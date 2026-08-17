-- 001-init.sql — event-creation vertical slice.
--
-- Applied idempotently at startup by cfp-scheduler-killer.db/migrate!.
-- Every statement MUST be safe to re-run (IF NOT EXISTS). No migration framework:
-- the file is split on ';' and each statement executed in order.
--
-- House style: hybrid JSONB — the columns we filter/sort on are real columns,
-- everything else lives in a JSONB blob. events_log is append-only: every
-- mutation writes a row (docs/design/domain-model.md).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS events (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slug           text UNIQUE NOT NULL,
  name           text NOT NULL,
  starts_on      date,
  ends_on        date,
  tz             text NOT NULL,
  cfp_opens_at   timestamptz,
  cfp_closes_at  timestamptz,
  support_email  text,
  settings       jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at     timestamptz DEFAULT now()
);

CREATE TABLE IF NOT EXISTS committees (
  id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id         uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
  name             text NOT NULL,
  scope            jsonb NOT NULL DEFAULT '{"all": true}'::jsonb,
  coverage_target  int NOT NULL DEFAULT 2,
  created_at       timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS committees_event_id_idx ON committees (event_id);

CREATE TABLE IF NOT EXISTS forms (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id    uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
  fields      jsonb NOT NULL,
  created_at  timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS forms_event_id_idx ON forms (event_id);

-- Append-only. Never UPDATE or DELETE a row here.
CREATE TABLE IF NOT EXISTS events_log (
  id        bigserial PRIMARY KEY,
  at        timestamptz NOT NULL DEFAULT now(),
  event_id  uuid,
  actor     text,
  type      text NOT NULL,
  payload   jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS events_log_event_id_idx ON events_log (event_id);
CREATE INDEX IF NOT EXISTS events_log_at_idx ON events_log (at DESC);
