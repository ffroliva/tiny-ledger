output "vpc_id" {
  description = "VPC ID"
  value       = module.networking.vpc_id
}

output "eks_cluster_name" {
  description = "EKS Cluster Name"
  value       = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  description = "EKS Cluster Endpoint"
  value       = module.eks.cluster_endpoint
}

output "rds_postgres_endpoint" {
  description = "RDS PostgreSQL Endpoint"
  value       = module.rds_postgres.endpoint
}

output "elasticache_redis_endpoint" {
  description = "ElastiCache Redis Primary Endpoint"
  value       = module.elasticache_redis.primary_endpoint_address
}

output "msk_kafka_bootstrap_brokers_sasl_iam" {
  description = "MSK Kafka Bootstrap Brokers (SASL/IAM)"
  value       = module.msk_kafka.bootstrap_brokers_sasl_iam
}

output "secrets_manager_secret_arn" {
  description = "Secrets Manager Secret ARN"
  value       = module.secrets.secret_arn
}

output "irsa_role_arn" {
  description = "IRSA Role ARN for tiny-ledger ServiceAccount"
  value       = module.secrets.irsa_role_arn
}
