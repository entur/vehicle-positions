# Terraform configuration for vehicle-positions (app id "vpos", projects ent-vpos-<env>).
module "init" {
  source      = "github.com/entur/terraform-google-init//modules/init?ref=v1.1.1"
  app_id      = "vpos"
  environment = var.environment
}
