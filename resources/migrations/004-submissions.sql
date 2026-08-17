-- 004-submissions.sql — the public CFP's output.
--
-- Applied idempotently at startup by cfp-scheduler-killer.db/migrate!.
--
-- form_snapshot is the whole field-def vector AS IT WAS when this was
-- submitted. Editing the form mid-CFP must never change how an existing
-- submission renders or validates (docs/design/form-builder.md, design rule 1).
-- The cost is duplication; the benefit is that a submission is a complete,
-- self-describing record forever.
--
-- answers is keyed by FIELD ID, never by label — field IDs are forever, labels
-- rename freely (design rule 2).
--
-- status: the 7-valued vocabulary lives in events.settings, not in a CHECK
-- constraint — an organizer may add "Waitlisted"/"Nominated" (seen on
-- Sessionize's own board) without a migration.

CREATE TABLE IF NOT EXISTS submissions (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id       uuid NOT NULL REFERENCES events (id) ON DELETE CASCADE,
  form_snapshot  jsonb NOT NULL,
  answers        jsonb NOT NULL,
  status         text NOT NULL DEFAULT 'Pending',
  priority       boolean NOT NULL DEFAULT false,
  notified_at    timestamptz,
  source         text NOT NULL DEFAULT 'form',
  created_at     timestamptz DEFAULT now()
);

CREATE INDEX IF NOT EXISTS submissions_event_id_idx ON submissions (event_id);
CREATE INDEX IF NOT EXISTS submissions_status_idx ON submissions (status);

-- The speakers ON a submission. `display` is the profile AS SUBMITTED — a
-- snapshot, so a later profile edit never rewrites history on an accepted talk.
-- The people row remains the identity; this is what they said that day.
-- position 0 = the owning speaker (the account that submitted).
CREATE TABLE IF NOT EXISTS submission_speakers (
  id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  submission_id  uuid NOT NULL REFERENCES submissions (id) ON DELETE CASCADE,
  person_id      uuid NOT NULL REFERENCES people (id) ON DELETE CASCADE,
  position       int NOT NULL DEFAULT 0,
  display        jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at     timestamptz DEFAULT now(),
  UNIQUE (submission_id, person_id)
);

CREATE INDEX IF NOT EXISTS submission_speakers_submission_id_idx ON submission_speakers (submission_id);
CREATE INDEX IF NOT EXISTS submission_speakers_person_id_idx ON submission_speakers (person_id);
