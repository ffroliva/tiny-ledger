variable "resource_group_name" {
  description = "Name of the resource group"
  type        = string
}

variable "location" {
  description = "Azure region for AKS cluster"
  type        = string
}

variable "resource_prefix" {
  description = "Prefix for naming resources"
  type        = string
}

variable "kubernetes_version" {
  description = "Kubernetes version for AKS"
  type        = string
  default     = "1.31"
}

variable "vnet_subnet_id" {
  description = "Subnet ID where AKS nodes will reside"
  type        = string
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
  description = "Number of user nodes"
  type        = number
  default     = 3
}

variable "user_vm_size" {
  description = "VM size for user workload nodes"
  type        = string
  default     = "Standard_D4s_v5"
}

variable "enable_auto_scaling" {
  description = "Enable autoscaling on user node pool"
  type        = bool
  default     = true
}

variable "min_count" {
  description = "Minimum node count for autoscaling"
  type        = number
  default     = 2
}

variable "max_count" {
  description = "Maximum node count for autoscaling"
  type        = number
  default     = 6
}

variable "tags" {
  description = "Resource tags"
  type        = map(string)
  default     = {}
}
