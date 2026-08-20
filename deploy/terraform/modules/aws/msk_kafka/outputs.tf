output "cluster_arn" {
  description = "The ARN of the MSK Cluster"
  value       = aws_msk_cluster.this.arn
}

output "cluster_name" {
  description = "The name of the MSK Cluster"
  value       = aws_msk_cluster.this.cluster_name
}

output "bootstrap_brokers_tls" {
  description = "TLS connection host:port pairs"
  value       = aws_msk_cluster.this.bootstrap_brokers_tls
}

output "bootstrap_brokers_sasl_iam" {
  description = "SASL/IAM connection host:port pairs"
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_iam
}

output "zookeeper_connect_string" {
  description = "Zookeeper connect string"
  value       = aws_msk_cluster.this.zookeeper_connect_string
}

output "security_group_id" {
  description = "Security Group ID of the MSK cluster"
  value       = aws_security_group.kafka.id
}
