# Version stamp: "ver \<sha\> (deployed Xm ago)"

A tiny pattern that makes every deployed page answer the two questions you
always ask when something looks wrong: **what code is this, and how fresh is it?**

Origin: `~/src.local/joe-payne-app` (esr-dashboard). Also used by
`~/src.local/gaiwan/does/video-library-admin`. Ready-to-use ns:
`src/cfp_scheduler_killer/version.clj` (this template).

## How it works

**1. Build time — the Makefile stamps two files into `resources/` before the jar
is built**, so the artifact carries its own identity:

```makefile
build:
	@git rev-parse --short HEAD > resources/build-sha.txt
	@date -u +%Y-%m-%dT%H:%M:%SZ > resources/build-time.txt
	clojure -T:build thin-build
```

**2. Gitignore the stamps** (they change every build):

```gitignore
resources/build-sha.txt
resources/build-time.txt
```

**3. Runtime — `version.clj` reads the stamps from the classpath**, with a dev
fallback (live `git rev-parse --short HEAD` + `Instant/now`) so it works before
you've wired the Makefile. The sha/build-time are read once (`def`), but
`time-ago` is computed per render — so "deployed 43m ago" stays current.

**4. Render it subtly** in your footer/header:

```clojure
[:span {:style "font-size:11px;color:#bbb"} (version/version-str)]
;; => ver 418cc60e (deployed 12m ago)
```

## Why bother

- **Instant deploy verification** — refresh the staging page after GitHub Actions;
  if the sha didn't change, your deploy didn't take (wrong project, cached
  revision, traffic not shifted).
- **Debugging truth** — "is prod running the fix?" becomes a glance, not a
  `gcloud run revisions describe` expedition.
- **Staleness smell** — "deployed 45d ago" on a service you thought was current
  tells you something by itself.

## Gotchas

- The dev fallback shells out to `git`; in a container without git/repo it
  returns `"dev"` — fine, but it means a MISSING stamp shows as `ver dev`.
  If you see `ver dev` in prod, the Makefile stamping didn't run before the build.
- Stamp **before** `clojure -T:build ...` so the files land inside the jar's
  `resources/`.
- `def`s cache at class-load: a long-running dev REPL keeps the sha from when
  the ns loaded. Fine in practice; reload the ns if it matters.
