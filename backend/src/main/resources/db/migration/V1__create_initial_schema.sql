-- Initial schema: normalized banking entities (users/devices/merchants/transactions)
-- plus ML scoring/explainability/audit tables. See database_decisions memory for the
-- hybrid rationale behind risk_scores.feature_snapshot.

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    external_ref VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE devices (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    device_fingerprint VARCHAR(255) NOT NULL,
    device_type VARCHAR(50),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, device_fingerprint)
);

CREATE TABLE merchants (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    device_id BIGINT NOT NULL REFERENCES devices(id) ON DELETE RESTRICT,
    merchant_id BIGINT NOT NULL REFERENCES merchants(id) ON DELETE RESTRICT,
    amount NUMERIC(12, 2) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    transaction_time TIMESTAMPTZ NOT NULL,
    location_country VARCHAR(2),
    location_city VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE risk_scores (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    fraud_probability NUMERIC(6, 5) NOT NULL,
    action VARCHAR(10) NOT NULL CHECK (action IN ('APPROVE', 'REVIEW', 'BLOCK')),
    model_version VARCHAR(50) NOT NULL,
    feature_snapshot JSONB NOT NULL,
    scored_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE explanations (
    id BIGSERIAL PRIMARY KEY,
    risk_score_id BIGINT NOT NULL REFERENCES risk_scores(id) ON DELETE CASCADE,
    feature_name VARCHAR(100) NOT NULL,
    feature_value VARCHAR(255),
    shap_value NUMERIC NOT NULL
);

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
    risk_score_id BIGINT REFERENCES risk_scores(id) ON DELETE RESTRICT,
    actor VARCHAR(100) NOT NULL,
    action_taken VARCHAR(20) NOT NULL,
    model_version VARCHAR(50),
    threshold_review NUMERIC(6, 5),
    threshold_block NUMERIC(6, 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Baseline indexes: foreign keys + timestamp columns only (per database_decisions).

CREATE INDEX idx_devices_user_id ON devices(user_id);

CREATE INDEX idx_transactions_user_id ON transactions(user_id);
CREATE INDEX idx_transactions_device_id ON transactions(device_id);
CREATE INDEX idx_transactions_merchant_id ON transactions(merchant_id);
CREATE INDEX idx_transactions_transaction_time ON transactions(transaction_time);

CREATE INDEX idx_risk_scores_transaction_id ON risk_scores(transaction_id);
CREATE INDEX idx_risk_scores_scored_at ON risk_scores(scored_at);

CREATE INDEX idx_explanations_risk_score_id ON explanations(risk_score_id);

CREATE INDEX idx_audit_log_transaction_id ON audit_log(transaction_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);
