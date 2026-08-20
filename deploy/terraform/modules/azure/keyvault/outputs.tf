output "key_vault_id" {
  description = "ID of the Key Vault"
  value       = azurerm_key_vault.kv.id
}

output "key_vault_uri" {
  description = "URI of the Key Vault"
  value       = azurerm_key_vault.kv.vault_uri
}

output "key_vault_name" {
  description = "Name of the Key Vault"
  value       = azurerm_key_vault.kv.name
}

output "workload_identity_client_id" {
  description = "Client ID of the User Assigned Identity for AKS Workload Identity"
  value       = azurerm_user_assigned_identity.workload_identity.client_id
}

output "workload_identity_id" {
  description = "Resource ID of the User Assigned Identity for AKS Workload Identity"
  value       = azurerm_user_assigned_identity.workload_identity.id
}

output "workload_identity_principal_id" {
  description = "Principal ID of the User Assigned Identity for AKS Workload Identity"
  value       = azurerm_user_assigned_identity.workload_identity.principal_id
}
