variable "resource_group_name" {
  description = "Name of the resource group"
  type        = string
}

variable "location" {
  description = "Azure region for Event Hubs namespace"
  type        = string
}

variable "resource_prefix" {
  description = "Prefix for naming resources"
  type        = string
}

variable "sku" {
  description = "SKU tier for Event Hubs (Standard or Premium for Kafka support)"
  type        = string
  default     = "Standard"
}

variable "capacity" {
  description = "Throughput Units (TUs) for Event Hubs namespace"
  type        = number
  default     = 1
}

variable "auto_inflate_enabled" {
  description = "Enable auto-inflate for throughput units"
  type        = bool
  default     = true
}

variable "maximum_throughput_units" {
  description = "Maximum throughput units when auto-inflate is enabled"
  type        = number
  default     = 5
}

variable "event_hub_name" {
  description = "Name of the Event Hub (Kafka Topic equivalent)"
  type        = string
  default     = "tiny-ledger-events"
}

variable "partition_count" {
  description = "Partition count for the Event Hub"
  type        = number
  default     = 4
}

variable "message_retention" {
  description = "Message retention in days"
  type        = number
  default     = 7
}

variable "tags" {
  description = "Resource tags"
  type        = map(string)
  default     = {}
}
