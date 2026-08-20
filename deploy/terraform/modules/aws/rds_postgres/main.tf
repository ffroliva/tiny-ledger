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

resource "random_password" "db_password" {
  length           = 24
  special          = true
  override_special = "!#$%&*()-_=+[]{}<>:?"
}

resource "aws_db_subnet_group" "this" {
  name        = "${var.environment}-postgres-subnet-group"
  subnet_ids  = var.subnet_ids
  description = "Database subnet group for ${var.environment} PostgreSQL"

  tags = merge(
    var.tags,
    {
      Name = "${var.environment}-postgres-subnet-group"
    }
  )
}

resource "aws_security_group" "db" {
  name        = "${var.environment}-postgres-sg"
  description = "Security group for ${var.environment} RDS PostgreSQL"
  vpc_id      = var.vpc_id

  ingress {
    description     = "Allow PostgreSQL access from EKS nodes"
    from_port       = 5432
    to_port         = 5432
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
      Name = "${var.environment}-postgres-sg"
    }
  )
}

resource "aws_db_instance" "this" {
  identifier                  = "${var.environment}-postgres"
  engine                      = "postgres"
  engine_version              = var.postgres_version
  instance_class              = var.instance_class
  allocated_storage           = var.allocated_storage
  max_allocated_storage       = var.max_allocated_storage
  storage_type                = "gp3"
  storage_encrypted           = true
  multi_az                    = var.multi_az
  db_name                     = var.database_name
  username                    = var.admin_username
  password                    = var.admin_password != "" ? var.admin_password : random_password.db_password.result
  db_subnet_group_name        = aws_db_subnet_group.this.name
  vpc_security_group_ids      = [aws_security_group.db.id]
  publicly_accessible         = false
  auto_minor_version_upgrade  = true
  allow_major_version_upgrade = false
  skip_final_snapshot         = var.skip_final_snapshot
  final_snapshot_identifier   = var.skip_final_snapshot ? null : "${var.environment}-postgres-final-snapshot"
  deletion_protection         = var.deletion_protection

  tags = merge(
    var.tags,
    {
      Name = "${var.environment}-postgres"
    }
  )
}
