output "vpc_id" {
  description = "VPC ID created in LocalStack"
  value       = module.networking.vpc_id
}

output "public_subnet_ids" {
  description = "Public Subnet IDs in LocalStack"
  value       = module.networking.public_subnet_ids
}

output "private_subnet_ids" {
  description = "Private Subnet IDs in LocalStack"
  value       = module.networking.private_subnet_ids
}

output "rds_endpoint" {
  description = "RDS Postgres endpoint in LocalStack"
  value       = module.rds_postgres.endpoint
}

output "secrets_arn" {
  description = "Secrets Manager Secret ARN in LocalStack"
  value       = module.secrets.secret_arn
}
