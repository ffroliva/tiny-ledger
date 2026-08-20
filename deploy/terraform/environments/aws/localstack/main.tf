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

module "rds_postgres" {
  source = "../../../modules/aws/rds_postgres"

  environment                = var.environment
  vpc_id                     = module.networking.vpc_id
  subnet_ids                 = module.networking.private_subnet_ids
  eks_node_security_group_id = ""
  instance_class             = "db.t4g.micro"
  allocated_storage          = 20
  database_name              = var.postgres_database_name
  admin_username             = var.postgres_admin_username
  admin_password             = var.postgres_admin_password
  multi_az                   = false
  skip_final_snapshot        = true
  deletion_protection        = false
  tags                       = var.tags

  depends_on = [module.networking]
}

module "secrets" {
  source = "../../../modules/aws/secrets"

  environment               = var.environment
  secret_name_prefix        = "tiny-ledger"
  service_account_namespace = "tiny-ledger"
  service_account_name      = "tiny-ledger-sa"

  secrets = {
    SPRING_DATASOURCE_URL          = "jdbc:postgresql://${module.rds_postgres.endpoint}/${module.rds_postgres.database_name}"
    SPRING_DATASOURCE_USERNAME     = module.rds_postgres.admin_username
    SPRING_DATASOURCE_PASSWORD     = module.rds_postgres.admin_password
    SPRING_DATA_REDIS_HOST         = "localhost"
    SPRING_DATA_REDIS_PORT         = "6379"
    SPRING_KAFKA_BOOTSTRAP_SERVERS = "localhost:9092"
  }

  tags = var.tags

  depends_on = [module.rds_postgres]
}
