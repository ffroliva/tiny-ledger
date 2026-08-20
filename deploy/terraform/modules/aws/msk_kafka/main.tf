terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0.0"
    }
  }
}

resource "aws_security_group" "kafka" {
  name        = "${var.environment}-msk-sg"
  description = "Security group for ${var.environment} MSK Kafka cluster"
  vpc_id      = var.vpc_id

  # Port 9092: Plaintext (if unauthenticated enabled)
  ingress {
    description     = "Allow Kafka plaintext from EKS nodes"
    from_port       = 9092
    to_port         = 9092
    protocol        = "tcp"
    security_groups = compact(concat([var.eks_node_security_group_id], var.allowed_security_group_ids))
  }

  # Port 9094: TLS client authentication
  ingress {
    description     = "Allow Kafka TLS from EKS nodes"
    from_port       = 9094
    to_port         = 9094
    protocol        = "tcp"
    security_groups = compact(concat([var.eks_node_security_group_id], var.allowed_security_group_ids))
  }

  # Port 9098: SASL/IAM client authentication
  ingress {
    description     = "Allow Kafka SASL/IAM from EKS nodes"
    from_port       = 9098
    to_port         = 9098
    protocol        = "tcp"
    security_groups = compact(concat([var.eks_node_security_group_id], var.allowed_security_group_ids))
  }

  # Port 2181: Zookeeper (if used)
  ingress {
    description     = "Allow Zookeeper from EKS nodes"
    from_port       = 2181
    to_port         = 2181
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
      Name = "${var.environment}-msk-sg"
    }
  )
}

resource "aws_msk_cluster" "this" {
  cluster_name           = "${var.environment}-msk-kafka"
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.number_of_broker_nodes

  broker_node_group_info {
    instance_type   = var.instance_type
    client_subnets  = var.subnet_ids
    security_groups = [aws_security_group.kafka.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.volume_size
      }
    }
  }

  client_authentication {
    sasl {
      iam = true
    }
    unauthenticated = var.allow_unauthenticated
  }

  encryption_info {
    encryption_in_transit {
      client_broker = var.allow_unauthenticated ? "TLS_PLAINTEXT" : "TLS"
      in_cluster    = true
    }
  }

  tags = merge(
    var.tags,
    {
      Name = "${var.environment}-msk-kafka"
    }
  )
}
