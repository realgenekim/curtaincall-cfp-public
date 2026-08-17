-- 002-people.sql — programming-committee members.
--
-- Applied idempotently at startup by cfp-scheduler-killer.db/migrate!.
-- Every statement MUST be safe to re-run (IF NOT EXISTS).
--
-- people are EVENT-INDEPENDENT identities keyed by email: the same human shows
-- up on next year's committee, and later as a speaker, without being recreated.
-- Emails are stored lowercased by the write path (see committees/normalize-email)
-- so the unique index is a real identity constraint, not a spelling contest.

CREATE TABLE IF NOT EXISTS people (
  id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  email       text UNIQUE NOT NULL,
  name        text NOT NULL,
  profile     jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at  timestamptz DEFAULT now()
);

-- membership = a person on a committee. NOT a permission grant:
-- committee is roster + scope filter, never a permission fortress
-- (docs/design/domain-model.md).
--
-- role: 'member' | 'chair'. The column exists NOW so the roster can record who
-- runs the committee; chair SEMANTICS (gating notify / harden actions) land in
-- a later slice. Until then a chair is a badge, not a capability.
CREATE TABLE IF NOT EXISTS memberships (
  id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  committee_id  uuid NOT NULL REFERENCES committees (id) ON DELETE CASCADE,
  person_id     uuid NOT NULL REFERENCES people (id) ON DELETE CASCADE,
  role          text NOT NULL DEFAULT 'member',
  created_at    timestamptz DEFAULT now(),
  UNIQUE (committee_id, person_id)
);

CREATE INDEX IF NOT EXISTS memberships_committee_id_idx ON memberships (committee_id);
CREATE INDEX IF NOT EXISTS memberships_person_id_idx ON memberships (person_id);
