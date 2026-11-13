# =============================================================================
# One environment of the platform on AWS. Mirrors the target architecture diagram:
#
#   Route 53 -> CloudFront (+WAF) -> S3 (two SPAs)  and  /api/* -> ALB -> gateway (EKS)
#   VPC: public (ALB, NAT) / private (EKS) / isolated data (RDS, MSK, ElastiCache) subnets
#
# Cluster add-ons that are installed with Helm (AWS Load Balancer Controller, Karpenter, External
# Secrets, metrics-server) are NOT managed here: deploy.sh installs them. Terraform owns only the
# AWS-side prerequisites (IAM roles, queues, pod-identity associations) they need.
# =============================================================================

data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = "${var.project}-${var.environment}"
  lean = var.size_profile == "lean"
  azs  = slice(data.aws_availability_zones.available.names, 0, 3)

  tags = {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  # Every workload deployed to the cluster (ECR repository per entry).
  services = [
    "gateway", "auth-service", "product-service", "inventory-service", "rating-service",
    "user-service", "cart-service", "order-service", "payment-service", "promotion-service",
    "notification-service", "seller-service", "support-service",
  ]

  # Services that own a PostgreSQL database (database <x>_db, role <x>_service).
  db_services = [
    "auth", "product", "inventory", "user", "cart", "order",
    "rating", "payment", "promotion", "notification", "seller", "support",
  ]

  api_domain    = "api.${var.domain_name}"
  secret_prefix = local.name

  # --- size profile ------------------------------------------------------------------------
  profile = {
    system_instance_types = local.lean ? ["t3.medium"] : ["m5.large"]
    rds_class             = local.lean ? "db.t4g.medium" : "db.m6g.large"
    rds_multi_az          = !local.lean
    rds_storage_gb        = local.lean ? 50 : 100
    redis_node_type       = local.lean ? "cache.t4g.small" : "cache.t4g.medium"
    redis_nodes           = local.lean ? 1 : 2
    msk_instance_type     = local.lean ? "kafka.t3.small" : "kafka.m5.large"
    msk_volume_gb         = local.lean ? 50 : 200
    cloudfront_price      = local.lean ? "PriceClass_100" : "PriceClass_200"
  }
}

# -----------------------------------------------------------------------------
# Encryption key shared by secrets, RDS, MSK and ElastiCache.
# -----------------------------------------------------------------------------
resource "aws_kms_key" "main" {
  description             = "${local.name} data encryption"
  deletion_window_in_days = 7
  enable_key_rotation     = true
}

resource "aws_kms_alias" "main" {
  name          = "alias/${local.name}"
  target_key_id = aws_kms_key.main.key_id
}

# -----------------------------------------------------------------------------
# Network
# -----------------------------------------------------------------------------
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.7"

  name = local.name
  cidr = var.vpc_cidr
  azs  = local.azs

  private_subnets  = [for k in range(3) : cidrsubnet(var.vpc_cidr, 4, k)]
  public_subnets   = [for k in range(3) : cidrsubnet(var.vpc_cidr, 8, 48 + k)]
  database_subnets = [for k in range(3) : cidrsubnet(var.vpc_cidr, 8, 52 + k)]

  # Isolated data tier: own route table with NO route to a NAT gateway.
  create_database_subnet_group       = true
  create_database_subnet_route_table = true

  enable_nat_gateway     = true
  single_nat_gateway     = local.lean
  one_nat_gateway_per_az = !local.lean

  enable_dns_hostnames = true
  enable_dns_support   = true

  public_subnet_tags = {
    "kubernetes.io/role/elb" = "1"
  }
  private_subnet_tags = {
    "kubernetes.io/role/internal-elb" = "1"
    "karpenter.sh/discovery"          = local.name
  }
}

# Gateway endpoint for S3 (free) always; interface endpoints only in the prod profile, where they
# pay for themselves in NAT data-processing charges and keep AWS API traffic off the internet.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = module.vpc.vpc_id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids = concat(
    module.vpc.private_route_table_ids,
    module.vpc.database_route_table_ids,
  )
}

resource "aws_security_group" "vpc_endpoints" {
  count       = local.lean ? 0 : 1
  name        = "${local.name}-vpc-endpoints"
  description = "HTTPS from inside the VPC to interface endpoints"
  vpc_id      = module.vpc.vpc_id

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }
}

resource "aws_vpc_endpoint" "interface" {
  for_each = local.lean ? toset([]) : toset(["ecr.api", "ecr.dkr", "secretsmanager", "logs", "sts"])

  vpc_id              = module.vpc.vpc_id
  service_name        = "com.amazonaws.${var.region}.${each.key}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = module.vpc.private_subnets
  security_group_ids  = [aws_security_group.vpc_endpoints[0].id]
  private_dns_enabled = true
}

# -----------------------------------------------------------------------------
# EKS
# -----------------------------------------------------------------------------
resource "aws_iam_role" "ebs_csi" {
  name = "${local.name}-ebs-csi"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.ebs_csi.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "~> 20.31"

  cluster_name    = local.name
  cluster_version = var.kubernetes_version

  vpc_id                   = module.vpc.vpc_id
  subnet_ids               = module.vpc.private_subnets
  control_plane_subnet_ids = module.vpc.private_subnets

  cluster_endpoint_public_access       = true
  cluster_endpoint_public_access_cidrs = var.api_access_cidrs
  cluster_endpoint_private_access      = true

  # Whoever runs deploy.sh becomes cluster admin.
  enable_cluster_creator_admin_permissions = true
  authentication_mode                      = "API_AND_CONFIG_MAP"

  cloudwatch_log_group_retention_in_days = 30
  cluster_enabled_log_types              = ["api", "audit", "authenticator"]

  cluster_addons = {
    coredns    = {}
    kube-proxy = {}
    vpc-cni = {
      before_compute = true
      # Enforces the NetworkPolicies in the Helm chart (default-deny + explicit allows).
      configuration_values = jsonencode({
        enableNetworkPolicy = "true"
      })
    }
    eks-pod-identity-agent = {
      before_compute = true
    }
    aws-ebs-csi-driver = {
      pod_identity_association = [{
        role_arn        = aws_iam_role.ebs_csi.arn
        service_account = "ebs-csi-controller-sa"
      }]
    }
    amazon-cloudwatch-observability = {}
  }

  # Small fixed node group for cluster add-ons and the autoscaler itself. Application pods run on
  # nodes that Karpenter provisions on demand.
  eks_managed_node_groups = {
    system = {
      instance_types = local.profile.system_instance_types
      min_size       = 2
      max_size       = 3
      desired_size   = 2
      labels         = { workload = "system" }
      iam_role_additional_policies = {
        cloudwatch = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
        ssm        = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
      }
    }
  }

  # Karpenter discovers the security group to attach to the nodes it launches by this tag.
  node_security_group_tags = {
    "karpenter.sh/discovery" = local.name
  }
}

# --- Karpenter: node IAM role, controller role (Pod Identity), interruption queue ------------------
module "karpenter" {
  source  = "terraform-aws-modules/eks/aws//modules/karpenter"
  version = "~> 20.31"

  cluster_name = module.eks.cluster_name

  # Karpenter v1.x needs the v1 permission set.
  enable_v1_permissions = true

  enable_pod_identity             = true
  create_pod_identity_association = true
  namespace                       = "kube-system"

  node_iam_role_additional_policies = {
    cloudwatch = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
    ssm        = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
  }
}

# --- AWS Load Balancer Controller ---------------------------------------------------------------
module "pod_identity_lbc" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "~> 1.7"

  name = "${local.name}-aws-lbc"

  attach_aws_lb_controller_policy = true

  associations = {
    main = {
      cluster_name    = module.eks.cluster_name
      namespace       = "kube-system"
      service_account = "aws-load-balancer-controller"
    }
  }
}

# --- External Secrets Operator: read-only access to this environment's secrets ------------------
resource "aws_iam_role" "external_secrets" {
  name = "${local.name}-external-secrets"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
}

resource "aws_iam_role_policy" "external_secrets" {
  name = "read-environment-secrets"
  role = aws_iam_role.external_secrets.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
        Resource = "arn:aws:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:${local.secret_prefix}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = aws_kms_key.main.arn
      },
    ]
  })
}

resource "aws_eks_pod_identity_association" "external_secrets" {
  cluster_name    = module.eks.cluster_name
  namespace       = "external-secrets"
  service_account = "external-secrets"
  role_arn        = aws_iam_role.external_secrets.arn
}

# -----------------------------------------------------------------------------
# Secrets (generated here; operator-supplied values come from variables)
# -----------------------------------------------------------------------------
module "secrets" {
  source = "../../modules/secrets"

  prefix      = local.secret_prefix
  kms_key_arn = aws_kms_key.main.arn
  db_services = local.db_services

  google_client_id       = var.google_client_id
  google_client_secret   = var.google_client_secret
  stripe_secret_key      = var.stripe_secret_key
  stripe_publishable_key = var.stripe_publishable_key
  stripe_webhook_secret  = var.stripe_webhook_secret

  smtp_username = module.ses.smtp_username
  smtp_password = module.ses.smtp_password
}

# -----------------------------------------------------------------------------
# Data tier (isolated subnets, reachable only from the EKS node security group)
# -----------------------------------------------------------------------------
module "rds" {
  source = "../../modules/rds"

  name              = local.name
  vpc_id            = module.vpc.vpc_id
  subnet_group_name = module.vpc.database_subnet_group_name
  allowed_sg_ids    = [module.eks.node_security_group_id]
  instance_class    = local.profile.rds_class
  multi_az          = local.profile.rds_multi_az
  allocated_storage = local.profile.rds_storage_gb
  master_password   = module.secrets.db_master_password
  kms_key_arn       = aws_kms_key.main.arn
}

module "redis" {
  source = "../../modules/elasticache"

  name           = local.name
  vpc_id         = module.vpc.vpc_id
  subnet_ids     = module.vpc.database_subnets
  allowed_sg_ids = [module.eks.node_security_group_id]
  node_type      = local.profile.redis_node_type
  num_nodes      = local.profile.redis_nodes
  kms_key_arn    = aws_kms_key.main.arn
}

module "msk" {
  source = "../../modules/msk"

  name           = local.name
  vpc_id         = module.vpc.vpc_id
  subnet_ids     = module.vpc.database_subnets
  allowed_sg_ids = [module.eks.node_security_group_id]
  instance_type  = local.profile.msk_instance_type
  volume_size_gb = local.profile.msk_volume_gb
  kms_key_arn    = aws_kms_key.main.arn
}

# -----------------------------------------------------------------------------
# Container registry, edge, e-mail, monitoring
# -----------------------------------------------------------------------------
module "ecr" {
  source = "../../modules/ecr"

  project  = var.project
  services = local.services
}

module "waf" {
  source = "../../modules/waf"

  providers = {
    aws = aws.us_east_1
  }

  name = local.name
}

module "edge" {
  source = "../../modules/edge"

  providers = {
    aws           = aws
    aws.us_east_1 = aws.us_east_1
  }

  name           = local.name
  domain_name    = var.domain_name
  api_domain     = local.api_domain
  hosted_zone_id = var.hosted_zone_id
  waf_acl_arn    = module.waf.acl_arn
  price_class    = local.profile.cloudfront_price
}

module "ses" {
  source = "../../modules/ses"

  name           = local.name
  domain_name    = var.domain_name
  hosted_zone_id = var.hosted_zone_id
}

resource "aws_s3_bucket" "assets" {
  bucket        = "${local.name}-assets-${data.aws_caller_identity.current.account_id}"
  force_destroy = true
}

resource "aws_s3_bucket_public_access_block" "assets" {
  bucket                  = aws_s3_bucket.assets.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "assets" {
  bucket = aws_s3_bucket.assets.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

module "observability" {
  source = "../../modules/observability"

  name           = local.name
  cluster_name   = module.eks.cluster_name
  alert_email    = var.alert_email
  rds_identifier = module.rds.identifier
  redis_group_id = module.redis.replication_group_id
}
