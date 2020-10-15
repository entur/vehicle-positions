variable "gcp_project" {
  description = "The GCP project id"
}
variable "pubsub_project" {
  description = "The GCP pubsub project id"
}

variable "kube_namespace" {
  description = "The Kubernetes namespace"
}

variable "load_config_file" {
  description = "Do not load kube config file"
  default     = false
}

variable "labels" {
  description = "Labels used in all resources"
  type        = map(string)
  default = {
    manager = "terraform"
    team    = "ror"
    slack   = "talk-ror"
    app     = "vehicle-positions"
  }
}

variable "sa_pubsub_role" {
  description = "IAM role for pubsub subscription"
  default = "roles/pubsub.editor"
}
