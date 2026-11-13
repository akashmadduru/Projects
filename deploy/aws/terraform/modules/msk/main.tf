# Amazon MSK: three brokers, one per availability zone.
#
# CLIENT ACCESS IS PLAINTEXT + TLS, UNAUTHENTICATED, RESTRICTED BY SECURITY GROUP. That matches how
# the services connect today (several build their Kafka consumers by hand with only a bootstrap
# address, so they cannot yet do SASL or TLS). The brokers already accept TLS on 9094, so moving
# clients over later is a client-side change only, with no cluster rebuild.

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

variable "instance_type" {
  type = string
}

variable "volume_size_gb" {
  type = number
}

variable "kms_key_arn" {
  type = string
}

resource "aws_security_group" "msk" {
  name        = "${var.name}-msk"
  description = "Kafka from the EKS nodes only"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "plaintext" {
  for_each = toset(var.allowed_sg_ids)

  security_group_id            = aws_security_group.msk.id
  referenced_security_group_id = each.value
  from_port                    = 9092
  to_port                      = 9092
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "tls" {
  for_each = toset(var.allowed_sg_ids)

  security_group_id            = aws_security_group.msk.id
  referenced_security_group_id = each.value
  from_port                    = 9094
  to_port                      = 9094
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "inter_broker" {
  security_group_id            = aws_security_group.msk.id
  referenced_security_group_id = aws_security_group.msk.id
  ip_protocol                  = "-1"
}

resource "aws_vpc_security_group_egress_rule" "all" {
  security_group_id = aws_security_group.msk.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
}

resource "aws_msk_configuration" "this" {
  name           = "${var.name}-kafka"
  kafka_versions = ["3.8.x"]

  # Replication factor 3 / min ISR 2 so a single broker loss neither loses nor blocks writes.
  # Topics created by the services themselves (inventory/product declare replicas(1)) keep the
  # replication factor they ask for; this is the default for everything else, including the
  # internal offsets and transaction topics.
  server_properties = <<-PROPS
    auto.create.topics.enable=true
    default.replication.factor=3
    min.insync.replicas=2
    num.partitions=3
    offsets.topic.replication.factor=3
    transaction.state.log.replication.factor=3
    transaction.state.log.min.isr=2
  PROPS
}

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/aws/msk/${var.name}"
  retention_in_days = 30
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.name}-kafka"
  kafka_version          = "3.8.x"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = var.instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.volume_size_gb
      }
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  encryption_info {
    encryption_at_rest_kms_key_arn = var.kms_key_arn
    encryption_in_transit {
      client_broker = "TLS_PLAINTEXT"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }
}

output "bootstrap_brokers" {
  description = "Plaintext listener (port 9092)."
  value       = aws_msk_cluster.this.bootstrap_brokers
}

output "bootstrap_brokers_tls" {
  value = aws_msk_cluster.this.bootstrap_brokers_tls
}
