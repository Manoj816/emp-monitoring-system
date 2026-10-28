# Cognito user pool for admin-portal sign-in (replaces the dev X-Admin-Key).
resource "aws_cognito_user_pool" "admin" {
  name = "${var.project}-admins"

  password_policy {
    minimum_length    = 8 # test env; raise to 12+ for production
    require_lowercase = true
    require_uppercase = true
    require_numbers   = true
    require_symbols   = true
  }

  admin_create_user_config {
    allow_admin_create_user_only = true # admins are provisioned, not self-signup
  }
}

resource "aws_cognito_user_pool_client" "admin_portal" {
  name                                 = "${var.project}-admin-portal"
  user_pool_id                         = aws_cognito_user_pool.admin.id
  generate_secret                      = false
  # USER_PASSWORD_AUTH is for test scripting (curl/CLI); the portal uses SRP. Drop it for production.
  explicit_auth_flows                  = ["ALLOW_USER_SRP_AUTH", "ALLOW_REFRESH_TOKEN_AUTH", "ALLOW_USER_PASSWORD_AUTH"]
  supported_identity_providers         = ["COGNITO"]
  allowed_oauth_flows_user_pool_client = false
}

# Identity pool backing the Face Liveness React component. The component needs
# AWS credentials in the browser to call StartFaceLivenessSession; it uses the
# unauthenticated role, scoped to exactly that one action.
resource "aws_cognito_identity_pool" "liveness" {
  identity_pool_name               = "${var.project}-liveness"
  allow_unauthenticated_identities = true
}

data "aws_iam_policy_document" "liveness_unauth_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = ["cognito-identity.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "cognito-identity.amazonaws.com:aud"
      values   = [aws_cognito_identity_pool.liveness.id]
    }
    condition {
      test     = "ForAnyValue:StringLike"
      variable = "cognito-identity.amazonaws.com:amr"
      values   = ["unauthenticated"]
    }
  }
}

resource "aws_iam_role" "liveness_unauth" {
  name               = "${var.project}-liveness-unauth"
  assume_role_policy = data.aws_iam_policy_document.liveness_unauth_assume.json
}

resource "aws_iam_role_policy" "liveness_unauth" {
  name = "start-face-liveness"
  role = aws_iam_role.liveness_unauth.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["rekognition:StartFaceLivenessSession"]
      Resource = "*"
    }]
  })
}

resource "aws_cognito_identity_pool_roles_attachment" "liveness" {
  identity_pool_id = aws_cognito_identity_pool.liveness.id
  roles = {
    unauthenticated = aws_iam_role.liveness_unauth.arn
  }
}
