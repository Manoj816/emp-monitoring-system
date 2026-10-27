# EmpVerify — Project Conventions

## AWS: cost-efficiency (TEST environment)

This is a **test environment**. Always provision the **cheapest viable** AWS
configuration; scale up only when explicitly asked. Apply this whenever creating
or editing VPC, subnets, ALB, ECS, RDS, S3, Cognito, or IAM.

- **No NAT Gateway** — run Fargate tasks in public subnets with public IPs
  (security groups still restrict ingress). NAT is ~$33/mo and unneeded for test.
- **Fargate**: `FARGATE_SPOT`, single task (`desired_count = 1`), smallest size
  that runs the app.
- **RDS**: `db.t4g.micro` (free-tier eligible), single-AZ, minimal backup
  retention, `skip_final_snapshot = true`.
- **S3**: SSE-S3 (AES256), not a customer-managed KMS key.
- **CloudWatch logs**: short retention (7 days).
- **Cognito**: free tier is sufficient.
- Keep a clear scale-up path (variables/comments) and note what to harden for
  production: customer KMS key, HTTPS (ACM + 443), Multi-AZ, longer backups,
  NAT Gateway or VPC endpoints, autoscaling.

## Git

- Commits in this repo are authored as **Manoj Kumar** (repo-local git config),
  never Divya.

## Local development

- Ports: PostgreSQL `5433`, backend `8082`, liveness-ui `5174`.
- Toolchains are user-local (not on PATH); see `docs/local-dev-runbook.md`.
