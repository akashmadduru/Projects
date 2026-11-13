# Redis for auth-service (user snapshot cache) and the gateway (edge rate limiter).
# In-transit encryption is ON, so clients must connect with TLS (SPRING_DATA_REDIS_SSL_ENABLED=true,
# set by the Helm chart).

variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_ids" {
  type = list(string)
}

variable "allowed_sg_ids" {
  type = list(string)
}

variable "node_type" {
  type = string
}

variable "num_nodes" {
  description = "1 = single node, 2+ = primary with replicas and automatic failover."
  type        = number
}

variable "kms_key_arn" {
  type = string
}

resource "aws_security_group" "redis" {
  name        = "${var.name}-redis"
  description = "Redis from the EKS nodes only"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "from_nodes" {
  for_each = toset(var.allowed_sg_ids)

  security_group_id            = aws_security_group.redis.id
  referenced_security_group_id = each.value
  from_port                    = 6379
  to_port                      = 6379
  ip_protocol                  = "tcp"
}

resource "aws_elasticache_subnet_group" "this" {
  name       = "${var.name}-redis"
  subnet_ids = var.subnet_ids
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id = "${var.name}-redis"
  description          = "${var.name} redis"

  engine               = "redis"
  engine_version       = "7.1"
  node_type            = var.node_type
  port                 = 6379
  parameter_group_name = "default.redis7"

  num_cache_clusters         = var.num_nodes
  automatic_failover_enabled = var.num_nodes > 1
  multi_az_enabled           = var.num_nodes > 1

  subnet_group_name  = aws_elasticache_subnet_group.this.name
  security_group_ids = [aws_security_group.redis.id]

  at_rest_encryption_enabled = true
  kms_key_id                 = var.kms_key_arn
  transit_encryption_enabled = true

  snapshot_retention_limit = 1
  apply_immediately        = true
}

output "primary_endpoint" {
  value = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "replication_group_id" {
  value = aws_elasticache_replication_group.this.replication_group_id
}
