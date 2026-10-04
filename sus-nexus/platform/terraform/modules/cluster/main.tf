# Módulo cluster — EKS (nuvem BR, sa-east-1) ou RKE2 (on-prem, perfil CIS) atrás da mesma interface.

terraform {
  required_version = ">= 1.6.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.60"
    }
    tls = {
      source  = "hashicorp/tls"
      version = ">= 4.0"
    }
  }
}

locals {
  is_eks  = var.provider_kind == "eks"
  is_rke2 = var.provider_kind == "rke2"
  tags = merge(var.tags, {
    "sus-nexus.gov.br/environment" = var.environment
    "sus-nexus.gov.br/managed-by"  = "terraform"
  })
}

# ------------------------------------------------------------------------------------------------
# EKS
# ------------------------------------------------------------------------------------------------
data "aws_iam_policy_document" "cluster_assume" {
  count = local.is_eks ? 1 : 0
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  count              = local.is_eks ? 1 : 0
  name               = "${var.name}-eks-cluster"
  assume_role_policy = data.aws_iam_policy_document.cluster_assume[0].json
  tags               = local.tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  for_each = local.is_eks ? toset([
    "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy",
    "arn:aws:iam::aws:policy/AmazonEKSVPCResourceController",
  ]) : toset([])
  role       = aws_iam_role.cluster[0].name
  policy_arn = each.value
}

resource "aws_cloudwatch_log_group" "cluster" {
  count             = local.is_eks ? 1 : 0
  name              = "/aws/eks/${var.name}/cluster"
  retention_in_days = 90
  tags              = local.tags
}

resource "aws_eks_cluster" "this" {
  count    = local.is_eks ? 1 : 0
  name     = var.name
  version  = var.kubernetes_version
  role_arn = aws_iam_role.cluster[0].arn

  vpc_config {
    subnet_ids              = concat(var.subnet_ids, var.control_plane_subnet_ids)
    endpoint_private_access = true
    endpoint_public_access  = length(var.api_allowed_cidrs) > 0
    public_access_cidrs     = length(var.api_allowed_cidrs) > 0 ? var.api_allowed_cidrs : ["0.0.0.0/32"]
  }

  access_config {
    authentication_mode                         = "API_AND_CONFIG_MAP"
    bootstrap_cluster_creator_admin_permissions = true
  }

  enabled_cluster_log_types = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  dynamic "encryption_config" {
    for_each = var.kms_key_arn != null ? [1] : []
    content {
      resources = ["secrets"]
      provider {
        key_arn = var.kms_key_arn
      }
    }
  }

  tags       = local.tags
  depends_on = [aws_iam_role_policy_attachment.cluster, aws_cloudwatch_log_group.cluster]
}

# IRSA (OIDC) — usado por ESO/Velero/cert-manager com roles IAM
data "tls_certificate" "oidc" {
  count = local.is_eks ? 1 : 0
  url   = aws_eks_cluster.this[0].identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "this" {
  count           = local.is_eks ? 1 : 0
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.oidc[0].certificates[0].sha1_fingerprint]
  url             = aws_eks_cluster.this[0].identity[0].oidc[0].issuer
  tags            = local.tags
}

data "aws_iam_policy_document" "node_assume" {
  count = local.is_eks ? 1 : 0
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  count              = local.is_eks ? 1 : 0
  name               = "${var.name}-eks-node"
  assume_role_policy = data.aws_iam_policy_document.node_assume[0].json
  tags               = local.tags
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = local.is_eks ? toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
    "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore",
  ]) : toset([])
  role       = aws_iam_role.node[0].name
  policy_arn = each.value
}

resource "aws_launch_template" "node" {
  for_each = local.is_eks ? var.node_groups : {}
  name     = "${var.name}-${each.key}"
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required" # IMDSv2
    http_put_response_hop_limit = 1
  }
  block_device_mappings {
    device_name = "/dev/xvda"
    ebs {
      volume_size           = each.value.disk_size
      volume_type           = "gp3"
      encrypted             = true
      kms_key_id            = var.kms_key_arn
      delete_on_termination = true
    }
  }
  tag_specifications {
    resource_type = "instance"
    tags          = merge(local.tags, { Name = "${var.name}-${each.key}" })
  }
  tags = local.tags
}

resource "aws_eks_node_group" "this" {
  for_each        = local.is_eks ? var.node_groups : {}
  cluster_name    = aws_eks_cluster.this[0].name
  node_group_name = each.key
  node_role_arn   = aws_iam_role.node[0].arn
  subnet_ids      = var.subnet_ids
  instance_types  = each.value.instance_types
  capacity_type   = each.value.capacity_type
  labels          = each.value.labels

  scaling_config {
    min_size     = each.value.min_size
    max_size     = each.value.max_size
    desired_size = each.value.desired_size
  }

  update_config {
    max_unavailable_percentage = 25
  }

  launch_template {
    id      = aws_launch_template.node[each.key].id
    version = aws_launch_template.node[each.key].latest_version
  }

  dynamic "taint" {
    for_each = each.value.taints
    content {
      key    = taint.value.key
      value  = taint.value.value
      effect = taint.value.effect
    }
  }

  tags       = local.tags
  depends_on = [aws_iam_role_policy_attachment.node]

  lifecycle {
    ignore_changes = [scaling_config[0].desired_size] # cluster-autoscaler
  }
}

# Add-ons gerenciados (CNI padrão trocado por Cilium via Argo em cluster novo: vpc-cni em modo chaining)
resource "aws_eks_addon" "this" {
  for_each                    = local.is_eks ? toset(["vpc-cni", "coredns", "kube-proxy", "aws-ebs-csi-driver", "snapshot-controller"]) : toset([])
  cluster_name                = aws_eks_cluster.this[0].name
  addon_name                  = each.value
  resolve_conflicts_on_update = "OVERWRITE"
  tags                        = local.tags
  depends_on                  = [aws_eks_node_group.this]
}

# ------------------------------------------------------------------------------------------------
# RKE2 (on-prem)
# ------------------------------------------------------------------------------------------------
module "rke2" {
  count               = local.is_rke2 ? 1 : 0
  source              = "../onprem-rke2"
  cluster_name        = var.name
  environment         = var.environment
  rke2_version        = var.rke2.rke2_version
  api_vip             = var.rke2.api_vip
  cluster_token       = var.rke2.cluster_token
  ssh_authorized_keys = var.rke2.ssh_authorized_keys
  registry_mirror     = var.rke2.registry_mirror
  cluster_domain_sans = var.rke2.cluster_domain_sans
  output_dir          = var.rke2.output_dir
  nodes               = var.rke2.nodes
}
