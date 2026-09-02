terraform {
  required_version = "~> 1.9"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.0"
    }
    # Required by the cloud-storage module even though we create no Kubernetes
    # resources from Terraform (see create_kubernetes_resources in snapshots.tf).
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "2.38.0"
    }
  }
}
