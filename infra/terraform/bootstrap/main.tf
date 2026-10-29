# One-time bootstrap, run locally by a human administrator after the AWS account
# is created. Everything after this deploys from GitHub Actions via OIDC —
# no long-lived IAM user keys anywhere.

terraform {
  required_version = ">= 1.7"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# GitHub's OIDC identity provider: lets workflow runs present a signed token
# that AWS exchanges for short-lived role credentials.
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

# Deploy role assumable ONLY by this repository's main branch or production
# environment. Tighten the sub condition further per-environment as needed.
resource "aws_iam_role" "github_deploy" {
  name = "empverify-github-deploy"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Federated = aws_iam_openid_connect_provider.github.arn
      }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        }
        StringLike = {
          "token.actions.githubusercontent.com:sub" = [
            "repo:${var.github_repository}:ref:refs/heads/main",
            "repo:${var.github_repository}:environment:production",
          ]
        }
      }
    }]
  })
}

# Deploy permissions: ECR push + a new ECS task-definition revision + service
# update (what .github/workflows/backend.yml does). Mirrors infra/iam/
# github-deploy-role-policy.json. Broaden deliberately and minimally as
# infrastructure modules are added (S3 sync, Amplify, etc.).
resource "aws_iam_role_policy" "deploy" {
  name = "empverify-deploy"
  role = aws_iam_role.github_deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuth"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "EcrPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:CompleteLayerUpload",
          "ecr:InitiateLayerUpload",
          "ecr:PutImage",
          "ecr:UploadLayerPart",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer",
        ]
        Resource = "arn:aws:ecr:${var.aws_region}:*:repository/empverify-*"
      },
      {
        Sid      = "TaskDefinitions"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"]
        Resource = "*"
      },
      {
        Sid    = "EcsDeploy"
        Effect = "Allow"
        Action = [
          "ecs:UpdateService",
          "ecs:DescribeServices",
        ]
        Resource = "*"
        Condition = {
          ArnLike = { "ecs:cluster" = "arn:aws:ecs:${var.aws_region}:*:cluster/empverify-*" }
        }
      },
      {
        # The task definition names the execution and task roles; registering it
        # requires passing them, and only to ECS.
        Sid    = "PassTaskRolesToEcsOnly"
        Effect = "Allow"
        Action = "iam:PassRole"
        Resource = [
          "arn:aws:iam::*:role/empverify-ecs-execution",
          "arn:aws:iam::*:role/empverify-ecs-task",
        ]
        Condition = {
          StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" }
        }
      },
    ]
  })
}
