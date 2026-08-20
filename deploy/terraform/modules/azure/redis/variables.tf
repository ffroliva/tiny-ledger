variable "resource_group_name" {
  description = "Name of the resource group"
  type        = string
}

variable "location" {
  description = "Azure region for Redis Cache"
  type        = string
}

variable "resource_prefix" {
  description = "Prefix for naming resources"
  type        = string
}

variable "capacity" {
  description = "Capacity of Redis cache instance (0-6 for Basic/Standard)"
  type        = number
  default     = 1
}

variable "family" {
  description = "SKU family for Redis Cache (C for Basic/Standard, P for Premium)"
  type        = string
  default     = "C"
}

variable "sku_name" {
  description = "SKU name for Redis Cache (Basic, Standard, Premium)"
  type        = string
  default     = "Standard"
}

variable "aks_subnet_start_ip" {
  description = "Start IP of AKS subnet range for Redis firewall"
  type        = string
  default     = "10.10.0.1"
}

variable "aks_subnet_end_ip" {
  description = "End IP of AKS subnet range for Redis firewall"
  type        = string
  default     = "10.10.15.254"
}

variable "tags" {
  description = "Resource tags"
  type        = map(string)
  default     = {}
}
