-- 003-event-location.sql — two optional event facts.
--
-- Applied idempotently at startup by cfp-scheduler-killer.db/migrate!.
-- Additive only: ADD COLUMN IF NOT EXISTS, both nullable, no backfill needed.
--
-- location    — free text ("Charlotte, NC"). Feeds the .ics LOCATION property
--               and the public CFP/schedule pages. Deliberately NOT structured
--               (venue/city/country); an organizer types a place, and the one
--               consumer that needs structure can parse later.
-- website_url — the event's own marketing page, linked from the dashboard and
--               the public pages. Validated as http(s) when present.
--
-- A description blurb was considered and ruled out (bd 23h) — the abstract-like
-- copy already lives on the marketing page these fields point at.

ALTER TABLE events ADD COLUMN IF NOT EXISTS location text;
ALTER TABLE events ADD COLUMN IF NOT EXISTS website_url text;
