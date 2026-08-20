variable "environment" {
  description = "Environment name"
  type        = string
  default     = "prod"
}

variable "secret_name_prefix" {
  description = "Prefix for the Secrets Manager secret name"
  type        = string
  default     = "tiny-ledger"
}

variable "secrets" {
  description = "Key-value map of secrets to store"
  type        = map(string)
  default     = {}
  sensitive   = true
}

variable "recovery_window_in_days" {
  description = "Number of days AWS Secrets Manager waits before deleting the secret"
  type        = number
  default     = 0
}

variable "oidc_provider_arn" {
  description = "ARN of the EKS OIDC Provider for IRSA role creation (optional)"
  type        = string
  default     = ""
}

variable "oidc_issuer_url" {
  description = "Issuer URL of the EKS OIDC Provider (optional)"
  type        = string
  default     = ""
}

variable "service_account_namespace" {
  description = "Kubernetes namespace for the tiny-ledger ServiceAccount"
  type        = string
  default     = "tiny-ledger"
}

variable "service_account_name" {
  description = "Kubernetes ServiceAccount name for IRSA"
  type        = string
  default     = "tiny-ledger-sa"
}

variable "tags" {
  description = "Tags to apply to resources"
  type        = map(string)
  default     = {}
}
