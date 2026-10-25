variable "aws_region" {
  description = "Must support Rekognition Face Liveness: us-east-1 | us-west-2 | eu-west-1 | ap-northeast-1 | ap-south-1"
  type        = string
  default     = "ap-south-1"
}

variable "github_repository" {
  description = "owner/repo allowed to assume the deploy role"
  type        = string
  default     = "Manoj816/emp-monitoring-system"
}
