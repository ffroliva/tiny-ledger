module "networking" {
  source = "../../../modules/aws/networking"

  vpc_cidr             = var.vpc_cidr
  availability_zones   = var.availability_zones
  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  environment          = var.environment
  cluster_name         = var.cluster_name
  tags                 = var.tags
}

resource "aws_security_group" "db_mock" {
  name        = "${var.environment}-postgres-mock-sg"
  description = "Security group for ${var.environment} mock PostgreSQL"
  vpc_id      = module.networking.vpc_id

  ingress {
    description = "Allow PostgreSQL access from within VPC"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
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
      Name = "${var.environment}-postgres-mock-sg"
    }
  )
}

module "secrets" {
  source = "../../../modules/aws/secrets"

  environment               = var.environment
  secret_name_prefix        = "tiny-ledger"
  service_account_namespace = "tiny-ledger"
  service_account_name      = "tiny-ledger-sa"

  secrets = {
    SPRING_DATASOURCE_URL          = "jdbc:postgresql://localhost:5432/${var.postgres_database_name}"
    SPRING_DATASOURCE_USERNAME     = var.postgres_admin_username
    SPRING_DATASOURCE_PASSWORD     = var.postgres_admin_password
    SPRING_DATA_REDIS_HOST         = "localhost"
    SPRING_DATA_REDIS_PORT         = "6379"
    SPRING_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092"
  }

  tags = var.tags

  depends_on = [module.networking]
}

