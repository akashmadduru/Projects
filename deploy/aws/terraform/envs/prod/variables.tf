variable "region" {
  description = "AWS region for everything except CloudFront certificates and WAF (us-east-1)."
  type        = string
  default     = "ap-south-1"
}

variable "project" {
  description = "Short name used as a prefix for every resource."
  type        = string
  default     = "ecommerce"
}

variable "environment" {
  description = "Environment name; part of every resource name."
  type        = string
  default     = "prod"
}

variable "domain_name" {
  description = "Root domain, e.g. example.com. Creates shop.<domain>, admin.<domain>, api.<domain>."
  type        = string
}

variable "hosted_zone_id" {
  description = "Route 53 hosted zone id for domain_name (created or looked up by deploy.sh)."
  type        = string
}

variable "size_profile" {
  description = "lean = cheapest working topology (single NAT, small data tier). prod = full HA."
  type        = string
  default     = "lean"

  validation {
    condition     = contains(["lean", "prod"], var.size_profile)
    error_message = "size_profile must be \"lean\" or \"prod\"."
  }
}

variable "kubernetes_version" {
  description = "EKS control-plane version. Keep in step with the pinned Karpenter chart."
  type        = string
  default     = "1.31"
}

variable "vpc_cidr" {
  type    = string
  default = "10.0.0.0/16"
}

variable "api_access_cidrs" {
  description = "CIDRs allowed to reach the public EKS API endpoint (kubectl/helm from your machine or CI)."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "alert_email" {
  description = "Address subscribed to the alarm SNS topic. Blank = topic only, no subscription."
  type        = string
  default     = ""
}

# --- secrets supplied by the operator (deploy.sh reads them from config.env) ------------------
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
  description = "Required: payment-service refuses to start under the prod profile without it."
  type        = string
  sensitive   = true
}

variable "stripe_publishable_key" {
  type = string
}

variable "stripe_webhook_secret" {
  type      = string
  sensitive = true
}
