output "resource_group_name" {
  description = "Resource Group Name"
  value       = azurerm_resource_group.rg.name
}

output "aks_cluster_name" {
  description = "AKS Cluster Name"
  value       = module.aks.cluster_name
}

output "aks_oidc_issuer_url" {
  description = "AKS OIDC Issuer URL"
  value       = module.aks.oidc_issuer_url
}

output "postgres_fqdn" {
  description = "PostgreSQL Flexible Server FQDN"
  value       = module.postgres.server_fqdn
}

output "redis_hostname" {
  description = "Redis Cache Hostname"
  value       = module.redis.hostname
}

output "eventhub_namespace" {
  description = "Event Hubs Namespace Name"
  value       = module.event_hubs.namespace_name
}

output "key_vault_uri" {
  description = "Key Vault URI"
  value       = module.keyvault.key_vault_uri
}

output "workload_identity_client_id" {
  description = "Client ID for AKS Workload Identity"
  value       = module.keyvault.workload_identity_client_id
}
