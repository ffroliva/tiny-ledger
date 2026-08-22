--liquibase formatted sql

--changeset flavio:6-index-events-global-index
-- global_index has been on this table since 001 but nothing read it, so it was never indexed.
-- EventStorePort.readAll pages on `WHERE global_index > ? ORDER BY global_index ASC LIMIT ?`,
-- which without this index is a sequential scan of the whole log on every page — worst on exactly
-- the operation it exists for, a full projection rebuild.
CREATE INDEX idx_events_global_index ON events (global_index);
