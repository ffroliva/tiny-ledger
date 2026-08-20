terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0.0"
    }
  }
}

locals {
  secret_name = "${var.environment}/${var.secret_name_prefix}/credentials"
  oidc_issuer = replace(var.oidc_issuer_url, "https://", "")
}

resource "aws_secretsmanager_secret" "ledger" {
  name                    = local.secret_name
  description             = "Secrets and credentials for tiny-ledger in ${var.environment}"
  recovery_window_in_days = var.recovery_window_in_days

  tags = merge(
    var.tags,
    {
      Name = local.secret_name
    }
  )
}

resource "aws_secretsmanager_secret_version" "ledger" {
  secret_id     = aws_secretsmanager_secret.ledger.id
  secret_string = jsonencode(var.secrets)
}

resource "aws_iam_policy" "secrets_reader" {
  name        = "${var.environment}-tiny-ledger-secrets-reader"
  description = "IAM policy to read tiny-ledger secrets from AWS Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret"
        ]
        Resource = aws_secretsmanager_secret.ledger.arn
      }
    ]
  })

  tags = var.tags
}

# Optional IRSA role for EKS ServiceAccount
resource "aws_iam_role" "irsa" {
  count = var.oidc_provider_arn != "" ? 1 : 0
  name  = "${var.environment}-tiny-ledger-irsa-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Federated = var.oidc_provider_arn
        }
        Action = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = {
            "${local.oidc_issuer}:sub" = "system:serviceaccount:${var.service_account_namespace}:${var.service_account_name}"
            "${local.oidc_issuer}:aud" = "sts.amazonaws.com"
          }
        }
      }
    ]
  })

  tags = var.tags
}

resource "aws_iam_role_policy_attachment" "irsa_secrets" {
  count      = var.oidc_provider_arn != "" ? 1 : 0
  role       = aws_iam_role.irsa[0].name
  policy_arn = aws_iam_policy.secrets_reader.arn
}
