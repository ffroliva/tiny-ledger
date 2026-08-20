variable "resource_group_name" {
  description = "Name of the resource group"
  type        = string
}

variable "location" {
  description = "Azure region for network resources"
  type        = string
}

variable "resource_prefix" {
  description = "Prefix for naming resources"
  type        = string
}

variable "vnet_cidr" {
  description = "CIDR block for the Virtual Network"
  type        = string
  default     = "10.10.0.0/16"
}

variable "aks_subnet_cidr" {
  description = "CIDR block for AKS subnet"
  type        = string
  default     = "10.10.0.0/20"
}

variable "db_subnet_cidr" {
  description = "CIDR block for PostgreSQL Flexible Server delegated subnet"
  type        = string
  default     = "10.10.16.0/24"
}

variable "redis_subnet_cidr" {
  description = "CIDR block for Redis subnet"
  type        = string
  default     = "10.10.17.0/24"
}

variable "ingress_subnet_cidr" {
  description = "CIDR block for Ingress / App Gateway subnet"
  type        = string
  default     = "10.10.18.0/24"
}

variable "tags" {
  description = "Resource tags"
  type        = map(string)
  default     = {}
}
