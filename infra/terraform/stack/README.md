# EmpVerify Infrastructure Stack

Terraform for the full AWS deployment. Pairs with the OIDC bootstrap in
`../bootstrap` (which creates the GitHub Actions deploy role — no IAM user keys).

## What it creates

| Area | Resources |
|---|---|
| Network | VPC, 2 public + 2 private subnets, IGW, NAT, route tables |
| Security | ALB / ECS / RDS security groups (least-privilege chain) |
| Compute | ECS Fargate cluster, task definition, service, ALB + target group |
| Database | RDS PostgreSQL (private, encrypted), subnet group |
| Storage | S3 image bucket (SSE-KMS, versioned, evidence lifecycle), KMS key |
| Registry | ECR repository (+ lifecycle), CloudWatch log group |
| Secrets | Secrets Manager: DB password, admin API key, device-token secret (generated) |
| Auth | Cognito user pool (admin portal) + identity pool (Face Liveness) |

IAM: the ECS **task role** grants the app its own Rekognition/S3/KMS permissions —
the app never uses static credentials.

## Deploy order (once the AWS account exists)

1. Pick the region (must support Face Liveness). Default `ap-south-1`.
2. Run `../bootstrap` once (creates the GitHub OIDC deploy role).
3. Apply this stack:
   ```sh
   cd infra/terraform/stack
   cp terraform.tfvars.example terraform.tfvars   # edit if needed
   terraform init
   terraform apply
   ```
   First apply uses a placeholder container image; the service goes healthy once a
   real backend image is pushed.
4. Push the backend image and roll the service — automatically via the
   `backend.yml` workflow once these repository variables are set from the
   outputs: `AWS_DEPLOY_ROLE_ARN`, `AWS_REGION`, `ECR_REPOSITORY`, `ECS_CLUSTER`,
   `ECS_SERVICE`.
5. Wire the frontends: set the liveness UI's Cognito Identity Pool ID
   (`cognito_identity_pool_id` output) and point the admin portal / agent at the
   backend URL (`alb_dns_name`, fronted with HTTPS).
6. Enrol the first admin user in the Cognito user pool.

## Notes

- **HTTPS**: the listener is HTTP:80 for bring-up. Add an ACM certificate and a
  443 listener (and the ALB 443 ingress, stubbed in `security.tf`) before real use —
  biometric traffic must be encrypted in transit.
- **Remote state**: `versions.tf` has a commented S3 backend. Enable it (bucket +
  DynamoDB lock) before a second person runs Terraform.
- **Cost**: NAT gateway, ALB, RDS, and Fargate are the always-on cost drivers
  (~$150–250/mo). Rekognition is usage-priced on top.
- Generated secrets live only in Secrets Manager and Terraform state — keep state
  in the encrypted S3 backend, not local disk, for any shared use.
