# Everything deploy.sh needs after `terraform apply`. Read with `terraform output -json`.

output "region" {
  value = var.region
}

output "account_id" {
  value = data.aws_caller_identity.current.account_id
}

output "name" {
  value = local.name
}

output "cluster_name" {
  value = module.eks.cluster_name
}

output "vpc_cidr" {
  value = var.vpc_cidr
}

output "ecr_registry" {
  value = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${var.region}.amazonaws.com"
}

output "karpenter_node_role_name" {
  value = module.karpenter.node_iam_role_name
}

output "karpenter_queue_name" {
  value = module.karpenter.queue_name
}

output "rds_endpoint" {
  value = module.rds.endpoint
}

output "redis_endpoint" {
  value = module.redis.primary_endpoint
}

output "kafka_bootstrap" {
  description = "Plaintext bootstrap brokers (in-VPC only; restricted by security group)."
  value       = module.msk.bootstrap_brokers
}

output "secret_prefix" {
  value = local.secret_prefix
}

output "alb_certificate_arn" {
  value = module.edge.alb_certificate_arn
}

output "api_domain" {
  value = local.api_domain
}

output "spa_buckets" {
  description = "Map site => S3 bucket name."
  value       = module.edge.buckets
}

output "cloudfront_distribution_ids" {
  description = "Map site => CloudFront distribution id."
  value       = module.edge.distribution_ids
}

output "site_urls" {
  value = module.edge.site_urls
}

output "alerts_topic_arn" {
  value = module.observability.topic_arn
}

output "assets_bucket" {
  value = aws_s3_bucket.assets.id
}

output "vpc_id" {
  value = module.vpc.vpc_id
}

output "jwt_keystore_password" {
  description = "Password for the JWT signing keystore deploy.sh creates (read with: terraform output -raw jwt_keystore_password)."
  value       = module.secrets.jwt_keystore_password
  sensitive   = true
}

output "spa_bucket_shop" {
  value = module.edge.buckets["shop"]
}

output "spa_bucket_admin" {
  value = module.edge.buckets["admin"]
}

output "distribution_shop" {
  value = module.edge.distribution_ids["shop"]
}

output "distribution_admin" {
  value = module.edge.distribution_ids["admin"]
}
