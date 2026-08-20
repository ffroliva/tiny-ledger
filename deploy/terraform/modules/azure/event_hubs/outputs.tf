output "namespace_id" {
  description = "ID of the Event Hubs namespace"
  value       = azurerm_eventhub_namespace.eventhub_ns.id
}

output "namespace_name" {
  description = "Name of the Event Hubs namespace"
  value       = azurerm_eventhub_namespace.eventhub_ns.name
}

output "event_hub_name" {
  description = "Name of the created Event Hub"
  value       = azurerm_eventhub.events_hub.name
}

output "kafka_bootstrap_servers" {
  description = "Kafka bootstrap endpoint (broker FQDN:9093)"
  value       = "${azurerm_eventhub_namespace.eventhub_ns.name}.servicebus.windows.net:9093"
}

output "authorization_rule_primary_connection_string" {
  description = "Primary connection string for application authorization rule"
  value       = azurerm_eventhub_authorization_rule.app_auth_rule.primary_connection_string
  sensitive   = true
}

output "authorization_rule_primary_key" {
  description = "Primary key for application authorization rule"
  value       = azurerm_eventhub_authorization_rule.app_auth_rule.primary_key
  sensitive   = true
}
