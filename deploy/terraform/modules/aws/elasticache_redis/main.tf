terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0.0"
    }
    random = {
      source  = "hashicorp/random"
      version = ">= 3.5.0"
    }
  }
}

resource "random_password" "auth_token" {
  length  = 32
  special = false
}

resource "aws_elasticache_subnet_group" "this" {
  name        = "${var.environment}-redis-subnet-group"
  subnet_ids  = var.subnet_ids
  description = "Subnet group for ${var.environment} ElastiCache Redis"

  tags = merge(
    var.tags,
    {
      Name = "${var.environment}-redis-subnet-group"
    }
  )
}

resource "aws_security_group" "redis" {
  name        = "${var.environment}-redis-sg"
  description = "Security group for ${var.environment} Redis"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Allow Redis access from EKS nodes"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = compact(concat([var.eks_node_security_group_id], var.allowed_security_group_ids))
  }

  egress {
    description = "Allow all outbound traffic"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = merge(
    var.tags,
    {
      Name = "${var.environment}-redis-sg"
    }
  )
}

resource "aws_elasticache_replication_group" "this" {
  replication_group_id       = "${var.environment}-redis"
  description                = "Redis cluster for ${var.environment}"
  node_type                  = var.node_type
  engine                     = "redis"
  engine_version             = var.engine_version
  port                       = 6379
  parameter_group_name       = var.parameter_group_name
  subnet_group_name          = aws_elasticache_subnet_group.this.name
  security_group_ids         = [aws_security_group.redis.id]
  num_cache_clusters         = var.num_cache_clusters
  automatic_failover_enabled = var.automatic_failover_enabled
  multi_az_enabled           = var.multi_az_enabled
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  auth_token                 = var.auth_token != "" ? var.auth_token : random_password.auth_token.result
  apply_immediately          = true
  auto_minor_version_upgrade = true

  tags = merge(
    var.tags,
    {
      Name = "${var.environment}-redis"
    }
  )
}
