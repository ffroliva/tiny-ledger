terraform {
  required_version = ">= 1.5.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = ">= 3.100.0, < 5.0.0"
    }
  }
}

resource "azurerm_redis_cache" "redis" {
  name                = "${var.resource_prefix}-redis"
  location            = var.location
  resource_group_name = var.resource_group_name
  capacity            = var.capacity
  family              = var.family
  sku_name            = var.sku_name
  minimum_tls_version = "1.2"

  redis_configuration {
    maxmemory_reserved = 50
    maxmemory_delta    = 50
    maxmemory_policy   = "allkeys-lru"
  }

  tags = var.tags
}

# Allow traffic from AKS subnet range if firewall rules are used
resource "azurerm_redis_firewall_rule" "allow_aks" {
  name                = "AllowAKS"
  redis_cache_name    = azurerm_redis_cache.redis.name
  resource_group_name = var.resource_group_name
  start_ip            = var.aks_subnet_start_ip
  end_ip              = var.aks_subnet_end_ip
}
