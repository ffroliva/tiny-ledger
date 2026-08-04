--liquibase formatted sql

--changeset flavio:1-init-event-store
CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    sequence_number BIGINT NOT NULL,
    global_index BIGSERIAL NOT NULL,
    payload JSONB NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    client_movement_uid UUID
);

CREATE UNIQUE INDEX uk_events_aggregate_sequence ON events (aggregate_id, sequence_number);
CREATE UNIQUE INDEX uk_events_client_movement_uid ON events (client_movement_uid) WHERE client_movement_uid IS NOT NULL;
