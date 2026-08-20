output "redis_id" {
  description = "ID of the Redis Cache"
  value       = azurerm_redis_cache.redis.id
}

output "hostname" {
  description = "Hostname of the Redis Cache"
  value       = azurerm_redis_cache.redis.hostname
}

output "ssl_port" {
  description = "SSL port of the Redis Cache"
  value       = azurerm_redis_cache.redis.ssl_port
}

output "primary_access_key" {
  description = "Primary access key for Redis Cache"
  value       = azurerm_redis_cache.redis.primary_access_key
  sensitive   = true
}

output "primary_connection_string" {
  description = "Primary connection string for Redis Cache"
  value       = azurerm_redis_cache.redis.primary_connection_string
  sensitive   = true
}
