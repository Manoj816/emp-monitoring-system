# EmpVerify — least-privilege access model (account 119331772192)

The long-lived access key used by the `empverify` CLI profile belongs to the IAM
user `empverify-deployer`. If that key leaks, the holder must not be able to do
anything beyond redeploying this project's backend image. Infrastructure
changes (Terraform) require a second factor.

| Principal | Policies | Can |
|---|---|---|
| user `empverify-deployer` (the CLI key) | `EmpVerifyDeploy` + `EmpVerifyGuardrails` | push images to `empverify-*` ECR repos, register task definitions, update the `empverify-cluster` service, read `/ecs/empverify-*` logs, manage admin-portal users in the Cognito pool, look up CloudTrail events, assume the infra role **only with MFA** |
| role `empverify-infra-admin` (assumed with MFA) | `EmpVerifyInfra` + `EmpVerifyGuardrails` | everything Terraform needs for this stack in us-east-1: VPC/ALB/ECS/ECR/RDS/S3/Cognito/KMS/logs, project secrets (`empverify/*`), project roles and policies (`empverify-*`) |
| role `empverify-github-deploy` (GitHub OIDC) | inline `empverify-deploy` | the CI deploy: ECR push, task-definition revision, service update |

`EmpVerifyGuardrails` is an explicit-deny policy attached to both the user and
the role. Explicit denies win over any allow, so even if an allow is ever
broadened by mistake, these stay off limits:

- creating or changing IAM users, access keys, passwords, groups, MFA devices;
- editing the `EmpVerify*` policies or loosening `empverify-infra-admin` (trust policy, attached policies);
- account-level services (Organizations, billing, SSO, etc.) and audit tampering (CloudTrail, Config, GuardDuty);
- EC2 instances, spot/reserved purchases, EBS, VPN/peering, Aurora clusters, RDS restores (Fargate + one small test DB only);
- reading objects from the `empverify-*` buckets (biometric images stay private) or making them public;
- anything outside `us-east-1` (except global services such as IAM/STS).

The deployer user has **no** access to secrets, S3 objects, RDS, EC2 or IAM
writes at all: those are not in `EmpVerifyDeploy`, and IAM denies by default.

## Files

| File | Used for |
|---|---|
| `EmpVerifyDeploy.json` | managed policy on the deployer user |
| `EmpVerifyGuardrails.json` | managed deny policy on the user and the infra role |
| `EmpVerifyInfra.json` | managed policy on the infra role |
| `empverify-infra-admin-trust.json` | trust policy of the infra role (deployer user + MFA) |
| `github-deploy-role-policy.json` | inline policy of the GitHub OIDC role (mirrored in `infra/terraform/bootstrap/main.tf`) |

Update a policy by editing the JSON and creating a new default version:

```powershell
aws iam create-policy-version --policy-arn arn:aws:iam::119331772192:policy/EmpVerifyDeploy `
  --policy-document file://EmpVerifyDeploy.json --set-as-default
```

Because the guardrails forbid the deployer from editing `EmpVerify*` policies,
run that as the account root / console administrator, not with the deployer key.

## One-time setup done by the account owner (console, signed in as root)

1. IAM → Users → `empverify-deployer` → Permissions → **remove `AdministratorAccess`**
   (the user cannot remove it itself: the guardrails deny every policy detach).
2. IAM → Users → `empverify-deployer` → Security credentials → **Assign MFA device**
   (virtual device in an authenticator app). Note the device ARN, e.g.
   `arn:aws:iam::119331772192:mfa/empverify-deployer`.
3. Add a CLI profile for infrastructure work in `~/.aws/config`:

```ini
[profile empverify-infra]
role_arn       = arn:aws:iam::119331772192:role/empverify-infra-admin
source_profile = empverify
mfa_serial     = arn:aws:iam::119331772192:mfa/empverify-deployer
region         = us-east-1
```

Then run Terraform with `AWS_PROFILE=empverify-infra`; the CLI asks for the
MFA code once per hour-long session. Day-to-day deploys keep using `empverify`.

If the key is ever suspected to be exposed: rotate it (console → Security
credentials → create a new key, update `~/.aws/credentials`, delete the old
one). With these policies the exposure window buys an attacker nothing beyond
redeploying the backend image, which CloudTrail records.
