output "alb_dns_name" {
  description = "Public backend URL (set as the agent/portal API base, and front with HTTPS)."
  value       = aws_lb.main.dns_name
}

output "ecr_repository_url" {
  description = "Set as the ECR_REPOSITORY CI variable (push images here)."
  value       = aws_ecr_repository.backend.repository_url
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.main.name
}

output "ecs_service_name" {
  value = aws_ecs_service.backend.name
}

output "s3_bucket" {
  value = aws_s3_bucket.images.bucket
}

output "db_endpoint" {
  value = aws_db_instance.main.address
}

output "cognito_user_pool_id" {
  value = aws_cognito_user_pool.admin.id
}

output "cognito_admin_client_id" {
  value = aws_cognito_user_pool_client.admin_portal.id
}

output "cognito_identity_pool_id" {
  description = "Set as the liveness UI's Cognito Identity Pool ID."
  value       = aws_cognito_identity_pool.liveness.id
}
