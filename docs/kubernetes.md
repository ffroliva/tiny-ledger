# Kubernetes & Multi-Cloud Deployment Guide

This document describes how `tiny-ledger` packages and runs on **Kubernetes** across local development (`kind`), **AWS EKS**, and **Azure AKS**, using cloud-agnostic base manifests and environment overlays.

---

## 1. Directory Structure

```text
deploy/
├── k8s/
│   ├── base/                    # Cloud-agnostic base manifests (Deployment, Svc, HPA, NetworkPolicy, etc.)
│   └── overlays/
│       ├── local-kind/          # Kind overlay with local backing services & ingress
│       ├── aws-eks/             # AWS EKS overlay with IRSA ServiceAccount & ALB Ingress
│       └── azure-aks/           # Azure AKS overlay with Workload Identity & App Gateway Ingress
└── terraform/
    ├── modules/
    │   ├── aws/                 # AWS modules (networking, eks, rds_postgres, elasticache_redis, msk_kafka, secrets)
    │   └── azure/               # Azure modules (networking, aks, postgres, redis, event_hubs, keyvault)
    └── environments/
        ├── aws/
        │   ├── localstack/      # LocalStack mock AWS environment for offline/CI verification
        │   └── prod/            # Production AWS environment
        └── azure/
            └── prod/            # Production Azure AKS environment
```

---

## 2. Local Kind Cluster Workflow

### Prerequisites
- `docker` (running)
- `kubectl`
- `kind`

### 1. Create Kind Cluster
```bash
./scripts/k8s/kind-cluster.sh up
```
This provisions a 3-node cluster (`1 control-plane`, `2 workers`) with host port bindings on `80` (HTTP) and `443` (HTTPS), and installs standard NGINX Ingress Controller.

### 2. Build & Load Container Image
```bash
# Build the Spring Boot container image locally
./mvnw spring-boot:build-image -DskipTests

# Load into Kind node runtime
./scripts/k8s/kind-cluster.sh load-image tiny-ledger:local
```

### 3. Deploy to Kind
```bash
./scripts/k8s/deploy-local.sh
```
This applies `deploy/k8s/overlays/local-kind/` into the `tiny-ledger` namespace and waits for pod rollout.

### 4. Verify Health Probes
```bash
kubectl -n tiny-ledger get pods
kubectl -n tiny-ledger get svc
curl -i http://localhost/actuator/health/readiness
```

### 5. Tear Down
```bash
./scripts/k8s/kind-cluster.sh down
```

---

## 3. LocalStack Offline IaC Validation

You can run Terraform lifecycle operations against **LocalStack** locally or in CI without cloud credentials:

```bash
./scripts/k8s/test-localstack-tf.sh
```

---

## 4. Production Cloud Deployments

### AWS EKS (`deploy/terraform/environments/aws/prod`)
1. Configure credentials: `aws configure`
2. Apply Terraform:
   ```bash
   cd deploy/terraform/environments/aws/prod
   cp terraform.tfvars.example terraform.tfvars
   terraform init
   terraform apply
   ```
3. Deploy K8s Workload:
   ```bash
   aws eks update-kubeconfig --name <cluster_name> --region <region>
   kubectl apply -k deploy/k8s/overlays/aws-eks
   ```

### Azure AKS (`deploy/terraform/environments/azure/prod`)
1. Configure credentials: `az login`
2. Apply Terraform:
   ```bash
   cd deploy/terraform/environments/azure/prod
   cp terraform.tfvars.example terraform.tfvars
   terraform init
   terraform apply
   ```
3. Deploy K8s Workload:
   ```bash
   az aks get-credentials --resource-group <rg_name> --name <cluster_name>
   kubectl apply -k deploy/k8s/overlays/azure-aks
   ```
