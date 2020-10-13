terraform {
  backend "gcs" {
    bucket = "entur-system-tf-backend-ror"
    prefix = "gcp/ror/bigdaddy_v2"
  }
}
