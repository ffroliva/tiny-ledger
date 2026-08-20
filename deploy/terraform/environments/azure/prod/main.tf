resource "azurerm_resource_group" "rg" {
  name     = var.resource_group_name
  location = var.location
  tags     = var.tags
}

module "networking" {
  source              = "../../../modules/azure/networking"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  resource_prefix     = var.resource_prefix
  tags                = var.tags
}

module "aks" {
  source              = "../../../modules/azure/aks"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  resource_prefix     = var.resource_prefix
  kubernetes_version  = var.kubernetes_version
  vnet_subnet_id      = module.networking.aks_subnet_id
  system_node_count   = var.system_node_count
  system_vm_size      = var.system_vm_size
  user_node_count     = var.user_node_count
  user_vm_size        = var.user_vm_size
  enable_auto_scaling = var.enable_auto_scaling
  min_count           = var.min_count
  max_count           = var.max_count
  tags                = var.tags
}

module "postgres" {
  source              = "../../../modules/azure/postgres"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  resource_prefix     = var.resource_prefix
  vnet_id             = module.networking.vnet_id
  db_subnet_id        = module.networking.db_subnet_id
  sku_name            = var.postgres_sku_name
  storage_mb          = var.postgres_storage_mb
  tags                = var.tags
}

module "redis" {
  source              = "../../../modules/azure/redis"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  resource_prefix     = var.resource_prefix
  sku_name            = var.redis_sku_name
  family              = var.redis_family
  capacity            = var.redis_capacity
  tags                = var.tags
}

module "event_hubs" {
  source              = "../../../modules/azure/event_hubs"
  resource_group_name = azurerm_resource_group.rg.name
  location            = azurerm_resource_group.rg.location
  resource_prefix     = var.resource_prefix
  sku                 = var.event_hub_sku
  capacity            = var.event_hub_capacity
  tags                = var.tags
}

module "keyvault" {
  source                     = "../../../modules/azure/keyvault"
  resource_group_name        = azurerm_resource_group.rg.name
  location                   = azurerm_resource_group.rg.location
  resource_prefix            = var.resource_prefix
  postgres_password          = module.postgres.administrator_password
  postgres_jdbc_url          = module.postgres.jdbc_url
  redis_primary_key          = module.redis.primary_access_key
  eventhub_connection_string = module.event_hubs.authorization_rule_primary_connection_string
  oidc_issuer_url            = module.aks.oidc_issuer_url
  k8s_namespace              = var.k8s_namespace
  k8s_service_account_name   = var.k8s_service_account_name
  reader_object_ids = [
    module.aks.kubelet_identity_object_id
  ]
  tags = var.tags
}
