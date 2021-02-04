terraform {
  required_version = ">= 0.12"
}

provider "google" {
  version = "~> 2.19"
}

provider "kubernetes" {
  version = "~> 1.13.3"
  load_config_file = var.load_config_file
}
