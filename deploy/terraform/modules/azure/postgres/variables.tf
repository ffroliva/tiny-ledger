variable "resource_group_name" {
  description = "Name of the resource group"
  type        = string
}

variable "location" {
  description = "Azure region for PostgreSQL Server"
  type        = string
}

variable "resource_prefix" {
  description = "Prefix for naming resources"
  type        = string
}

variable "vnet_id" {
  description = "Virtual Network ID for Private DNS link"
  type        = string
}

variable "db_subnet_id" {
  description = "Delegated subnet ID for PostgreSQL Flexible Server"
  type        = string
}

variable "administrator_login" {
  description = "PostgreSQL administrator username"
  type        = string
  default     = "ledgeradmin"
}

variable "database_name" {
  description = "Name of the initial application database"
  type        = string
  default     = "tiny_ledger"
}

variable "sku_name" {
  description = "SKU for PostgreSQL Flexible Server"
  type        = string
  default     = "GP_Standard_D2ds_v5"
}

variable "storage_mb" {
  description = "Storage size in MB"
  type        = number
  default     = 32768
}

variable "backup_retention_days" {
  description = "Backup retention days"
  type        = number
  default     = 7
}

variable "geo_redundant_backup_enabled" {
  description = "Enable geo-redundant backups"
  type        = bool
  default     = false
}

variable "tags" {
  description = "Resource tags"
  type        = map(string)
  default     = {}
}
