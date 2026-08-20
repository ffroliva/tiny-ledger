output "secret_id" {
  description = "The ID of the Secrets Manager secret"
  value       = aws_secretsmanager_secret.ledger.id
}

output "secret_arn" {
  description = "The ARN of the Secrets Manager secret"
  value       = aws_secretsmanager_secret.ledger.arn
}

output "secret_name" {
  description = "The name of the Secrets Manager secret"
  value       = aws_secretsmanager_secret.ledger.name
}

output "iam_policy_arn" {
  description = "The ARN of the IAM policy to read the secret"
  value       = aws_iam_policy.secrets_reader.arn
}

output "irsa_role_arn" {
  description = "The ARN of the IRSA IAM role (if created)"
  value       = length(aws_iam_role.irsa) > 0 ? aws_iam_role.irsa[0].arn : null
}
