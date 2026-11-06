variable "aws_region" {
  description = "Must support Rekognition Face Liveness: us-east-1 | us-west-2 | eu-west-1 | ap-northeast-1 | ap-south-1"
  type        = string
  default     = "us-east-1"
}

variable "github_repository" {
  description = "owner/repo allowed to assume the deploy role"
  type        = string
  default     = "Manoj816/emp-monitoring-system"
}

variable "github_repository_immutable" {
  description = <<-EOT
    The same repository in GitHub's immutable OIDC subject form,
    owner@owner_id/repo@repo_id — what the sub claim actually carries now that
    GitHub enabled immutable subjects here. Read the current value with:
    curl -s https://api.github.com/repos/Manoj816/emp-monitoring-system/actions/oidc/customization/sub
  EOT
  type        = string
  default     = "Manoj816@221407696/emp-monitoring-system@1435794160"
}
