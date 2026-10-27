# KMS key encrypting images at rest (S3) and the RDS volume.
resource "aws_kms_key" "main" {
  description             = "${var.project} data encryption"
  deletion_window_in_days = 7
  enable_key_rotation     = true
  tags                    = { Name = "${var.project}-kms" }
}

resource "aws_kms_alias" "main" {
  name          = "alias/${var.project}"
  target_key_id = aws_kms_key.main.key_id
}

# S3 bucket for reference images and CHECK-event evidence.
resource "aws_s3_bucket" "images" {
  bucket = "${var.project}-images-${data.aws_caller_identity.current.account_id}"
  tags   = { Name = "${var.project}-images" }
}

resource "aws_s3_bucket_versioning" "images" {
  bucket = aws_s3_bucket.images.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "images" {
  bucket = aws_s3_bucket.images.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.main.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "images" {
  bucket                  = aws_s3_bucket.images.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Retention: expire capture evidence after the policy window. Reference images
# (reference/ prefix) are retained until offboarding and are not expired here.
resource "aws_s3_bucket_lifecycle_configuration" "images" {
  bucket = aws_s3_bucket.images.id
  rule {
    id     = "expire-evidence"
    status = "Enabled"
    filter {
      prefix = "evidence/"
    }
    expiration {
      days = var.evidence_retention_days
    }
  }
}

# Container registry for the backend image.
resource "aws_ecr_repository" "backend" {
  name                 = "${var.project}-backend"
  image_tag_mutability = "MUTABLE"
  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep last 10 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${var.project}-backend"
  retention_in_days = 30
}
