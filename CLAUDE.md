# RecipeDealer backend (PoC)

Spring Boot backend for the RecipeDealer Android app (`../RecipeDealerFinal`, separate repo).

## Rules
- Code, identifiers, comments, commit messages, test names: English.
- Build in milestones (see README); keep the build green; one commit per milestone.
- Data access: `JdbcClient` + plain SQL, no JPA. Migrations: Flyway, one file per milestone, never edit an applied one.
- The capture contract is `src/main/resources/schema/capture-v1.json` (copied unchanged from the Android repo).
  Do not invent fields; an incompatible change means a new `schemaVersion` and a new file.
- Server-generated IDs are UUIDv7; `captureId` comes from the phone.
- All time comparisons (locks, jobs) use the database clock (`now()`).
- Pin library and image versions; check current stable versions instead of relying on memory.

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
- Server: `ssh poc-vps` (root), aarch64, Ubuntu 26.04.1 LTS, Docker 29.9.2, Compose v5. Data under `/srv/recipedealer`.
- Upload limits: 25 MiB per file, 50 MiB per request (Spring "MB" = MiB, Caddy `50MiB`).
- Server IPv4: 2.31.66.200. Caddy uses the Let's Encrypt `shortlived` profile for the IP certificate.

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
- Claude Code allows only `ssh poc-vps *` (permissions and sandbox exclusion): put SSH options after the host
  (`ssh poc-vps -o ConnectTimeout=10 '...'`), otherwise the command does not match and is denied.
- Run `gh`, `git push` and `ssh poc-vps` as standalone commands: never chain them with `;`, `&&`, `|`, and never
  add redirections like `2>&1`. They must match permission patterns exactly to run outside the sandbox.
- Command substitution (`$(...)`) anywhere in an `ssh poc-vps` command, even inside the quoted remote command,
  breaks the match: it runs sandboxed and fails with "Could not resolve hostname poc-vps". Split it into separate
  calls and pass values literally.

## Build & test
- JDK 25 is on the PATH (Corretto); run `./mvnw` as is. Commands with `export JAVA_HOME=...` or a `JAVA_HOME=...`
  prefix are denied by the Claude Code permissions.
- `./mvnw verify` – unit + integration tests (Testcontainers, needs a running Docker). In the Claude Code sandbox add
  `"-DargLine=-Djava.io.tmpdir=$TMPDIR"` (the system temp dir is not writable); Docker is reached through
  `DOCKER_HOST` and `allowUnixSockets` in the user's settings.
- Local stack smoke test: copy `target/recipedealer.jar` next to the Dockerfile, create `.env` with
  `DATA_DIR=./local-data`, `docker compose up -d --build postgres app` (publish 8080 with an override file).
