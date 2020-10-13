resource "google_service_account" "service_account" {
  account_id   = "${var.labels.team}-${var.labels.app}-sa"
  display_name = "${var.labels.team}-${var.labels.app} service account"
  project = var.gcp_project
}

resource "google_service_account_key" "service_account_key" {
  service_account_id = google_service_account.service_account.name
}

resource "kubernetes_secret" "service_account_credentials" {
  metadata {
    name      = "${var.labels.team}-${var.labels.app}-sa-key"
    namespace = var.kube_namespace
  }
  data = {
    "credentials.json" = base64decode(google_service_account_key.service_account_key.private_key)
  }
}