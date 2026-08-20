terraform {
  required_version = ">= 1.5.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = ">= 3.100.0, < 5.0.0"
    }
  }
}

resource "azurerm_user_assigned_identity" "aks_identity" {
  name                = "${var.resource_prefix}-aks-identity"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_kubernetes_cluster" "aks" {
  name                = "${var.resource_prefix}-aks"
  location            = var.location
  resource_group_name = var.resource_group_name
  dns_prefix          = "${var.resource_prefix}-k8s"
  kubernetes_version  = var.kubernetes_version

  oidc_issuer_enabled       = true
  workload_identity_enabled = true

  default_node_pool {
    name                 = "system"
    node_count           = var.system_node_count
    vm_size              = var.system_vm_size
    vnet_subnet_id       = var.vnet_subnet_id
    os_disk_size_gb      = 50
    orchestrator_version = var.kubernetes_version

    node_labels = {
      "nodepool" = "system"
    }

    tags = var.tags
  }

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.aks_identity.id]
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_data_plane  = "cilium"
    network_policy      = "cilium"
    load_balancer_sku   = "standard"
  }

  key_vault_secrets_provider {
    secret_rotation_enabled  = true
    secret_rotation_interval = "2m"
  }

  tags = var.tags
}

resource "azurerm_kubernetes_cluster_node_pool" "user" {
  name                  = "user"
  kubernetes_cluster_id = azurerm_kubernetes_cluster.aks.id
  vm_size               = var.user_vm_size
  node_count            = var.user_node_count
  vnet_subnet_id        = var.vnet_subnet_id
  os_disk_size_gb       = 100
  orchestrator_version  = var.kubernetes_version

  auto_scaling_enabled = var.enable_auto_scaling
  min_count            = var.enable_auto_scaling ? var.min_count : null
  max_count            = var.enable_auto_scaling ? var.max_count : null

  node_labels = {
    "nodepool" = "workload"
    "role"     = "tiny-ledger"
  }

  tags = var.tags
}
