module "networking" {
  source = "../../../modules/aws/networking"

  vpc_cidr             = var.vpc_cidr
  availability_zones   = var.availability_zones
  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  environment          = var.environment
  cluster_name         = var.cluster_name
  tags                 = var.tags
}

module "eks" {
  source = "../../../modules/aws/eks"

  cluster_name                   = var.cluster_name
  kubernetes_version             = var.kubernetes_version
  vpc_id                         = module.networking.vpc_id
  subnet_ids                     = module.networking.private_subnet_ids
  node_instance_types            = var.node_instance_types
  node_desired_size              = var.node_desired_size
  node_min_size                  = var.node_min_size
  node_max_size                  = var.node_max_size
  cluster_endpoint_public_access = true
  environment                    = var.environment
  tags                           = var.tags

  depends_on = [module.networking]
}

module "rds_postgres" {
  source = "../../../modules/aws/rds_postgres"

  environment                = var.environment
  vpc_id                     = module.networking.vpc_id
  subnet_ids                 = module.networking.private_subnet_ids
  eks_node_security_group_id = module.eks.node_security_group_id
  instance_class             = var.postgres_instance_class
  allocated_storage          = var.postgres_allocated_storage
  database_name              = var.postgres_database_name
  admin_username             = var.postgres_admin_username
  admin_password             = var.postgres_admin_password
  multi_az                   = true
  skip_final_snapshot        = false
  deletion_protection        = true
  tags                       = var.tags

  depends_on = [module.networking, module.eks]
}

module "elasticache_redis" {
  source = "../../../modules/aws/elasticache_redis"

  environment                = var.environment
  vpc_id                     = module.networking.vpc_id
  subnet_ids                 = module.networking.private_subnet_ids
  eks_node_security_group_id = module.eks.node_security_group_id
  node_type                  = var.redis_node_type
  num_cache_clusters         = var.redis_num_cache_clusters
  auth_token                 = var.redis_auth_token
  multi_az_enabled           = true
  automatic_failover_enabled = true
  tags                       = var.tags

  depends_on = [module.networking, module.eks]
}

module "msk_kafka" {
  source = "../../../modules/aws/msk_kafka"

  environment                = var.environment
  vpc_id                     = module.networking.vpc_id
  subnet_ids                 = module.networking.private_subnet_ids
  eks_node_security_group_id = module.eks.node_security_group_id
  instance_type              = var.kafka_instance_type
  volume_size                = var.kafka_volume_size
  allow_unauthenticated      = false
  tags                       = var.tags

  depends_on = [module.networking, module.eks]
}

module "secrets" {
  source = "../../../modules/aws/secrets"

  environment               = var.environment
  secret_name_prefix        = "tiny-ledger"
  oidc_provider_arn         = module.eks.oidc_provider_arn
  oidc_issuer_url           = module.eks.oidc_issuer_url
  service_account_namespace = "tiny-ledger"
  service_account_name      = "tiny-ledger-sa"

  secrets = {
    SPRING_DATASOURCE_URL          = "jdbc:postgresql://${module.rds_postgres.endpoint}/${module.rds_postgres.database_name}"
    SPRING_DATASOURCE_USERNAME     = module.rds_postgres.admin_username
    SPRING_DATASOURCE_PASSWORD     = module.rds_postgres.admin_password
    SPRING_DATA_REDIS_HOST         = module.elasticache_redis.primary_endpoint_address
    SPRING_DATA_REDIS_PORT         = tostring(module.elasticache_redis.port)
    SPRING_DATA_REDIS_PASSWORD     = module.elasticache_redis.auth_token
    SPRING_KAFKA_BOOTSTRAP_SERVERS = module.msk_kafka.bootstrap_brokers_sasl_iam
  }

  tags = var.tags

  depends_on = [module.eks, module.rds_postgres, module.elasticache_redis, module.msk_kafka]
}
