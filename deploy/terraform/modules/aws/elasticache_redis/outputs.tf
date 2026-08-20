output "primary_endpoint_address" {
  description = "The address of the endpoint for the primary node in the replication group"
  value       = aws_elasticache_replication_group.this.primary_endpoint_address
}

output "reader_endpoint_address" {
  description = "The address of the endpoint for the reader node in the replication group"
  value       = aws_elasticache_replication_group.this.reader_endpoint_address
}

output "port" {
  description = "The port number on which the cache accepts connections"
  value       = aws_elasticache_replication_group.this.port
}

output "auth_token" {
  description = "The Redis AUTH token"
  value       = var.auth_token != "" ? var.auth_token : random_password.auth_token.result
  sensitive   = true
}

output "security_group_id" {
  description = "Security Group ID of the Redis cluster"
  value       = aws_security_group.redis.id
}

output "replication_group_id" {
  description = "The ID of the ElastiCache Replication Group"
  value       = aws_elasticache_replication_group.this.id
}

output "arn" {
  description = "The ARN of the ElastiCache Replication Group"
  value       = aws_elasticache_replication_group.this.arn
}
