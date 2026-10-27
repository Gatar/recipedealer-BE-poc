# RecipeDealer backend PoC: summary so far

Status as of 2026-10-22. Part of the final PoC summary; extend it as the PoC continues.

## What exists

- **Backend** (this repo), built in 8 milestones on 2026-10-21, all green in CI:
  device token auth and admin enrollment; capture ingest with a content-addressed blob store; PostgreSQL job queue
  with passthrough processing, candidates and the NEW/UPDATE resolution; recipe read, revisions, sync feed, search
  and blobs; edit locks, user revisions and soft delete; deployment files and CI/CD.
- **Tests**: 14 test classes (plus the `IntegrationTest` base), unit and integration (Testcontainers with PostgreSQL 18.6). `./mvnw verify` takes about
  50 s in GitHub Actions.
- **Production**: one ARM64 VPS (`2.31.66.200`, Ubuntu 26.04.1, Docker 29.9.2, Compose v5) running app, PostgreSQL
  and Caddy, with HTTPS on the bare IP. Every push to `main` builds, tests and deploys (build ~50 s, deploy ~40 s).
  Nightly `pg_dump` via host cron is installed.
- **First end-to-end run (2026-10-22)**: the Android app (emulator, device "Android Studio Pixel 9") sent a web
  capture of `kwestiasmaku.com/przepis/gofry-belgijskie`; the server processed it into one candidate
  (10 ingredients, 7 steps), the NEW decision was sent with curl, and the recipe "Gofry belgijskie" was created
  (`MACHINE` revision, `needsReview`, source "Kwestia Smaku", canonical-URL identity key).

## What worked well

- **Milestone workflow with a written decision log** (`CLAUDE.md`, "Decisions"): the backend went from empty repo to
  a deployable service in one day, and every later question could be answered from the code and the log.
- **Shared JSON Schema contract** (`capture-v1.json`, copied unchanged from the Android repo): the first real
  capture from the phone validated and processed without any change on either side.
- **PostgreSQL for everything** (job queue with `FOR UPDATE SKIP LOCKED`, trigram search, locks on the database
  clock): one stateful component to run and back up; no broker, no search engine.
- **Building the image on the server** from the JAR copied by CI: no container registry needed.
- **Let's Encrypt IP certificate** (`shortlived` profile, Caddy 2.11.7): issued on the first attempt, within seconds;
  no domain is needed for the PoC. The `sslip.io` fallback was not needed.
- **`sourceSummary` from `source.siteName`** gives a readable source ("Kwestia Smaku") instead of a bare domain.

## Findings and lessons

### Product and API

1. **The Android app implements only the upload part of the API.** It uses `PUT /v1/captures/{id}`,
   `GET /v1/captures/{id}` (status only) and `GET /v1/health`. Decisions, inbox, sync feed, recipe detail, search,
   blobs and editing exist only in the backend, so captures stop at `needs_decision` with no way to continue from
   the phone. The full client spec is in [`update-android-prompt.txt`](../update-android-prompt.txt).
   Lesson: design the client flows together with the backend API, or at least check the client against every
   endpoint before calling a milestone done.
2. **Every capture needs a user decision, even when there is nothing to match.** The first recipe ever had no
   possible duplicates, but still required an explicit NEW. Open question: create automatically when there are no
   candidates (no URL match and no similar title), and ask only when there is a real choice.
3. **Web captures get no thumbnail.** `thumbnailBlob` comes only from image/screenshot attachments; a web capture
   has none, so the recipe has no image on the server, only `recipe.images[]` URLs. Options: the client shows
   `images[0]`, or the backend downloads it into the blob store.
4. **`needsReview` on a well-known site.** On kwestiasmaku.com the title, author and image came from `microdata`,
   but ingredients and steps came from `heuristic`. Worth checking whether the site really lacks structured
   ingredients or the phone's extractor misses them; if many sites look like this, most recipes will be flagged.

### CI/CD and deployment

5. **A green pipeline hid a skipped deployment.** The first CI run was "success" while the deploy job had only
   printed a notice that the SSH secrets were missing. Then the deploy job condition allowed only `push`, so a manual
   run skipped it too, although the README said manual runs deploy. Fixed in `9a8b345`. Lesson: a deployment that
   cannot run should fail or be impossible to miss, and documented behavior needs a test run.
6. **"Deploy keys" vs. Actions secrets.** GitHub's "Deploy keys" let a server access the repository; deploying from
   Actions needs the opposite: a private key in Actions secrets (`SSH_PRIVATE_KEY`, `SSH_HOST`, `SSH_KNOWN_HOSTS`)
   and its public key on the server. The README step was renamed to avoid the confusion.
7. **Pin the server's host keys.** `SSH_KNOWN_HOSTS` was filled from `/etc/ssh/*.pub` on the server instead of a
   network `ssh-keyscan`, so the workflow does not trust whatever answers on first contact.
8. **Manual server preparation is small but easy to get wrong:** directories, `blobs` owned by uid `10001`
   (the app container user), `.env` with mode 600 and the bcrypt hash in single quotes (it contains `$`).
   A candidate for a one-shot setup script.
9. **Checking from the server is not checking from outside.** HTTPS calls from the VPS to its own IP do not pass
   the Hetzner Cloud Firewall; the external check had to be done from a laptop.

### Working with Claude Code

10. **An invalid `~/.claude/settings.json` silently drops the whole configuration.** A missing comma removed the
    sandbox and all allow rules mid-session; in `dontAsk` mode every non-read-only command was then denied with no
    hint about the cause. Validate the JSON after editing it.
11. **Permission patterns match the whole command string.** `gh`, `git push` and `ssh poc-vps` run outside the
    sandbox only as standalone commands: no `;`, `&&`, `|`, redirections, or `$(...)` (even inside the quoted remote
    command). A command that does not match runs in the sandbox and fails in a misleading way (e.g. "Could not
    resolve hostname poc-vps", because `~/.ssh/config` is not readable there). Documented in `CLAUDE.md`.
12. **The sandbox blocks direct network access to the server** (`curl https://2.31.66.200`, `ssh-keyscan`); all
    server checks go through `ssh poc-vps`. Git warns about `~/.config/git/ignore` being unreadable; harmless.

## Security shortcuts taken for the PoC

Status 2026-10-27 (before the beta):
- ~~Admin password `admin`~~: changed. The database password stays `admin` on purpose: PostgreSQL is reachable only
  inside the Docker network, and root on the server beats any database password. Never publish port 5432.
- The GitHub token used by `gh` is stored in plain text in `~/.claude/settings.json` and was pasted into a session;
  rotate it.
- The Actions deploy key logs in as `root` on the server.
- Off-site copies: Hetzner Backups (daily server snapshots) are on; a backup ZIP is copied off the server by hand.

## Open items

- ~~Android: implement the API from `update-android-prompt.txt`~~: done.
- ~~Auto-NEW without candidates (finding 2), server-side thumbnails for web captures (finding 3)~~: done
  2026-10-27 (see `CLAUDE.md`, "Before the beta").
- Investigate the `heuristic` ingredients on kwestiasmaku.com (finding 4).
- ~~Make a skipped or misconfigured deploy fail the pipeline (finding 5).~~ Done: missing deploy secrets fail the
  deploy job.
- ~~Test a restore from a dump~~: done 2026-10-26 (`deploy/restore.sh`, nightly dump and API ZIP).
- Optional: server setup script (finding 8), `DEADMAN_URL` for the nightly dump.
