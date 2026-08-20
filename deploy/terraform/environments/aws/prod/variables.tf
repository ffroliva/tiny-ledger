variable "aws_region" {
  description = "AWS region for deployment"
  type        = string
  default     = "eu-west-1"
}

variable "environment" {
  description = "Deployment environment name"
  type        = string
  default     = "prod"
}

variable "vpc_cidr" {
  description = "CIDR block for the VPC"
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = "List of availability zones"
  type        = list(string)
  default     = ["eu-west-1a", "eu-west-1b", "eu-west-1c"]
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
  default     = "tiny-ledger-prod-eks"
}

variable "kubernetes_version" {
  description = "Kubernetes version for EKS"
  type        = string
  default     = "1.31"
}

variable "node_instance_types" {
  description = "EKS node instance types"
  type        = list(string)
  default     = ["t3.medium"]
}

variable "node_desired_size" {
  description = "Desired number of worker nodes"
  type        = number
  default     = 3
}

variable "node_min_size" {
  description = "Minimum number of worker nodes"
  type        = number
  default     = 2
}

variable "node_max_size" {
  description = "Maximum number of worker nodes"
  type        = number
  default     = 6
}

variable "postgres_instance_class" {
  description = "RDS Postgres instance class"
  type        = string
  default     = "db.t4g.medium"
}

variable "postgres_allocated_storage" {
  description = "RDS Postgres allocated storage in GB"
  type        = number
  default     = 20
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
  description = "Postgres admin password (optional, generated if blank)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "redis_node_type" {
  description = "ElastiCache Redis node type"
  type        = string
  default     = "cache.t4g.medium"
}

variable "redis_num_cache_clusters" {
  description = "Number of Redis cache nodes"
  type        = number
  default     = 2
}

variable "redis_auth_token" {
  description = "Redis AUTH password (optional, generated if blank)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "kafka_instance_type" {
  description = "MSK Kafka broker instance type"
  type        = string
  default     = "kafka.m5.large"
}

variable "kafka_volume_size" {
  description = "MSK Kafka EBS volume size in GB per broker"
  type        = number
  default     = 100
}

variable "tags" {
  description = "Additional tags for all resources"
  type        = map(string)
  default     = {}
}
