variable "aws_region" {
  description = "Must support Rekognition Face Liveness: us-east-1 | us-west-2 | eu-west-1 | ap-northeast-1 | ap-south-1"
  type        = string
  default     = "ap-south-1"
}

variable "project" {
  type    = string
  default = "empverify"
}

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "container_image" {
  description = "Backend image. Placeholder until the first image is pushed to ECR; CI then updates the service."
  type        = string
  default     = "public.ecr.aws/nginx/nginx:latest"
}

variable "desired_count" {
  type    = number
  default = 1
}

variable "db_name" {
  type    = string
  default = "empverify"
}

variable "db_username" {
  type    = string
  default = "empverify"
}

variable "db_engine_version" {
  type    = string
  default = "16.4"
}

variable "db_instance_class" {
  description = "Smallest current-gen (free-tier eligible). Scale up for production."
  type        = string
  default     = "db.t4g.micro"
}

variable "db_backup_retention" {
  description = "Days of automated backups. 1 = minimal for test; use >= 7 in production."
  type        = number
  default     = 1
}

variable "evidence_retention_days" {
  type    = number
  default = 90
}

variable "liveness_ui_url" {
  description = "Public URL of the hosted React Face Liveness page (Amplify)."
  type        = string
  default     = ""
}
