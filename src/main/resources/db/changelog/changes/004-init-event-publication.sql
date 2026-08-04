--liquibase formatted sql

--changeset flavio:4-init-event-publication
-- Spec §12: Liquibase is the single migration authority, so Modulith's own schema initializer stays
-- off (spring.modulith.events.jdbc.schema-initialization.enabled=false) and its official DDL lives
-- here instead. Copied verbatim from spring-modulith-events-jdbc 2.1.0,
-- org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql — v2 is what the
-- repository expects unless spring.modulith.events.jdbc.use-legacy-structure is turned on. Re-copy
-- it whenever that jar's version moves.
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);
