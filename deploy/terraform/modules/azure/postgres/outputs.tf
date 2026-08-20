output "server_id" {
  description = "ID of the PostgreSQL Flexible Server"
  value       = azurerm_postgresql_flexible_server.postgres.id
}

output "server_fqdn" {
  description = "FQDN of the PostgreSQL Flexible Server"
  value       = azurerm_postgresql_flexible_server.postgres.fqdn
}

output "database_name" {
  description = "Name of the created database"
  value       = azurerm_postgresql_flexible_server_database.tiny_ledger.name
}

output "administrator_login" {
  description = "Administrator username for PostgreSQL"
  value       = azurerm_postgresql_flexible_server.postgres.administrator_login
}

output "administrator_password" {
  description = "Administrator password for PostgreSQL"
  value       = random_password.postgres_admin_password.result
  sensitive   = true
}

output "jdbc_url" {
  description = "JDBC connection URL for Spring Boot application"
  value       = "jdbc:postgresql://${azurerm_postgresql_flexible_server.postgres.fqdn}:5432/${azurerm_postgresql_flexible_server_database.tiny_ledger.name}?sslmode=require"
}
