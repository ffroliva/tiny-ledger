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

  # Example S3 remote backend configuration
  # backend "s3" {
  #   bucket         = "tiny-ledger-tfstate-prod"
  #   key            = "aws/prod/terraform.tfstate"
  #   region         = "eu-west-1"
  #   dynamodb_table = "tiny-ledger-tf-locks"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "tiny-ledger"
      Environment = var.environment
      ManagedBy   = "Terraform"
    }
  }
}
