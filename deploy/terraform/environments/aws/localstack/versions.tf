terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.5"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

provider "aws" {
  region                      = var.aws_region
  access_key                  = "mock_access_key"
  secret_key                  = "mock_secret_key"
  s3_use_path_style           = true
  skip_credentials_validation = true
  skip_metadata_api_check     = true
  skip_requesting_account_id  = true

  endpoints {
    ec2            = var.localstack_endpoint
    eks            = var.localstack_endpoint
    rds            = var.localstack_endpoint
    elasticache    = var.localstack_endpoint
    kafka          = var.localstack_endpoint
    secretsmanager = var.localstack_endpoint
    iam            = var.localstack_endpoint
    sts            = var.localstack_endpoint
  }

  default_tags {
    tags = {
      Project     = "tiny-ledger"
      Environment = var.environment
      ManagedBy   = "Terraform-LocalStack"
    }
  }
}
