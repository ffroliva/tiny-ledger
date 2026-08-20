output "endpoint" {
  description = "The connection endpoint (host:port) of the RDS instance"
  value       = aws_db_instance.this.endpoint
}

output "address" {
  description = "The hostname of the RDS instance"
  value       = aws_db_instance.this.address
}

output "port" {
  description = "The database port"
  value       = aws_db_instance.this.port
}

output "database_name" {
  description = "The database name"
  value       = aws_db_instance.this.db_name
}

output "admin_username" {
  description = "The master username for the database"
  value       = aws_db_instance.this.username
}

output "admin_password" {
  description = "The master password for the database"
  value       = var.admin_password != "" ? var.admin_password : random_password.db_password.result
  sensitive   = true
}

output "security_group_id" {
  description = "Security Group ID of the RDS instance"
  value       = aws_security_group.db.id
}

output "db_instance_id" {
  description = "The RDS instance ID"
  value       = aws_db_instance.this.id
}

output "db_instance_arn" {
  description = "The ARN of the RDS instance"
  value       = aws_db_instance.this.arn
}
