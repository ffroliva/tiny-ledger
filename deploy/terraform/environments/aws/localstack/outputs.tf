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

output "db_security_group_id" {
  description = "Mock DB Security Group ID in LocalStack"
  value       = aws_security_group.db_mock.id
}

output "secrets_arn" {
  description = "Secrets Manager Secret ARN in LocalStack"
  value       = module.secrets.secret_arn
}

