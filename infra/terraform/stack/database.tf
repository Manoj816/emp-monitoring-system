# Generated secrets stored in Secrets Manager; never in state output or env files.
resource "random_password" "db" {
  length  = 24
  special = false
}

resource "random_password" "admin_api_key" {
  length  = 40
  special = false
}

resource "random_password" "device_token_secret" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "db" {
  name = "${var.project}/db-password"
}

resource "aws_secretsmanager_secret_version" "db" {
  secret_id     = aws_secretsmanager_secret.db.id
  secret_string = random_password.db.result
}

resource "aws_secretsmanager_secret" "admin_api_key" {
  name = "${var.project}/admin-api-key"
}

resource "aws_secretsmanager_secret_version" "admin_api_key" {
  secret_id     = aws_secretsmanager_secret.admin_api_key.id
  secret_string = random_password.admin_api_key.result
}

resource "aws_secretsmanager_secret" "device_token_secret" {
  name = "${var.project}/device-token-secret"
}

resource "aws_secretsmanager_secret_version" "device_token_secret" {
  secret_id     = aws_secretsmanager_secret.device_token_secret.id
  secret_string = random_password.device_token_secret.result
}

# Tenant provisioning token: the installer/MDM places it on each laptop so the agent
# self-registers as an unassigned system; an admin then links it to an employee.
resource "random_password" "provisioning_token" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "provisioning_token" {
  name = "${var.project}/provisioning-token"
}

resource "aws_secretsmanager_secret_version" "provisioning_token" {
  secret_id     = aws_secretsmanager_secret.provisioning_token.id
  secret_string = random_password.provisioning_token.result
}

resource "aws_db_subnet_group" "main" {
  name       = "${var.project}-db"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_instance" "main" {
  identifier              = "${var.project}-db"
  engine                  = "postgres"
  engine_version          = var.db_engine_version
  instance_class          = var.db_instance_class
  allocated_storage       = 20
  storage_type            = "gp3"
  storage_encrypted       = true # default AWS-managed RDS key (free)
  db_name                 = var.db_name
  username                = var.db_username
  password                = random_password.db.result
  db_subnet_group_name    = aws_db_subnet_group.main.name
  vpc_security_group_ids  = [aws_security_group.rds.id]
  multi_az                = false
  publicly_accessible     = false
  backup_retention_period = var.db_backup_retention
  skip_final_snapshot     = true
  apply_immediately       = true
  tags                    = { Name = "${var.project}-db" }
}
