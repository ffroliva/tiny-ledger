--liquibase formatted sql

--changeset flavio:2-init-balance-projection
CREATE TABLE balance_projections (
    account_id UUID PRIMARY KEY,
    account_name VARCHAR(128) NOT NULL,
    owner VARCHAR(128) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance_minor_units BIGINT NOT NULL DEFAULT 0,
    stream_version BIGINT NOT NULL DEFAULT 0,
    as_of TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE account_history (
    transaction_uid UUID NOT NULL,
    account_id UUID NOT NULL REFERENCES balance_projections(account_id),
    movement_type VARCHAR(16) NOT NULL,
    direction VARCHAR(3) NOT NULL,
    amount_currency VARCHAR(3) NOT NULL,
    amount_minor_units BIGINT NOT NULL,
    balance_after_currency VARCHAR(3) NOT NULL,
    balance_after_minor_units BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'SETTLED',
    transaction_time TIMESTAMP WITH TIME ZONE NOT NULL,
    settlement_time TIMESTAMP WITH TIME ZONE NOT NULL,
    reference VARCHAR(256),
    PRIMARY KEY (account_id, transaction_uid)
);

CREATE INDEX idx_account_history_keyset ON account_history (account_id, transaction_time DESC, transaction_uid DESC);
