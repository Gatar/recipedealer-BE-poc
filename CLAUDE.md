# RecipeDealer backend (PoC)

Spring Boot backend for the RecipeDealer Android app (`../RecipeDealerAndroid`, separate repo).
Shared rules (language, contract, deployment order, VPS, permission pitfalls): `../CLAUDE.md`.

## Rules
- Build in milestones (see README); keep the build green; one commit per milestone.
- Data access: `JdbcClient` + plain SQL, no JPA. Migrations: Flyway, one file per milestone, never edit an applied one.
- Server-generated IDs are UUIDv7; `captureId` comes from the phone.
- All time comparisons (locks, jobs) use the database clock (`now()`).
- Pin library and image versions; check current stable versions instead of relying on memory.
- API documentation: `docs/api.md`, one `### METHOD /path` heading per endpoint, spelled like the Spring mapping.
  Update it in the same commit as any API change; `ApiDocumentationTest` fails on missing or extra endpoints.

## Decisions (agreed 2026-10-21)
- Versions: Spring Boot 4.1.1 (manages Flyway, Testcontainers 2.x, JUnit 6, Jackson 3), PostgreSQL 18.6,
  networknt json-schema-validator 3.0.6 (3.0.7+ needs Jackson 3.2, Boot manages 3.1), bucket4j_jdk17-core 8.21.0, java-uuid-generator 5.2.0,
  eclipse-temurin 25 JDK image, Caddy 2.11.7.
- Phone contract details (from the Android code): attachment parts are named like `attachments[].name`;
  errors `{"error": "...", "details": ["..."]}`; health body contains `"ok"`; provenance is a flat map
  (`title`, `ingredients`, `steps`, ...) with stages `jsonLd | microdata | pluginHits | heuristic | ocr | page | manual | user`.
  `needsReview` when ingredients/steps come only from `heuristic`, `ocr` or `page`.
- Identity keys come from `raw.web.canonicalUrl` (fallback `source.url`) and `raw.web.jsonLdRecipeId`.
- Admin rate limit: 5 failed admin authentications per hour, global (not per IP), then 429 for all admin calls.
- `DELETE /v1/recipes/{id}` (soft delete, tombstone in the sync feed) is in scope.
- Nightly `pg_dump` at 01:30 server time via host cron; keep the last 7 dumps.
- Upload limits: 25 MiB per file, 50 MiB per request (Spring "MB" = MiB, Caddy `50MiB`).

## Backups (agreed 2026-10-26)
- On request through the admin API: ZIP with `pg_dump` + blobs + manifest in `/srv/recipedealer/backups`, kept until
  deleted. The app image carries `postgresql-client-18` from PGDG, pinned to the postgres image's version
  (`18.6-1.pgdg24.04+2`); bump both together. Tests dump inside the Testcontainers container instead.
- Restore only on the server (`deploy/restore.sh`), never through the API; it saves a pre-restore dump first.
- Later option, not decided: off-site copies (restic to a Hetzner Storage Box / Object Storage).

## Accounts (agreed 2026-10-27)
- Self-service: `POST /v1/auth/register` (needs the household `REGISTRATION_CODE`; without it registration is
  disabled) and `POST /v1/auth/login`; both return a new device token. Admin enrollment stays, without a password and
  beyond the limit.
- At most `MAX_USERS` (10) users in total. Passwords: no rules, bcrypt, at most 72 bytes (rejected, not truncated).
- Tokens do not expire; every login is a new device, earlier tokens stay until revoked.
- Users without a password (admin-enrolled) set one with `PUT /v1/me/password` and their token; changing it needs
  the current password.
- Failed attempts: 5 per user name / 15 min, 50 in total / hour (in memory, reset on restart).

## Tags (agreed 2026-10-25)
- Two levels `category:tag`; each level has a `name` (stable readable id, `[a-z0-9-]`, e.g. `meal:breakfast`) and
  a `title` (shown, e.g. "Posiłek" / "Śniadanie"). Catalog order = the user's order (`position`).
- Assignments (`recipe_tag`) are not recipe content: no revision, no edit lock. Changing them, or renaming / moving /
  deleting a tag in use, bumps `recipe.updated_at` so the recipe comes again in the sync feed with its new `tags`.
- Tags are assigned manually only (also with a NEW decision); no ingredient tags (ingredient search covers that).
- Recipe merge does not exist yet; when it comes, the target gets the union of both recipes' tags.

## Gotchas
- `@ConditionalOnProperty` is evaluated before `DynamicPropertyRegistrar` runs: test properties that switch beans
  (e.g. `recipedealer.worker.enabled=false`) go into `@SpringBootTest(properties = ...)` (see `IntegrationTest`).
- MockMvc does not enforce multipart size limits; `UploadLimitsTest` uses real HTTP.
- Tests share one database: use unique URLs/titles per test (see `TestCaptures.webCapture(id, url, title)`).
- `recipe.updated_at` is always set to `now()` (or later) in the writing transaction: the sync feed only returns
  changes older than the oldest open transaction (`RecipeQueryService.sync`), so a commit cannot land behind a
  client's cursor. A transaction left open (e.g. `psql` idle in `BEGIN`) holds new changes back from the feed.
- Candidates and revisions store the contract version of their content (`schema_version`); set it on every new
  content source.

## Build & test
- JDK 25 is on the PATH (Corretto); run `./mvnw` as is.
- `./mvnw verify` – unit + integration tests (Testcontainers, needs a running Docker). In the Claude Code sandbox add
  `"-DargLine=-Djava.io.tmpdir=$TMPDIR"` (the system temp dir is not writable); Docker is reached through
  `DOCKER_HOST` and `allowUnixSockets` in the user's settings.
- Local stack smoke test: copy `target/recipedealer.jar` next to the Dockerfile, create `.env` with
  `DATA_DIR=./local-data`, `docker compose up -d --build postgres app` (publish 8080 with an override file).
  In the Claude Code sandbox `--build` and image pulls fail (buildx cannot write `~/.docker/buildx`, pulls hit the
  keychain): use an override with `build: !reset null`, `image: recipedealer-app:latest`, `pull_policy: never` and
  the new JAR mounted over `/app/recipedealer.jar`; the user rebuilds the image outside the sandbox when the
  Dockerfile changes.
