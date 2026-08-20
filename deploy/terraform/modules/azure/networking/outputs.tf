output "vnet_id" {
  description = "ID of the Virtual Network"
  value       = azurerm_virtual_network.vnet.id
}

output "vnet_name" {
  description = "Name of the Virtual Network"
  value       = azurerm_virtual_network.vnet.name
}

output "aks_subnet_id" {
  description = "ID of the AKS subnet"
  value       = azurerm_subnet.aks.id
}

output "db_subnet_id" {
  description = "ID of the PostgreSQL delegated subnet"
  value       = azurerm_subnet.db.id
}

output "redis_subnet_id" {
  description = "ID of the Redis subnet"
  value       = azurerm_subnet.redis.id
}

output "ingress_subnet_id" {
  description = "ID of the Ingress subnet"
  value       = azurerm_subnet.ingress.id
}
