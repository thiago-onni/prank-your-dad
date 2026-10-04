output "bucket_names" {
  value = local.is_s3 ? { for k, b in aws_s3_bucket.this : k => b.bucket } : { for k, b in minio_s3_bucket.this : k => b.bucket }
}

output "bucket_arns" {
  value = { for k, b in aws_s3_bucket.this : k => b.arn }
}

output "audit_archive_bucket" {
  value = local.is_s3 ? try(aws_s3_bucket.this["audit-archive"].bucket, null) : try(minio_s3_bucket.this["audit-archive"].bucket, null)
}

output "access_logs_bucket" {
  description = "Bucket de server access logs (somente S3)."
  value       = local.is_s3 ? aws_s3_bucket.access_logs[0].bucket : null
}
