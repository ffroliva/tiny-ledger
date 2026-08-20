# Validate Terraform configurations and test LocalStack compatibility (PowerShell)
#
# Usage:
#   .\scripts\k8s\test-localstack-tf.ps1 [-Plan]
#
param (
    [switch]$Plan
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path "$PSScriptRoot\..\.."
$TfDir = Join-Path $RepoRoot "deploy\terraform"

Write-Host "`n=== 1. Checking Terraform CLI ===" -ForegroundColor Cyan
if (-not (Get-Command terraform -ErrorAction SilentlyContinue)) {
    Write-Error "terraform is not installed or not on PATH."
    exit 1
}
terraform -version | Select-Object -First 1
Write-Host "Terraform CLI is available." -ForegroundColor Green

Write-Host "`n=== 2. Checking formatting across $TfDir ===" -ForegroundColor Cyan
terraform -chdir="$TfDir" fmt -check -recursive
if ($LASTEXITCODE -ne 0) {
    Write-Error "Terraform formatting check failed. Run: terraform -chdir=`"$TfDir`" fmt -recursive"
    exit 1
}
Write-Host "Terraform formatting check passed." -ForegroundColor Green

Write-Host "`n=== 3. Validating AWS Prod Environment ===" -ForegroundColor Cyan
$ProdDir = Join-Path $TfDir "environments\aws\prod"
Write-Host "Running 'terraform init -backend=false' in $ProdDir..."
terraform -chdir="$ProdDir" init -backend=false
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "Running 'terraform validate' in $ProdDir..."
terraform -chdir="$ProdDir" validate
if ($LASTEXITCODE -ne 0) { exit 1 }
Write-Host "AWS Prod configuration is valid." -ForegroundColor Green

Write-Host "`n=== 4. Validating AWS LocalStack Environment ===" -ForegroundColor Cyan
$LocalDir = Join-Path $TfDir "environments\aws\localstack"
Write-Host "Running 'terraform init -backend=false' in $LocalDir..."
terraform -chdir="$LocalDir" init -backend=false
if ($LASTEXITCODE -ne 0) { exit 1 }

Write-Host "Running 'terraform validate' in $LocalDir..."
terraform -chdir="$LocalDir" validate
if ($LASTEXITCODE -ne 0) { exit 1 }
Write-Host "AWS LocalStack configuration is valid." -ForegroundColor Green

if ($Plan) {
    Write-Host "`n=== 5. Running Terraform Plan against LocalStack ===" -ForegroundColor Cyan
    $env:AWS_ACCESS_KEY_ID = "test"
    $env:AWS_SECRET_ACCESS_KEY = "test"
    $env:AWS_DEFAULT_REGION = "us-east-1"

    Write-Host "Running 'terraform plan' in $LocalDir..."
    terraform -chdir="$LocalDir" plan
    if ($LASTEXITCODE -ne 0) { exit 1 }
    Write-Host "Terraform plan succeeded against LocalStack endpoint." -ForegroundColor Green
}

Write-Host "`nAll Terraform validations completed successfully!" -ForegroundColor Green
