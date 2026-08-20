#!/usr/bin/env bash
# Validate Terraform configurations and test LocalStack compatibility.
#
# Usage:
#   ./scripts/k8s/test-localstack-tf.sh [--plan]
#
set -euo pipefail

REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
TF_DIR="$REPO/deploy/terraform"
DO_PLAN=0

if [ -t 1 ]; then C='\033[36m'; Y='\033[33m'; G='\033[32m'; R='\033[31m'; D='\033[90m'; N='\033[0m'
else C=''; Y=''; G=''; R=''; D=''; N=''; fi
say()  { printf "${C}%s${N}\n" "$*"; }
warn() { printf "${Y}%s${N}\n" "$*"; }
ok()   { printf "${G}%s${N}\n" "$*"; }
die()  { printf "${R}%s${N}\n" "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --plan) DO_PLAN=1; shift ;;
    *) die "Unknown argument: $1. Usage: $0 [--plan]" ;;
  esac
done

say ""
say "=== 1. Checking Terraform CLI ==="
if ! command -v terraform >/dev/null 2>&1; then
  die "terraform is not installed or not on PATH."
fi
terraform -version | head -n 1
ok "Terraform CLI is available."

say ""
say "=== 2. Checking formatting across $TF_DIR ==="
if terraform -chdir="$TF_DIR" fmt -check -recursive; then
  ok "Terraform formatting check passed."
else
  die "Terraform formatting check failed. Run: terraform -chdir=\"$TF_DIR\" fmt -recursive"
fi

say ""
say "=== 3. Validating AWS Prod Environment ==="
PROD_DIR="$TF_DIR/environments/aws/prod"
say "Running 'terraform init -backend=false' in $PROD_DIR..."
terraform -chdir="$PROD_DIR" init -backend=false
say "Running 'terraform validate' in $PROD_DIR..."
terraform -chdir="$PROD_DIR" validate
ok "AWS Prod configuration is valid."

say ""
say "=== 4. Validating AWS LocalStack Environment ==="
LOCAL_DIR="$TF_DIR/environments/aws/localstack"
say "Running 'terraform init -backend=false' in $LOCAL_DIR..."
terraform -chdir="$LOCAL_DIR" init -backend=false
say "Running 'terraform validate' in $LOCAL_DIR..."
terraform -chdir="$LOCAL_DIR" validate
ok "AWS LocalStack configuration is valid."

if [ "$DO_PLAN" -eq 1 ]; then
  say ""
  say "=== 5. Running Terraform Plan against LocalStack ==="
  export AWS_ACCESS_KEY_ID="test"
  export AWS_SECRET_ACCESS_KEY="test"
  export AWS_DEFAULT_REGION="us-east-1"

  say "Running 'terraform plan' in $LOCAL_DIR..."
  terraform -chdir="$LOCAL_DIR" plan
  ok "Terraform plan succeeded against LocalStack endpoint."
fi

say ""
ok "All Terraform validations completed successfully!"
