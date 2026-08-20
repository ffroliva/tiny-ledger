variable "resource_group_name" {
  description = "Name of the resource group"
  type        = string
}

variable "location" {
  description = "Azure region for Key Vault"
  type        = string
}

variable "resource_prefix" {
  description = "Prefix for naming resources"
  type        = string
}

variable "sku_name" {
  description = "SKU for Key Vault (standard or premium)"
  type        = string
  default     = "standard"
}

variable "reader_object_ids" {
  description = "List of Azure AD Object IDs permitted to read secrets (e.g. AKS CSI secret provider identity)"
  type        = list(string)
  default     = []
}

variable "postgres_password" {
  description = "PostgreSQL administrator password to store in Key Vault"
  type        = string
  sensitive   = true
}

variable "postgres_jdbc_url" {
  description = "PostgreSQL JDBC connection URL to store in Key Vault"
  type        = string
}

variable "redis_primary_key" {
  description = "Redis primary access key to store in Key Vault"
  type        = string
  sensitive   = true
}

variable "eventhub_connection_string" {
  description = "Event Hub primary connection string to store in Key Vault"
  type        = string
  sensitive   = true
}

variable "oidc_issuer_url" {
  description = "OIDC Issuer URL of AKS for Workload Identity federation"
  type        = string
}

variable "k8s_namespace" {
  description = "Kubernetes namespace for the tiny-ledger service account"
  type        = string
  default     = "tiny-ledger"
}

variable "k8s_service_account_name" {
  description = "Kubernetes service account name for tiny-ledger"
  type        = string
  default     = "tiny-ledger-sa"
}

variable "tags" {
  description = "Resource tags"
  type        = map(string)
  default     = {}
}
