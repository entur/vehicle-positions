# Startup snapshot cache for the parsed NeTEx and NSR exports, keyed by export ETag.
# See docs/superpowers/specs/2026-09-02-planned-data-snapshot-design.md.
#
# Every object here is disposable: a miss, a corrupt snapshot or an unreachable
# bucket makes the pod parse the export as before. Nothing is stored that cannot
# be rebuilt from the exports, so no versioning and no offsite backup.
#
# https://github.com/entur/terraform-google-cloud-storage/tree/master/modules/bucket#inputs
module "snapshots" {
  source                      = "github.com/entur/terraform-google-cloud-storage//modules/bucket?ref=v0.2.4"
  init                        = module.init
  name_override               = "vpos-snapshots"
  versioning                  = false
  force_destroy               = true
  disable_offsite_backup      = true
  create_kubernetes_resources = false

  # The snapshot object name carries the export's ETag, so a new export writes a
  # new object and the old one is never read again. Delete anything a week old:
  # a pod that has been up that long is already past its startup.
  lifecycle_rules_override = {
    expire_snapshots = {
      action = {
        type = "Delete"
      }
      condition = {
        age = "7"
      }
    }
  }
}

# The application service account behind the "application" Kubernetes service
# account reads, writes and deletes snapshot objects. objectAdmin rather than
# the narrower objectUser: only roles on the platform's assignable-roles
# allowlist (entur/ai guides/platform/iam-roles.md) pass the policy guard.
resource "google_storage_bucket_iam_member" "snapshot_writer" {
  bucket = module.snapshots.cloud_storage_bucket.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${module.init.service_accounts.default.email}"
}

output "snapshot_bucket" {
  description = "Name of the startup snapshot bucket. Set helm configMap.snapshotUri to gs://<name>/snapshots to enable the cache."
  value       = module.snapshots.cloud_storage_bucket.name
}
