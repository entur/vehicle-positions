terraform {
  required_version = ">= 0.12"
}

provider "google" {
  version = "~> 2.19"
}

provider "kubernetes" {
  load_config_file = var.load_config_file
}

# create service account
resource "google_service_account" "app_service_account" {
  account_id   = "${var.labels.team}-${var.labels.app}-sa"
  display_name = "${var.labels.team}-${var.labels.app} service account"
  project = var.gcp_project
}

# add service account as member to the pubsub
resource "google_project_iam_member" "project" {
  project = var.pubsub_project
  role    = var.sa_pubsub_role
  member = "serviceAccount:${google_service_account.app_service_account.email}"
}