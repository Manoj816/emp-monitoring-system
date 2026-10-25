-- Core schema for the Remote Workforce Identity Verification System.
-- Platform-neutral by design: devices carry a platform discriminator and
-- server-issued opaque IDs so a macOS agent can register without schema changes.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE check_policies (
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                      TEXT NOT NULL UNIQUE,
    work_window_start         TIME NOT NULL DEFAULT '09:00',
    work_window_end           TIME NOT NULL DEFAULT '17:00',
    timezone                  TEXT NOT NULL DEFAULT 'UTC',
    max_checks_per_day        INT  NOT NULL DEFAULT 4,
    response_timeout_seconds  INT  NOT NULL DEFAULT 120,
    match_threshold           NUMERIC(5,2) NOT NULL DEFAULT 90.00,
    review_threshold          NUMERIC(5,2) NOT NULL DEFAULT 75.00,
    liveness_on_session_start BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE employees (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_code   TEXT NOT NULL UNIQUE,
    full_name       TEXT NOT NULL,
    email           TEXT NOT NULL UNIQUE,
    department      TEXT,
    status          TEXT NOT NULL DEFAULT 'ACTIVE'
                    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'OFFBOARDED')),
    policy_id       UUID REFERENCES check_policies(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id          UUID NOT NULL REFERENCES employees(id),
    platform             TEXT NOT NULL CHECK (platform IN ('WINDOWS', 'MACOS')),
    hostname             TEXT,
    status               TEXT NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'ACTIVE', 'REVOKED')),
    -- Hash of the per-device secret; the secret itself lives only in the
    -- device's OS credential store (Credential Manager / Keychain).
    credential_hash      TEXT,
    agent_version        TEXT,
    registered_at        TIMESTAMPTZ,
    last_seen_at         TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_devices_employee ON devices(employee_id);

-- One-time tokens an admin issues so a fresh install can register itself.
CREATE TABLE enrollment_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id  UUID NOT NULL REFERENCES employees(id),
    token_hash   TEXT NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    created_by   TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE reference_images (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    employee_id  UUID NOT NULL REFERENCES employees(id),
    s3_key       TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE', 'REPLACED', 'DELETED')),
    created_by   TEXT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_reference_images_active
    ON reference_images(employee_id) WHERE status = 'ACTIVE';

CREATE TABLE verification_checks (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id            UUID NOT NULL REFERENCES devices(id),
    employee_id          UUID NOT NULL REFERENCES employees(id),
    check_type           TEXT NOT NULL
                         CHECK (check_type IN ('SESSION_START', 'PERIODIC', 'LIVENESS', 'ESCALATION')),
    status               TEXT NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'MATCH', 'CHECK', 'NO_FACE',
                                           'MULTIPLE_FACES', 'VERIFICATION_ERROR', 'EXPIRED')),
    mismatch_flag        BOOLEAN NOT NULL DEFAULT FALSE,
    similarity_score     NUMERIC(5,2),
    liveness_confidence  NUMERIC(5,2),
    -- Evidence is retained only for CHECK / flagged outcomes; MATCH captures are discarded.
    evidence_s3_key      TEXT,
    -- Replay protection: server-issued nonce, single use, short expiry.
    nonce                TEXT NOT NULL,
    error_detail         TEXT,
    requested_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at           TIMESTAMPTZ NOT NULL,
    completed_at         TIMESTAMPTZ
);

CREATE INDEX idx_checks_device_requested ON verification_checks(device_id, requested_at DESC);
CREATE INDEX idx_checks_status ON verification_checks(status) WHERE status IN ('PENDING', 'CHECK');

CREATE TABLE check_reviews (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    check_id    UUID NOT NULL REFERENCES verification_checks(id),
    reviewer    TEXT NOT NULL,
    resolution  TEXT NOT NULL
                CHECK (resolution IN ('CONFIRMED_EMPLOYEE', 'CONFIRMED_MISMATCH',
                                      'TECHNICAL_ISSUE', 'INCONCLUSIVE')),
    notes       TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tamper-evident audit log: append-only, hash-chained per entity in the service layer.
CREATE TABLE audit_log (
    id           BIGSERIAL PRIMARY KEY,
    actor        TEXT NOT NULL,
    action       TEXT NOT NULL,
    entity_type  TEXT NOT NULL,
    entity_id    TEXT,
    details      JSONB,
    prev_hash    TEXT,
    entry_hash   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_entity ON audit_log(entity_type, entity_id);

INSERT INTO check_policies (name) VALUES ('default');
