variable "resource_group_name" {
  description = "Name of the resource group for Tiny Ledger"
  type        = string
  default     = "rg-tiny-ledger-prod"
}

variable "location" {
  description = "Azure Region"
  type        = string
  default     = "eastus"
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "prod"
}

variable "resource_prefix" {
  description = "Prefix for all resources"
  type        = string
  default     = "tlprod"
}

variable "kubernetes_version" {
  description = "Kubernetes version for AKS"
  type        = string
  default     = "1.31"
}

variable "system_node_count" {
  description = "Number of system nodes"
  type        = number
  default     = 2
}

variable "system_vm_size" {
  description = "VM size for system nodes"
  type        = string
  default     = "Standard_D2s_v5"
}

variable "user_node_count" {
  description = "Number of workload user nodes"
  type        = number
  default     = 3
}

variable "user_vm_size" {
  description = "VM size for workload user nodes"
  type        = string
  default     = "Standard_D4s_v5"
}

variable "enable_auto_scaling" {
  description = "Enable AKS autoscaling"
  type        = bool
  default     = true
}

variable "min_count" {
  description = "Minimum node count for autoscaler"
  type        = number
  default     = 2
}

variable "max_count" {
  description = "Maximum node count for autoscaler"
  type        = number
  default     = 6
}

variable "postgres_sku_name" {
  description = "SKU for PostgreSQL Flexible Server"
  type        = string
  default     = "GP_Standard_D2ds_v5"
}

variable "postgres_storage_mb" {
  description = "Storage size in MB for PostgreSQL"
  type        = number
  default     = 32768
}

variable "redis_sku_name" {
  description = "SKU name for Redis Cache (Basic, Standard, Premium)"
  type        = string
  default     = "Standard"
}

variable "redis_family" {
  description = "SKU family for Redis Cache"
  type        = string
  default     = "C"
}

variable "redis_capacity" {
  description = "Capacity for Redis Cache"
  type        = number
  default     = 1
}

variable "event_hub_sku" {
  description = "SKU for Event Hubs namespace"
  type        = string
  default     = "Standard"
}

variable "event_hub_capacity" {
  description = "Capacity (TUs) for Event Hubs"
  type        = number
  default     = 1
}

variable "k8s_namespace" {
  description = "Kubernetes namespace for Tiny Ledger deployment"
  type        = string
  default     = "tiny-ledger"
}

variable "k8s_service_account_name" {
  description = "Kubernetes Service Account name"
  type        = string
  default     = "tiny-ledger-sa"
}

variable "tags" {
  description = "Resource tags"
  type        = map(string)
  default = {
    Environment = "prod"
    Project     = "tiny-ledger"
    ManagedBy   = "terraform"
  }
}
