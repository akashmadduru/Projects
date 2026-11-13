# Amazon SES for notification-service (it already speaks SMTP, so this is configuration only).
#
# NEW SES ACCOUNTS START IN THE SANDBOX: mail can only be sent to verified addresses until you ask
# AWS to lift the limit (SES console -> Account dashboard -> Request production access). That is a
# manual AWS review and cannot be automated here.

variable "name" {
  type = string
}

variable "domain_name" {
  type = string
}

variable "hosted_zone_id" {
  type = string
}

data "aws_region" "current" {}

resource "aws_ses_domain_identity" "this" {
  domain = var.domain_name
}

resource "aws_route53_record" "verification" {
  zone_id = var.hosted_zone_id
  name    = "_amazonses.${var.domain_name}"
  type    = "TXT"
  ttl     = 600
  records = [aws_ses_domain_identity.this.verification_token]
}

resource "aws_ses_domain_dkim" "this" {
  domain = aws_ses_domain_identity.this.domain
}

resource "aws_route53_record" "dkim" {
  count = 3

  zone_id = var.hosted_zone_id
  name    = "${aws_ses_domain_dkim.this.dkim_tokens[count.index]}._domainkey.${var.domain_name}"
  type    = "CNAME"
  ttl     = 600
  records = ["${aws_ses_domain_dkim.this.dkim_tokens[count.index]}.dkim.amazonses.com"]
}

# SMTP credentials: an IAM user that may only send mail. The SMTP password is derived from its
# secret access key by the provider.
resource "aws_iam_user" "smtp" {
  name          = "${var.name}-ses-smtp"
  force_destroy = true
}

resource "aws_iam_user_policy" "smtp" {
  name = "send-mail"
  user = aws_iam_user.smtp.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ses:SendRawEmail"]
      Resource = "*"
    }]
  })
}

resource "aws_iam_access_key" "smtp" {
  user = aws_iam_user.smtp.name
}

output "smtp_host" {
  value = "email-smtp.${data.aws_region.current.name}.amazonaws.com"
}

output "smtp_username" {
  value = aws_iam_access_key.smtp.id
}

output "smtp_password" {
  value     = aws_iam_access_key.smtp.ses_smtp_password_v4
  sensitive = true
}
