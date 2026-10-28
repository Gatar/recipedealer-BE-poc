# RecipeDealer backend (PoC)

Backend for RecipeDealer, a private recipe system for one household. Android devices (phones and a kitchen
tablet) send recipe *captures* (a structured recipe extracted on the phone plus all raw material); the backend
stores them forever, processes them asynchronously into versioned recipes, and serves browsing, sync, search
and editing.

## Architecture in short

- **Captures** are immutable raw input: the original JSON bytes and every attachment are content-addressed blobs
  (`BlobStore`, SHA-256), validated against the capture JSON Schema (`schemaVersion: 1`) shared with the Android
  app (`src/main/resources/schema/capture-v1.json`).
- **Processing runs** turn a capture into recipe candidates through an extraction cascade (`ExtractionCascade`):
  the phone's result (`PassthroughExtractor`), then Claude Haiku, then Claude Sonnet (`ClaudeExtractor`); a step
  runs only when the previous one fails validation (title, ingredients, steps). One job and one run per step. Jobs
  live in a PostgreSQL table (`FOR UPDATE SKIP LOCKED`, lease, exponential backoff, `needs_attention`).
  `ProvenanceGuard` keeps fields the user edited on the phone; candidates whose ingredients or steps come from
  `heuristic` / `ocr` / `page` / `llm` are marked `needsReview`.
- **Recipes** have identity keys (normalized canonical URL, JSON-LD `@id`) and immutable **revisions**
  (`MACHINE` or `USER`). Automatic changes never move a head the user wrote; equal content creates no revision
  (except a user save of an unchanged head that `needsReview`: it becomes a USER revision and clears the flag).
- **Deduplication** is the user's decision (NEW or UPDATE) based on candidates ranked by exact URL, then
  trigram title similarity. A decision may arrive before processing finishes and is applied later.
- **Thumbnails** are blobs too: the server downloads a recipe's web photo (`WebImageService`) and finds the photo
  in PDF attachments (`PdfImageService`, PDFBox: the largest image of the first page that has one), both in the
  background with the job worker. For photos and screenshots the LLM step points at the dish photo and
  `DishPhotoCropper` cuts it out (`dish_photo`); a photo the LLM did not read is the thumbnail as it is.
- **Editing** is online only: 5-minute edit lease (`Lock-Id`) plus `If-Match` on the head revision.

Stack: Java 25, Spring Boot 4.1, Maven, PostgreSQL 18 (`pg_trgm`, `unaccent`), `JdbcClient` with plain SQL,
Flyway, JUnit + Testcontainers. Deployed with Docker Compose (app, PostgreSQL, Caddy) on one ARM64 VPS.

## API

The full reference is [`docs/api.md`](docs/api.md): every endpoint with its request, responses and error codes.
`ApiDocumentationTest` fails the build when the code and that file list different endpoints.

In short: a user registers (`POST /v1/auth/register`, with the household registration code) or logs in
(`POST /v1/auth/login`) and gets a device token. Devices authenticate with `Authorization: Bearer <device token>`
and use `/v1/captures`, `/v1/recipes`, `/v1/tags`, `/v1/blobs`, `/v1/me` and `/v1/health`. The admin uses HTTP Basic on `/v1/admin/**` (enrollments, devices,
backups, LLM comparisons and reprocessing, API key check). Errors are `{"error": "...", "details": ["..."]}`.

## Local development

Requirements: JDK 25, Docker (for Testcontainers and the local stack).

```bash
export JAVA_HOME=/path/to/jdk-25
./mvnw verify                      # unit + integration tests against PostgreSQL 18.6 in a container
```

Run the app against a local database:

```bash
docker run -d --name rd-postgres -p 5432:5432 \
  -e POSTGRES_DB=recipedealer -e POSTGRES_USER=recipedealer -e POSTGRES_PASSWORD=recipedealer \
  -e POSTGRES_INITDB_ARGS="--encoding=UTF8 --locale=C.UTF-8" postgres:18.6

export RECIPEDEALER_ADMIN_USERNAME=admin
export RECIPEDEALER_ADMIN_PASSWORD_HASH="$(docker run --rm caddy:2.11.7 caddy hash-password --plaintext admin)"
./mvnw spring-boot:run             # http://localhost:8080, blobs in ./data/blobs
```

Configuration (environment variables): `DB_URL`, `DB_USER`, `DB_PASSWORD`, `BLOB_DIR`, `BACKUP_DIR`,
`RECIPEDEALER_ADMIN_USERNAME`, `RECIPEDEALER_ADMIN_PASSWORD_HASH` (bcrypt), `REGISTRATION_CODE`, `MAX_USERS`
(default 10), `ANTHROPIC_API_KEY`, `DEADMAN_API_KEY_URL`. The admin credentials never live in the database; without
them the admin endpoints answer 401. Without `REGISTRATION_CODE` self-service registration is disabled. Without
`ANTHROPIC_API_KEY` the LLM extraction steps are disabled (one WARN at startup).

Tests never call the Claude API (a stubbed client). The opt-in test against the real API costs a fraction of a cent:

```bash
CLAUDE_IT=true ANTHROPIC_API_KEY=sk-ant-... ./mvnw test -Dtest=ClaudeApiTest
```

## Deployment

Target: Hetzner CAX11 (ARM64, Ubuntu, Docker + Compose). Everything lives on the server disk, which Hetzner
Backups cover:

| Path | Content |
|---|---|
| `/srv/recipedealer/app` | `compose.yaml`, `Caddyfile`, `Dockerfile`, JAR, scripts, `.env` (secrets) |
| `/srv/recipedealer/postgres` | PostgreSQL data |
| `/srv/recipedealer/blobs` | Capture bodies and attachments (content-addressed) |
| `/srv/recipedealer/dumps` | Nightly `pg_dump` files (last 7), `pre-restore-*.dump` written by `restore.sh` |
| `/srv/recipedealer/backups` | On-request backups (`POST /v1/admin/backups`), kept until deleted |
| `/srv/recipedealer/caddy` | Caddy certificates and ACME account |

GitHub Actions (`.github/workflows/ci.yml`) builds and tests on every push. On `main` it copies the JAR and the
deployment files to the server with `scp` and runs `docker compose up -d --build` over SSH; the server builds the
small application image itself (no registry).

### First deployment

1. **Server directories and secrets** (as root on the server):

   ```bash
   mkdir -p /srv/recipedealer/{app,postgres,blobs,backups,dumps,caddy}
   chown 10001:10001 /srv/recipedealer/{blobs,backups}   # the app container runs as uid 10001
   ```

   Create `/srv/recipedealer/app/.env` from [`deploy/env.example`](deploy/env.example) with `chmod 600`.
   Generate the admin hash with
   `docker run --rm caddy:2.11.7 caddy hash-password --plaintext 'your-admin-password'` and put it in single
   quotes (it contains `$`).

2. **Firewall**: allow inbound 22/tcp, 80/tcp (ACME HTTP challenge), 443/tcp and 443/udp, in the Hetzner Cloud
   Firewall or with `ufw`.

3. **SSH key for GitHub Actions** (Actions secrets, not repository deploy keys: the workflow logs in to the
   server, the server never accesses GitHub): on your machine,
   `ssh-keygen -t ed25519 -f recipedealer-deploy -C github-actions -N ''`, append `recipedealer-deploy.pub` to
   `/root/.ssh/authorized_keys` on the server. In the GitHub repository settings → Secrets and variables →
   Actions add:
   - `SSH_PRIVATE_KEY`: content of `recipedealer-deploy`
   - `SSH_HOST`: the server's IPv4
   - optional `SSH_KNOWN_HOSTS`: output of `ssh-keyscan <host>` (otherwise the workflow scans the key on each run)
   - optional `SSH_USER` (default `root`)

4. **Push to `main`** (or run the workflow manually). The deploy job also installs the backup cron entry and
   waits until the app answers. Caddy then requests the certificate; check with
   `docker compose -f /srv/recipedealer/app/compose.yaml logs caddy`.

5. **Configure the phone**: base URL `https://<server IPv4>` and a device token (next section).

### HTTPS on an IP address

Let's Encrypt issues certificates for IP addresses only with the short-lived profile (about 6 days). Caddy 2.11
supports this (`cert_issuer acme { profile shortlived }` in the Caddyfile) and renews automatically. Clients
connecting to an IP send no SNI, so the Caddyfile sets `default_sni` to the site address.

If Let's Encrypt refuses the IP certificate, the simplest alternative is a free wildcard DNS name that resolves to
the IP, e.g. `SITE_ADDRESS=2-31-66-200.sslip.io` (normal 90-day certificate; the phone uses that name). Moving to a
real domain later means changing only `SITE_ADDRESS` in `.env` (or the site address line in the Caddyfile) and
running `docker compose up -d`.

### Enroll a device

```bash
curl -u admin:'your-admin-password' -H 'Content-Type: application/json' \
  -d '{"userName":"Ania","deviceName":"Pixel 9"}' \
  https://<server>/v1/admin/enrollments
# → {"token":"rd_...","userId":"...","deviceId":"..."}   (the token is shown only once)

curl -u admin:'your-admin-password' https://<server>/v1/admin/devices
curl -u admin:'your-admin-password' -X DELETE https://<server>/v1/admin/devices/<deviceId>   # revoke
```

Shared devices are users too: enroll the kitchen tablet with `"userName":"Tablet"`.

### Backups

`/srv/recipedealer/app/backup.sh` runs from `/etc/cron.d/recipedealer-backup` every day at 01:30 server time
(UTC), before the Hetzner backup window. It writes `pg_dump --format=custom` to `/srv/recipedealer/dumps`, keeps the
last 7 dumps, and pings `DEADMAN_URL` from `.env` if set (e.g. a healthchecks.io check). Log:
`/var/log/recipedealer-backup.log`. Blobs are not in the dump; they are files under `/srv/recipedealer/blobs`,
covered by the Hetzner server backup. Off-site backup is out of scope for v1.

### Backups on request

`POST /v1/admin/backups` writes one ZIP to `/srv/recipedealer/backups` with `database.dump` (`pg_dump --format=custom`,
taken by the app with the PostgreSQL 18 client in its image), `blobs/` (all blobs, same layout as the blob store) and
`manifest.json` (time, label, last Flyway migration, counts, SHA-256 of the dump). The dump is taken first, then the
blobs are copied, so the archive is consistent. Backups are never deleted automatically.

```bash
curl -u admin:'your-admin-password' -H 'Content-Type: application/json' -d '{"label":"core"}' \
  https://<server>/v1/admin/backups
curl -u admin:'your-admin-password' https://<server>/v1/admin/backups
curl -u admin:'your-admin-password' -OJ https://<server>/v1/admin/backups/recipedealer-<timestamp>-core.zip
```

### Restore

`/srv/recipedealer/app/restore.sh` (as root on the server) replaces the whole database with a backup ZIP (a path or a
name in `/srv/recipedealer/backups`) or a bare dump file (e.g. a nightly dump), and copies the backup's missing blobs.
It asks for confirmation (`--yes` skips it) and first saves the current database to
`/srv/recipedealer/dumps/pre-restore-<timestamp>.dump`; restoring that file undoes the restore.

```bash
/srv/recipedealer/app/restore.sh recipedealer-<timestamp>-core.zip
/srv/recipedealer/app/restore.sh /srv/recipedealer/dumps/recipedealer-<timestamp>.dump
```

A nightly dump has no blobs: restore `/srv/recipedealer/blobs` from the Hetzner backup of the same night if blobs
were lost too (blobs newer than the dump are harmless: they are just unreferenced).

### LLM extraction (Claude)

1. Create an API key in the Claude Console (console.anthropic.com → API keys), ideally in its own workspace with a
   spend limit, and put it into `.env` as `ANTHROPIC_API_KEY=sk-ant-...`. Never commit or print it.
2. Optional dead man's switch for the key: create a second healthchecks.io check (period 1 day, grace a few hours),
   put its URL into `.env` as `DEADMAN_API_KEY_URL`. Every day at 06:00 server time the app sends a minimal request
   (`max_tokens: 0` to Claude Haiku) and pings the URL only if it succeeds, so an invalid key or an empty account
   shows up as a missing ping. The app logs an ERROR with the error type and HTTP status, never the key.
3. `docker compose up -d` (the deploy job does it), then check the key at once:

   ```bash
   curl -u admin:'your-admin-password' -X POST https://<server>/v1/admin/api-key-check
   ```

Models, effort, timeouts and prices (USD per million tokens, taken from the pricing page; the cost of a run is stored
when it finishes) are in `application.yaml` under `recipedealer.llm`. Every capture whose phone result is incomplete
goes to Claude Haiku, and to Claude Sonnet if Haiku's result is incomplete too. Usage per run is stored on
`processing_run` and shown per recipe by `GET /v1/recipes/{recipeId}/extraction-stats`.

Admin tools (see `docs/api.md`):

```bash
# Compare models on some captures (never applied), then read the report side by side.
curl -u admin:'…' -H 'Content-Type: application/json' -X POST https://<server>/v1/admin/llm-comparisons \
  -d '{"captureIds": ["…"], "steps": ["haiku", "sonnet"]}'
curl -u admin:'…' "https://<server>/v1/admin/llm-comparisons?captureIds=…,…"
# Process captures again through the cascade (from Haiku), e.g. all that wait for extraction.
curl -u admin:'…' -H 'Content-Type: application/json' -X POST https://<server>/v1/admin/llm-extractions \
  -d '{"reason": "AWAITING_EXTRACTION"}'
```

### Operations

- Logs: `docker compose logs -f app` (in `/srv/recipedealer/app`).
- Diagnostics: the image is a full JDK, e.g. `docker compose exec app jcmd 1 Thread.print`.
- The app runs with `-Xmx1g` and the default GC.
- Failed processing jobs end in capture status `needs_attention` with reason `PROCESSING_FAILED`; a Claude key or
  credit problem with `LLM_UNAVAILABLE` (not retried: fix the key, then reprocess with
  `{"reason": "LLM_UNAVAILABLE"}`).
