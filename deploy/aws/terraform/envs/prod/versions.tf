terraform {
  # >= 1.10 for the S3 backend's native state locking (`use_lockfile`); no DynamoDB table needed.
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.82"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # Bucket, key, region and `use_lockfile` are supplied by deploy.sh / destroy.sh with
  # -backend-config, so this directory contains no account-specific values.
  backend "s3" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}

# CloudFront certificates and CLOUDFRONT-scoped WAF ACLs must live in us-east-1.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = local.tags
  }
}
