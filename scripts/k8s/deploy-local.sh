#!/usr/bin/env bash
# ==============================================================================
# deploy-local.sh — Deploy tiny-ledger stack into local Kind cluster
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
NAMESPACE="tiny-ledger"
OVERLAY_DIR="${REPO_ROOT}/deploy/k8s/overlays/local-kind"

echo "=== Deploying tiny-ledger to local Kind cluster ==="

echo "1. Ensuring namespace '${NAMESPACE}' exists..."
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

echo "2. Applying manifests from ${OVERLAY_DIR}..."
kubectl apply -k "${OVERLAY_DIR}"

echo "3. Waiting for backing services to become ready..."
echo "Waiting for postgres..."
kubectl rollout status deployment/postgres -n "${NAMESPACE}" --timeout=120s || true
echo "Waiting for redis..."
kubectl rollout status deployment/redis -n "${NAMESPACE}" --timeout=60s || true
echo "Waiting for kafka..."
kubectl rollout status deployment/kafka -n "${NAMESPACE}" --timeout=120s || true
echo "Waiting for keycloak..."
kubectl rollout status deployment/keycloak -n "${NAMESPACE}" --timeout=180s || true

echo "4. Waiting for tiny-ledger application deployment to roll out..."
kubectl rollout status deployment/tiny-ledger -n "${NAMESPACE}" --timeout=180s

echo "=== Deployment complete ==="
kubectl get pods,svc,ingress -n "${NAMESPACE}"
