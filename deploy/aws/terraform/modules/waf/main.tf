# WAF ACL attached to both CloudFront distributions. Instantiate with the us-east-1 provider:
# CLOUDFRONT-scoped ACLs only exist there.
#
# The WAF sees the REAL viewer address (it sits in front of CloudFront's origin fetch), which is why
# per-IP rate limiting lives here and not on the ALB, where every request would appear to come from
# CloudFront. The gateway keeps its own per-user Redis limiter for authenticated traffic.

terraform {
  required_providers {
    aws = {
      source = "hashicorp/aws"
    }
  }
}

variable "name" {
  type = string
}

locals {
  # Credential-guessing surfaces get a much tighter per-IP limit than the rest of the API.
  sensitive_paths = [
    "/api/auth/login",
    "/api/auth/register",
    "/api/auth/otp",
    "/api/auth/password",
  ]
}

resource "aws_wafv2_web_acl" "edge" {
  name  = "${var.name}-edge"
  scope = "CLOUDFRONT"

  default_action {
    allow {}
  }

  rule {
    name     = "rate-limit-sensitive-auth"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 100 # requests per 5 minutes per IP
        aggregate_key_type = "IP"

        scope_down_statement {
          or_statement {
            dynamic "statement" {
              for_each = local.sensitive_paths
              content {
                byte_match_statement {
                  search_string         = statement.value
                  positional_constraint = "STARTS_WITH"
                  field_to_match {
                    uri_path {}
                  }
                  text_transformation {
                    priority = 0
                    type     = "NONE"
                  }
                }
              }
            }
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-rate-auth"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "rate-limit-global"
    priority = 2

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 5000 # requests per 5 minutes per IP; generous so shared NATs are not hit
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-rate-global"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-common"
    priority = 10

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"

        # The 8 KB body limit would reject seller bulk CSV uploads (up to 2 MB) and larger JSON
        # payloads. Count it instead of blocking.
        rule_action_override {
          name = "SizeRestrictions_BODY"
          action_to_use {
            count {}
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-common"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-known-bad-inputs"
    priority = 20

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-ip-reputation"
    priority = 30

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesAmazonIpReputationList"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "${var.name}-ip-reputation"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-edge"
    sampled_requests_enabled   = true
  }
}

output "acl_arn" {
  value = aws_wafv2_web_acl.edge.arn
}
