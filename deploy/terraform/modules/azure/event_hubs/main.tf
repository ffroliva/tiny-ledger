terraform {
  required_version = ">= 1.5.0"
  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = ">= 3.100.0, < 5.0.0"
    }
  }
}

resource "azurerm_eventhub_namespace" "eventhub_ns" {
  name                     = "${var.resource_prefix}-evhns"
  location                 = var.location
  resource_group_name      = var.resource_group_name
  sku                      = var.sku
  capacity                 = var.capacity
  auto_inflate_enabled     = var.auto_inflate_enabled
  maximum_throughput_units = var.auto_inflate_enabled ? var.maximum_throughput_units : null
  minimum_tls_version      = "1.2"

  tags = var.tags
}

resource "azurerm_eventhub" "events_hub" {
  name                = var.event_hub_name
  namespace_name      = azurerm_eventhub_namespace.eventhub_ns.name
  resource_group_name = var.resource_group_name
  partition_count     = var.partition_count
  message_retention   = var.message_retention
}

resource "azurerm_eventhub_authorization_rule" "app_auth_rule" {
  name                = "tiny-ledger-app-rule"
  namespace_name      = azurerm_eventhub_namespace.eventhub_ns.name
  eventhub_name       = azurerm_eventhub.events_hub.name
  resource_group_name = var.resource_group_name
  listen              = true
  send                = true
  manage              = false
}
