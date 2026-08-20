variable "environment" {
  description = "Environment name"
  type        = string
  default     = "prod"
}

variable "vpc_id" {
  description = "VPC ID where Redis is deployed"
  type        = string
}

variable "subnet_ids" {
  description = "Subnet IDs for ElastiCache Subnet Group"
  type        = list(string)
}

variable "eks_node_security_group_id" {
  description = "Security Group ID of the EKS worker nodes"
  type        = string
  default     = ""
}

variable "allowed_security_group_ids" {
  description = "Additional Security Group IDs allowed to connect to Redis"
  type        = list(string)
  default     = []
}

variable "node_type" {
  description = "ElastiCache node type"
  type        = string
  default     = "cache.t4g.medium"
}

variable "engine_version" {
  description = "Redis engine version"
  type        = string
  default     = "7.1"
}

variable "parameter_group_name" {
  description = "Redis parameter group name"
  type        = string
  default     = "default.redis7"
}

variable "num_cache_clusters" {
  description = "Number of cache clusters (nodes) in the replication group"
  type        = number
  default     = 2
}

variable "automatic_failover_enabled" {
  description = "Enable automatic failover across AZs"
  type        = bool
  default     = true
}

variable "multi_az_enabled" {
  description = "Enable Multi-AZ"
  type        = bool
  default     = true
}

variable "auth_token" {
  description = "Redis AUTH password (leave empty to generate random)"
  type        = string
  default     = ""
  sensitive   = true
}

variable "tags" {
  description = "Tags to apply to resources"
  type        = map(string)
  default     = {}
}
