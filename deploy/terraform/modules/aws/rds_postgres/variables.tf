variable "environment" {
  description = "Environment name"
  type        = string
  default     = "prod"
}

variable "vpc_id" {
  description = "VPC ID where RDS is deployed"
  type        = string
}

variable "subnet_ids" {
  description = "Subnet IDs for the DB Subnet Group"
  type        = list(string)
}

variable "eks_node_security_group_id" {
  description = "Security Group ID of the EKS worker nodes"
  type        = string
  default     = ""
}

variable "allowed_security_group_ids" {
  description = "Additional Security Group IDs allowed to connect to RDS"
  type        = list(string)
  default     = []
}

variable "postgres_version" {
  description = "PostgreSQL engine version"
  type        = string
  default     = "16.4"
}

variable "instance_class" {
  description = "RDS instance class"
  type        = string
  default     = "db.t4g.medium"
}

variable "allocated_storage" {
  description = "Allocated storage in GB"
  type        = number
  default     = 20
}

variable "max_allocated_storage" {
  description = "Maximum storage limit for autoscaling in GB"
  type        = number
  default     = 100
}

variable "multi_az" {
  description = "Specifies if the RDS instance is multi-AZ"
  type        = bool
  default     = true
}

variable "database_name" {
  description = "Default database name"
  type        = string
  default     = "ledger"
}

variable "admin_username" {
  description = "Username for the database administrator"
  type        = string
  default     = "ledger_admin"
}

variable "admin_password" {
  description = "Password for database administrator (leave empty to generate random)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "skip_final_snapshot" {
  description = "Whether to skip final snapshot when destroying DB"
  type        = bool
  default     = true
}

variable "deletion_protection" {
  description = "Enable deletion protection on RDS instance"
  type        = bool
  default     = false
}

variable "tags" {
  description = "Tags to apply to resources"
  type        = map(string)
  default     = {}
}
