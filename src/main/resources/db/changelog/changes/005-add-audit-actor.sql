--liquibase formatted sql

--changeset flavio:5-add-audit-actor
ALTER TABLE audit_entries ADD COLUMN actor VARCHAR(255);
