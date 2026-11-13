# One PostgreSQL 17 instance hosting the twelve service databases. The databases and their
# least-privilege roles are created by the db-bootstrap Kubernetes Job (deploy/aws/k8s), because
# only something inside the VPC can reach the isolated subnets.

variable "name" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "subnet_group_name" {
  type = string
}

variable "allowed_sg_ids" {
  type = list(string)
}

variable "instance_class" {
  type = string
}

variable "multi_az" {
  type = bool
}

variable "allocated_storage" {
  type = number
}

variable "master_password" {
  type      = string
  sensitive = true
}

variable "kms_key_arn" {
  type = string
}

resource "aws_security_group" "db" {
  name        = "${var.name}-rds"
  description = "PostgreSQL from the EKS nodes only"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "from_nodes" {
  for_each = toset(var.allowed_sg_ids)

  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = each.value
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_db_parameter_group" "this" {
  name   = "${var.name}-pg17"
  family = "postgres17"

  # Refuse unencrypted client connections.
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }
}

resource "aws_db_instance" "this" {
  identifier = "${var.name}-postgres"

  engine         = "postgres"
  engine_version = "17"
  instance_class = var.instance_class

  allocated_storage     = var.allocated_storage
  max_allocated_storage = var.allocated_storage * 4
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = var.kms_key_arn

  username = "dbadmin"
  password = var.master_password

  multi_az               = var.multi_az
  db_subnet_group_name   = var.subnet_group_name
  vpc_security_group_ids = [aws_security_group.db.id]
  publicly_accessible    = false
  parameter_group_name   = aws_db_parameter_group.this.name

  backup_retention_period    = 7
  backup_window              = "21:00-22:00"
  maintenance_window         = "sun:22:30-sun:23:30"
  copy_tags_to_snapshot      = true
  auto_minor_version_upgrade = true
  apply_immediately          = true

  performance_insights_enabled          = true
  performance_insights_retention_period = 7
  performance_insights_kms_key_id       = var.kms_key_arn

  # Deletion protection is switched off, and a final snapshot taken, by destroy.sh through the RDS
  # API before `terraform destroy` runs, so an accidental destroy of just this module still stops
  # here but a deliberate teardown does not need a hand edit.
  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.name}-postgres-final"

  lifecycle {
    # auto_minor_version_upgrade moves the minor version underneath Terraform.
    ignore_changes = [engine_version]
  }
}

output "endpoint" {
  value = aws_db_instance.this.address
}

output "identifier" {
  value = aws_db_instance.this.identifier
}
