variable "aws_region" {
  description = "AWS region for LocalStack simulation"
  type        = string
  default     = "us-east-1"
}

variable "localstack_endpoint" {
  description = "LocalStack endpoint URL"
  type        = string
  default     = "http://localhost:4566"
}

variable "environment" {
  description = "Deployment environment name"
  type        = string
  default     = "localstack"
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "List of availability zones"
  type        = list(string)
  default     = ["us-east-1a", "us-east-1b", "us-east-1c"]
}

variable "public_subnet_cidrs" {
  description = "CIDR blocks for public subnets"
  type        = list(string)
  default     = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
}

variable "private_subnet_cidrs" {
  description = "CIDR blocks for private subnets"
  type        = list(string)
  default     = ["10.0.11.0/24", "10.0.12.0/24", "10.0.13.0/24"]
}

variable "cluster_name" {
  description = "Name of the EKS cluster"
  type        = string
  default     = "tiny-ledger-local-eks"
}

variable "postgres_database_name" {
  description = "Postgres database name"
  type        = string
  default     = "ledger"
}

variable "postgres_admin_username" {
  description = "Postgres admin username"
  type        = string
  default     = "ledger_admin"
}

variable "postgres_admin_password" {
  description = "Postgres admin password"
  type        = string
  default     = "localstack_secret_pw"
  sensitive   = true
}

variable "tags" {
  description = "Additional tags for all resources"
  type        = map(string)
  default     = {}
}
