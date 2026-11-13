# Secrets Manager entries consumed by the cluster through External Secrets.
#
#   <prefix>/db   database master + one password per service        (bootstrap Job, services)
#   <prefix>/app  shared application secrets                         (services)
#
# A third secret, <prefix>/jwt-keystore (the JWT signing key), is created by deploy.sh with the AWS
# CLI, not here, so the private key never enters Terraform state.
#
# recovery_window_in_days = 0 deletes secrets immediately on `terraform destroy`; without it a
# destroyed environment cannot be recreated for 7-30 days because the names stay reserved.

variable "prefix" {
  type = string
}

variable "kms_key_arn" {
  type = string
}

variable "db_services" {
  type = list(string)
}

variable "google_client_id" {
  type    = string
  default = ""
}

variable "google_client_secret" {
  type      = string
  default   = ""
  sensitive = true
}

variable "stripe_secret_key" {
  type      = string
  sensitive = true
}

variable "stripe_publishable_key" {
  type = string
}

variable "stripe_webhook_secret" {
  type      = string
  sensitive = true
}

variable "smtp_username" {
  type = string
}

variable "smtp_password" {
  type      = string
  sensitive = true
}

# --- generated values -----------------------------------------------------------------------
# special = false: these end up in JDBC URLs, shell scripts and SQL; a character that needs
# escaping in any of them is a bug waiting for a bad day.
resource "random_password" "db_master" {
  length  = 32
  special = false
}

resource "random_password" "db" {
  for_each = toset(var.db_services)
  length   = 32
  special  = false
}

resource "random_password" "internal_token" {
  length  = 48
  special = false
}

resource "random_password" "jwt_keystore" {
  length  = 24
  special = false
}

# Signs the OIDC "state" cookie. It MUST be the same on every auth-service replica, otherwise a
# Google sign-in that starts on one pod and returns to another fails.
resource "random_password" "auth_state_cookie" {
  length  = 48
  special = false
}

# 32 random bytes, standard base64: the format AUTH_DATA_KEY / SELLER_DATA_KEY (AES-256-GCM) expect.
resource "random_id" "auth_data_key" {
  byte_length = 32
}

resource "random_id" "seller_data_key" {
  byte_length = 32
}

# --- <prefix>/db -----------------------------------------------------------------------------
resource "aws_secretsmanager_secret" "db" {
  name                    = "${var.prefix}/db"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id = aws_secretsmanager_secret.db.id
  secret_string = jsonencode(merge(
    {
      username        = "dbadmin"
      master_password = random_password.db_master.result
    },
    { for s in var.db_services : "${s}_password" => random_password.db[s].result },
  ))
}

# --- <prefix>/app ----------------------------------------------------------------------------
resource "aws_secretsmanager_secret" "app" {
  name                    = "${var.prefix}/app"
  kms_key_id              = var.kms_key_arn
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    internal_api_token       = random_password.internal_token.result
    auth_data_key            = random_id.auth_data_key.b64_std
    seller_data_key          = random_id.seller_data_key.b64_std
    jwt_keystore_password    = random_password.jwt_keystore.result
    auth_state_cookie_secret = random_password.auth_state_cookie.result
    google_client_id         = var.google_client_id
    google_client_secret     = var.google_client_secret
    stripe_secret_key        = var.stripe_secret_key
    stripe_publishable_key   = var.stripe_publishable_key
    stripe_webhook_secret    = var.stripe_webhook_secret
    mail_username            = var.smtp_username
    mail_password            = var.smtp_password
  })
}

output "db_master_password" {
  value     = random_password.db_master.result
  sensitive = true
}

output "jwt_keystore_password" {
  value     = random_password.jwt_keystore.result
  sensitive = true
}

output "db_secret_arn" {
  value = aws_secretsmanager_secret.db.arn
}

output "app_secret_arn" {
  value = aws_secretsmanager_secret.app.arn
}
