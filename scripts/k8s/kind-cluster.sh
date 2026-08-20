#!/usr/bin/env bash
# ==============================================================================
# kind-cluster.sh — Manage multi-node Kind cluster for tiny-ledger local testing
# ==============================================================================
set -euo pipefail

CLUSTER_NAME="${KIND_CLUSTER_NAME:-tiny-ledger}"
KIND_CONFIG_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KIND_CONFIG="${KIND_CONFIG_DIR}/kind-cluster-config.yaml"

create_config() {
  cat <<EOF > "${KIND_CONFIG}"
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: ${CLUSTER_NAME}
nodes:
  - role: control-plane
    kubeadmConfigPatches:
      - |
        kind: InitConfiguration
        nodeRegistration:
          kubeletExtraArgs:
            node-labels: "ingress-ready=true"
    extraPortMappings:
      - containerPort: 80
        hostPort: 80
        protocol: TCP
      - containerPort: 443
        hostPort: 443
        protocol: TCP
  - role: worker
  - role: worker
EOF
}

cmd_up() {
  if kind get clusters | grep -qx "${CLUSTER_NAME}"; then
    echo "Kind cluster '${CLUSTER_NAME}' is already running."
  else
    echo "Creating multi-node Kind cluster '${CLUSTER_NAME}'..."
    create_config
    kind create cluster --config "${KIND_CONFIG}"
    echo "Installing ingress-nginx for Kind..."
    kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml
    echo "Waiting for ingress-nginx controller to be ready..."
    kubectl wait --namespace ingress-nginx \
      --for=condition=ready pod \
      --selector=app.kubernetes.io/component=controller \
      --timeout=120s || true
  fi
}

cmd_down() {
  echo "Deleting Kind cluster '${CLUSTER_NAME}'..."
  kind delete cluster --name "${CLUSTER_NAME}"
  if [[ -f "${KIND_CONFIG}" ]]; then
    rm -f "${KIND_CONFIG}"
  fi
}

cmd_load_image() {
  local image_name="${1:-}"
  if [[ -z "${image_name}" ]]; then
    echo "Error: Image name required. Usage: $0 load-image <image-name>" >&2
    exit 1
  fi
  echo "Loading image '${image_name}' into Kind cluster '${CLUSTER_NAME}'..."
  kind load docker-image "${image_name}" --name "${CLUSTER_NAME}"
}

cmd_status() {
  echo "=== Kind Clusters ==="
  kind get clusters
  echo ""
  echo "=== Cluster Nodes ==="
  kubectl get nodes -o wide || true
  echo ""
  echo "=== Pods across all namespaces ==="
  kubectl get pods -A || true
}

usage() {
  echo "Usage: $0 {up|down|load-image <image_name>|status}"
  exit 1
}

main() {
  local subcommand="${1:-}"
  case "${subcommand}" in
    up)
      cmd_up
      ;;
    down)
      cmd_down
      ;;
    load-image)
      shift
      cmd_load_image "$@"
      ;;
    status)
      cmd_status
      ;;
    *)
      usage
      ;;
  esac
}

main "$@"
