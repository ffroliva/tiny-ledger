--liquibase formatted sql

--changeset flavio:3-init-audit-store
CREATE TABLE audit_entries (
    id BIGSERIAL PRIMARY KEY,
    account_id UUID NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    stream_version BIGINT NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Kafka delivery is at-least-once, so redelivery must not duplicate the trail (ADR 0001).
CREATE UNIQUE INDEX uk_audit_entries_account_version ON audit_entries (account_id, stream_version);

CREATE INDEX idx_audit_entries_keyset ON audit_entries (account_id, occurred_at DESC, id DESC);
