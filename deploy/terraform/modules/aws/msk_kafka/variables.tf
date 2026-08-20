variable "environment" {
  description = "Environment name"
  type        = string
  default     = "prod"
}

variable "vpc_id" {
  description = "VPC ID where MSK is deployed"
  type        = string
}

variable "subnet_ids" {
  description = "Subnet IDs for MSK broker nodes (at least 2 subnets across different AZs)"
  type        = list(string)
}

variable "eks_node_security_group_id" {
  description = "Security Group ID of the EKS worker nodes"
  type        = string
  default     = ""
}

variable "allowed_security_group_ids" {
  description = "Additional Security Group IDs allowed to connect to MSK"
  type        = list(string)
  default     = []
}

variable "kafka_version" {
  description = "Kafka engine version"
  type        = string
  default     = "3.6.0"
}

variable "number_of_broker_nodes" {
  description = "Number of broker nodes in the MSK cluster (multiple of AZs)"
  type        = number
  default     = 3
}

variable "instance_type" {
  description = "MSK broker instance type"
  type        = string
  default     = "kafka.m5.large"
}

variable "volume_size" {
  description = "EBS storage size per broker in GB"
  type        = number
  default     = 100
}

variable "allow_unauthenticated" {
  description = "Allow unauthenticated plaintext access (e.g. for development/localstack)"
  type        = bool
  default     = false
}

variable "tags" {
  description = "Tags to apply to resources"
  type        = map(string)
  default     = {}
}
