# Infrastructure

Everything deploys from GitHub Actions using **OIDC role assumption** — no IAM
user access keys are created or stored anywhere, per project policy.

## Order of operations (once the AWS account exists)

1. Create the AWS account; enable MFA on the root user; create an admin via
   IAM Identity Center (SSO) for day-to-day console/CLI use.
2. Pick the region. It must support Rekognition Face Liveness:
   `us-east-1`, `us-west-2`, `eu-west-1`, `ap-northeast-1`, or `ap-south-1`
   (default here: `ap-south-1`).
3. Run the one-time bootstrap locally (human-run, the only non-CI step):

   ```sh
   cd infra/terraform/bootstrap
   terraform init
   terraform apply
   ```

4. In the GitHub repository, set **Settings → Secrets and variables → Actions →
   Variables**:
   - `AWS_DEPLOY_ROLE_ARN` — from the bootstrap output
   - `AWS_REGION` — the chosen region
   - `ECR_REPOSITORY`, `ECS_CLUSTER`, `ECS_SERVICE` — once those modules exist
5. From then on, pushes to `main` deploy via the workflows in `.github/workflows/`.

## Modules still to be written (Phase 1 deployment)

- `network/` — VPC, subnets, ALB
- `ecr/` + `ecs/` — backend container registry and Fargate service
- `rds/` — PostgreSQL
- `s3/` — image bucket with SSE-KMS and lifecycle (retention) rules
- `cognito/` — Identity Pool for the Face Liveness UI; User Pool for admin auth
- `amplify/` — hosting for admin-portal and liveness-ui (or connect the repo in
  the Amplify console)

Remote Terraform state (S3 + DynamoDB lock) should be added before the second
module exists; the bootstrap can stay on local state until then.
