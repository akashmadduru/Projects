# Public edge: two single-page apps on S3 behind CloudFront, plus the API.
#
#   https://shop.<domain>    React storefront
#   https://admin.<domain>   Vue admin + seller portal
#   https://api.<domain>     the gateway behind an ALB (created by the AWS Load Balancer
#                            Controller; its DNS record is written by deploy.sh)
#
# BOTH SPA HOSTS ALSO FORWARD /api/*, /oauth2/*, /login/oauth2/* AND /.well-known/* TO THE API.
# The storefront issues relative `/api/...` calls and the refresh cookie is HttpOnly, so serving the
# API from the same origin as the page keeps every cookie first-party and needs no cross-origin
# setup for the browser.
#
# SPA deep links (/products/42) are rewritten to /index.html by a CloudFront FUNCTION on the S3
# behaviour only. Distribution-wide custom error responses are deliberately NOT used: they would
# also rewrite genuine API 404s into index.html with a 200.

terraform {
  required_providers {
    aws = {
      source                = "hashicorp/aws"
      configuration_aliases = [aws.us_east_1]
    }
  }
}

variable "name" {
  type = string
}

variable "domain_name" {
  type = string
}

variable "api_domain" {
  type = string
}

variable "hosted_zone_id" {
  type = string
}

variable "waf_acl_arn" {
  type = string
}

variable "price_class" {
  type = string
}

data "aws_caller_identity" "current" {}

locals {
  sites = {
    shop  = "shop.${var.domain_name}"
    admin = "admin.${var.domain_name}"
  }

  api_paths = ["/api/*", "/oauth2/*", "/login/oauth2/*", "/.well-known/*"]

  # Fixed, AWS-documented hosted zone id shared by every CloudFront distribution.
  cloudfront_zone_id = "Z2FDTNDATAQYW2"
}

data "aws_cloudfront_cache_policy" "optimized" {
  name = "Managed-CachingOptimized"
}

data "aws_cloudfront_cache_policy" "disabled" {
  name = "Managed-CachingDisabled"
}

# Forwards every viewer header INCLUDING Host. The API must see the host the visitor used
# (shop.<domain> or admin.<domain>): auth-service builds the Google sign-in callback URL from it, and
# that URL has to be on the same host as the page that started the login.
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

# -----------------------------------------------------------------------------
# Certificates
# -----------------------------------------------------------------------------
# ALB certificate (regional). It covers api.<domain> AND the two site hosts, because CloudFront
# forwards the visitor's Host header (shop.<domain> / admin.<domain>) to the ALB and validates the
# origin certificate against it.
resource "aws_acm_certificate" "alb" {
  domain_name               = var.api_domain
  subject_alternative_names = [local.sites.shop, local.sites.admin]
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "alb_validation" {
  for_each = {
    for dvo in aws_acm_certificate.alb.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  allow_overwrite = true
  zone_id         = var.hosted_zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
}

resource "aws_acm_certificate_validation" "alb" {
  certificate_arn         = aws_acm_certificate.alb.arn
  validation_record_fqdns = [for r in aws_route53_record.alb_validation : r.fqdn]
}

# CloudFront certificate (must be us-east-1) for shop + admin
resource "aws_acm_certificate" "cloudfront" {
  provider = aws.us_east_1

  domain_name               = local.sites.shop
  subject_alternative_names = [local.sites.admin]
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "cloudfront_validation" {
  for_each = {
    for dvo in aws_acm_certificate.cloudfront.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  allow_overwrite = true
  zone_id         = var.hosted_zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
}

resource "aws_acm_certificate_validation" "cloudfront" {
  provider = aws.us_east_1

  certificate_arn         = aws_acm_certificate.cloudfront.arn
  validation_record_fqdns = [for r in aws_route53_record.cloudfront_validation : r.fqdn]
}

# -----------------------------------------------------------------------------
# Buckets (private; reachable only through CloudFront via Origin Access Control)
# -----------------------------------------------------------------------------
resource "aws_s3_bucket" "site" {
  for_each = local.sites

  bucket        = "${var.name}-${each.key}-${data.aws_caller_identity.current.account_id}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "site" {
  for_each = aws_s3_bucket.site

  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "site" {
  for_each = aws_s3_bucket.site

  bucket = each.value.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "${var.name}-s3"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# -----------------------------------------------------------------------------
# CloudFront
# -----------------------------------------------------------------------------
resource "aws_cloudfront_function" "spa_rewrite" {
  name    = "${var.name}-spa-rewrite"
  runtime = "cloudfront-js-2.0"
  publish = true
  comment = "Rewrite extension-less paths to /index.html (SPA deep links)"

  code = <<-JS
    function handler(event) {
      var request = event.request;
      var last = request.uri.substring(request.uri.lastIndexOf('/') + 1);
      if (last.indexOf('.') === -1) {
        request.uri = '/index.html';
      }
      return request;
    }
  JS
}

resource "aws_cloudfront_response_headers_policy" "security" {
  name = "${var.name}-security-headers"

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      preload                    = true
      override                   = true
    }
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
  }

  custom_headers_config {
    items {
      header   = "Permissions-Policy"
      value    = "camera=(), microphone=(), geolocation=(self), payment=(self)"
      override = true
    }
  }
}

resource "aws_cloudfront_distribution" "site" {
  for_each = local.sites

  enabled             = true
  is_ipv6_enabled     = true
  http_version        = "http2and3"
  price_class         = var.price_class
  aliases             = [each.value]
  default_root_object = "index.html"
  web_acl_id          = var.waf_acl_arn
  comment             = "${var.name} ${each.key}"

  origin {
    origin_id                = "s3"
    domain_name              = aws_s3_bucket.site[each.key].bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  origin {
    origin_id   = "api"
    domain_name = var.api_domain

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      origin_read_timeout    = 60
    }
  }

  default_cache_behavior {
    target_origin_id           = "s3"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.optimized.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_rewrite.arn
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.api_paths
    content {
      path_pattern             = ordered_cache_behavior.value
      target_origin_id         = "api"
      viewer_protocol_policy   = "https-only"
      allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods           = ["GET", "HEAD"]
      compress                 = true
      cache_policy_id          = data.aws_cloudfront_cache_policy.disabled.id
      origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cloudfront.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

resource "aws_s3_bucket_policy" "site" {
  for_each = aws_s3_bucket.site

  bucket = each.value.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AllowCloudFrontRead"
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = "s3:GetObject"
      Resource  = "${each.value.arn}/*"
      Condition = {
        StringEquals = {
          "AWS:SourceArn" = aws_cloudfront_distribution.site[each.key].arn
        }
      }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.site]
}

resource "aws_route53_record" "site_a" {
  for_each = local.sites

  zone_id = var.hosted_zone_id
  name    = each.value
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.site[each.key].domain_name
    zone_id                = local.cloudfront_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "site_aaaa" {
  for_each = local.sites

  zone_id = var.hosted_zone_id
  name    = each.value
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.site[each.key].domain_name
    zone_id                = local.cloudfront_zone_id
    evaluate_target_health = false
  }
}

output "alb_certificate_arn" {
  value = aws_acm_certificate_validation.alb.certificate_arn
}

output "buckets" {
  value = { for k, b in aws_s3_bucket.site : k => b.id }
}

output "distribution_ids" {
  value = { for k, d in aws_cloudfront_distribution.site : k => d.id }
}

output "site_urls" {
  value = { for k, h in local.sites : k => "https://${h}" }
}
