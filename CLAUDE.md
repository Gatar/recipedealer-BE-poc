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
  The Postman collection `docs/RecipeDealer.postman_collection.json` (v2.1, no secrets in it) follows the same
  changes; no test checks it.

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
- Tokens do not expire. A device = user + device name (case ignored): logging in (or enrolling) again with the same
  name replaces that device's token and keeps its id; a new name adds a device.
- `DELETE /v1/admin/users/{userId}` is a soft delete (`deleted_at`), also for users with history: devices revoked,
  locks released, name and limit slot freed; history keeps pointing at the old user row.
- Users without a password (admin-enrolled) set one with `PUT /v1/me/password` and their token; changing it needs
  the current password.
- Failed attempts: 5 per user name / 15 min, 50 in total / hour (in memory, reset on restart).

## Before the beta (agreed 2026-10-27)
- Auto-NEW: processing stores NEW (capture's user/device, no tags) for a candidate without a decision that matches
  no live recipe (`CandidateService.hasMatch`: identity key, URL or similar title); otherwise `needs_decision`.
  Switch: `recipedealer.processing.auto-new` (off in tests, `AutoNewTest` runs its own service).
- Web photos: `WebImageService` downloads live heads' `images[0]` into the blob store (`web_image`, one row per
  URL; poll every minute with the job worker; 404 / not an image / too large / private address = given up, other
  failures retried 1 h, 4 h, 16 h, … up to 5 attempts). `thumbnail_blob` = that copy, else the newest capture
  photo of the recipe. Never fetch from private addresses (`allow-private-addresses` is for tests only).
- PDF photos (2026-10-28): `PdfImageService` (PDFBox, poll every minute with the job worker) searches every PDF
  attachment once: the largest image (shorter edge ≥ 200 px, stencil masks ignored, images in forms included) of the
  first page that has one, pages in order; no image → no photo, pages are never rendered. Stored as JPEG ≤ 1600 px
  in `pdf_image` (one row per attachment, also for "none" / damaged) and used like a photo attachment for the
  thumbnail.
- Dish photos from photos / screenshots (2026-10-28): the LLM step also returns `dishPhoto` (image number from the
  labels "Image N, name:", box in thousandths of that image, not pixels: the API may downscale internally);
  `DishPhotoCropper` cuts it out of the original attachment (tiles mapped back), JPEG ≤ 1600 px, shorter edge ≥
  200 px, into `dish_photo` (one row per LLM run that saw images; no blob = no dish). The first box is rough
  (tested on cookbook photos: often too tall or cut), so the same model takes a second look
  (`RecipePrompt.DISH_PHOTO_PROMPT`, the box + 50 % margin on each side) and gives the exact edges; its usage is added
  to the run; if it fails, the first box stays (not turned); a refined box far from the first one is ignored.
  Rotation (pages photographed sideways or upside down): the second look also gets the whole page turned 0/90/180/270°
  (A–D, 768 px) and picks the version whose text reads normally. Asked for the rotation in degrees or for the
  direction of the letters instead, Haiku and Sonnet both got sideways pages wrong (Sonnet: 2 of 2 on one page). The newest succeeded (non-comparison) LLM run of a capture
  that found a dish gives the cut-out; a later run that finds none keeps it (user, 2026-10-28); no run found one →
  no thumbnail (a photo of only text), refreshed after every such run even without a new revision; captures no LLM
  run judged (heuristic) keep the raw attachment.
- Photos in the content (user, 2026-10-28: a new reading must not lose the photo): LLM answers have no `images`, so a
  single candidate keeps the capture's own `recipe.images` (the page photo; `ProcessingService.candidates`), and a
  machine revision without `images` keeps the head's (`RevisionService.addRevision`).
- Capture deletion (user, 2026-10-28; e.g. an invoice shared by mistake): `DELETE /v1/captures/{id}`
  (`CaptureDeletionService`) removes a capture that never became a recipe completely (rows, runs, decisions, and the
  blobs nothing else uses; files after the commit). 409 `capture_applied` / `extraction_in_progress`. The capture
  tables stay immutable: V15 allows `DELETE` only in a transaction with `set_config('rd.delete_capture', 'on', true)`.
  A backup running at that moment can fail on the vanished file; run it again.
- `DB_PASSWORD` stays as it is (Postgres is not published); never publish port 5432.

## Tags (agreed 2026-10-25)
- Two levels `category:tag`; each level has a `name` (stable readable id, `[a-z0-9-]`, e.g. `meal:breakfast`) and
  a `title` (shown, e.g. "Posiłek" / "Śniadanie"). Catalog order = the user's order (`position`).
- Assignments (`recipe_tag`) are not recipe content: no revision, no edit lock. Changing them, or renaming / moving /
  deleting a tag in use, bumps `recipe.updated_at` so the recipe comes again in the sync feed with its new `tags`.
- Tags are assigned manually (also with a NEW decision), and by Haiku for new recipes the user did not tag (below);
  no ingredient tags (ingredient search covers that).
- Recipe merge does not exist yet; when it comes, the target gets the union of both recipes' tags.

## Tagging of new recipes (agreed 2026-11-03)
- Every NEW decision without tags (auto-NEW, the user's NEW without `tags`, manual captures) requests a tagging
  (`AutoTagRepository.request` in `ResolutionService.applyDecision`, row in `auto_tagging`); a NEW with tags chosen by
  the user, UPDATEs, forced re-reads of applied captures and existing recipes are never tagged automatically.
- Only tags of the catalog (schema enum of the ids; `TagPrompt`); none is a valid answer. `t2` (user, 2026-11-03,
  after `t1` over-tagged on production: 11 tags on guacamole, "American" for an American website): fewer tags,
  usually one per category, cuisine of the dish not of the site, same-titled tags read with their category. Input: the head
  content as text (title, servings, times, ingredients, steps, notes), no photos. Model / effort:
  `recipedealer.tagging` (Haiku, `high`; user, 2026-11-03). Compared on 5 web recipes with `t2`: `low` $0.00038 /
  1.9 s per recipe with 4 wrong tags (a cheesecake tagged soup and no-bake), `medium` $0.00051 / 3.2 s and `high`
  $0.00057 / 3.6 s without wrong tags (`high` adds obvious meal tags `medium` sometimes skips).
- Before `done`: a pending tagging keeps the capture `processing` (`ResolutionService.updateState`); the tagging job
  (`process_capture` job with step `tagging`, so `hasActive` counts it; `JobWorker` dispatches to `AutoTagger`) calls
  the model outside a transaction, assigns the tags only if the recipe is live and still untagged, then
  `refreshState`. Any LLM failure only leaves the recipe untagged (no job retry).
- Not requested without an API key or with an empty catalog, so captures never wait for nothing. Off in the shared test
  context (`IntegrationTest` properties); `AutoTagTest` turns it on with `@TestPropertySource`.
- Usage per tagging on the row; `extraction-stats` lists it as a run with step `tagging`.

## Recipe photos from the phone (agreed 2026-10-24 option a, built 2026-11-03)
- `POST /v1/blobs` (part `image`, `PhotoService`): whatever is sent is re-encoded as JPEG ≤ 1600 px on the longer edge
  (user, 2026-11-03: camera photos of several MB must not fill the storage); huge images are decoded subsampled.
  Row in `user_photo`. EXIF orientation is not applied (the app sends the photo upright).
- The recipe references it in its content: `images[0] = "blob:<sha256>"`, saved as a normal USER revision (history,
  edit lock). Not a `data:` URI (rejected: bloats every sync). A revision naming an unknown `blob:` is 400
  `unknown_photo`. Thumbnail order: user photo, then `web_image`, then capture photo (`RecipeRepository`).
- A capture may carry the ref in `recipe.images[0]` too (the app's manual entry / corrected preview); the new recipe
  keeps it like any page photo. Not validated at upload: an unknown ref simply gives no thumbnail.
- Unused photos (no revision, candidate, capture or recipe thumbnail names them) are deleted after 7 days by
  `PhotoCleanupPoller` (daily 04:30, `recipedealer.photos.unused-grace`), files after the commit; older revisions
  keep theirs so history can be restored.

## Translation of new recipes (agreed 2026-11-03)
- The app sends header `Translate-To: <device language>` on the upload when its setting is on; stored in
  `capture.translate_to` on insert (the capture contract stays as it is). Every machine revision such a capture makes
  the head (NEW, also decided on another device; UPDATE; re-read via `applyAutomatic`; user, 2026-11-03: a re-read
  must not undo the translation) requests a translation (`AutoTranslationRepository.request`). Only when the content's
  `language` is known and differs: never content already in the target language or without a language (user,
  2026-11-03: a model can only spoil it); `AutoTranslator` checks the head's language again before calling.
  Only a NEW recipe is tagged afterwards (`tag_after`).
- The translation is a MACHINE revision (head; the original stays in the history; `run_id` = the head's run, as the
  schema requires one for machine revisions), skipped when the head is a USER revision or changed meanwhile.
- `TranslatePrompt` (version `r2`): texts only (title, servings text, group names, items, notes); quantities as
  written, only fixed-factor units (°F, oz, lb, fl oz, pint, quart, gallon, inch) converted to metric with the original
  in parentheses; cups / spoons never (user, 2026-11-03: no guessing, only arithmetic). `r2`: a metric value the source
  already gives is kept alone (`r1` wrote "177°C (350°F; 175°C)" for "350°F (175°C)"); not tested on production. An answer with a different
  number of groups or items is rejected (`invalid_output`). Haiku, effort `high` (`recipedealer.translation`).
- Order: translation job (step `translation`), then the tagging request (tags from the translated text); a pending
  translation keeps the capture `processing` like a pending tagging. A failed translation still tags.
- Usage per translation in `auto_translation`, `extraction-stats` step `translation`.

## LLM extraction (agreed 2026-10-28; task prompt `docs/add-llm-prompt.txt`)
- Cascade heuristic (`PassthroughExtractor`) → Claude Haiku → Claude Sonnet (`ExtractionCascade`, steps
  `heuristic | haiku | sonnet`); the next step runs only when the previous one fails validation
  (`ExtractionValidator`: title, ≥1 ingredient, ≥1 step, schema-conforming output, `stop_reason` not `max_tokens` /
  `refusal`, `recipeFound` not false). The same validation applies to the heuristic: a partial result goes to Haiku.
- One job = one step = one `processing_run` (purpose `pipeline | forced | comparison`, `cascade_id` groups a pass);
  the next step is enqueued in the transaction that finishes the previous one. A run failing validation is
  `rejected` (candidates kept, `needs_review` true). All steps fail → the best non-empty run of the cascade is
  applied (promoted to `succeeded`, later steps win ties); all empty → `AWAITING_EXTRACTION`. A forced cascade
  without a valid result never replaces what a capture already applied.
- Transient API errors (5xx, 529, 429, timeouts) retry the same step (SDK `max-retries` 2, then job backoff); auth /
  permission / credit (401/402/403) end the job at once (`NonRetryableProcessingException`, capture
  `needs_attention` / `LLM_UNAVAILABLE`); other 4xx → `LLM_REQUEST_REJECTED`. SDK timeout 150 s × 3 < lease 10 min.
- Recipes without an ingredient list (ingredients only named in the steps): Haiku returns no ingredients and the
  cascade escalates to Sonnet, which lists them. Do not add a prompt rule to derive ingredients from the steps
  (user, 2026-10-28: it would push the model to make ingredients up); Sonnet handles these.
- Refusals: no server-side refusal fallback (`fallbacks` param); a refusal is a validation failure
  (`refusal:<category>`) and escalates.
- SDK `com.anthropic:anthropic-java` (3.3.0) behind `ClaudeClient` (`AnthropicClaudeClient`; tests use a stub, the
  real API only in the opt-in `ClaudeApiTest` with `CLAUDE_IT=true`). It uses Jackson 2 (Boot-managed 2.21.x) next
  to Jackson 3. SDK 3.x sends the key as `Authorization: Bearer`.
- Input text also carries the phone's own recipe fields (`LlmInputBuilder.phoneRecipe`, 2026-11-03): Readability
  leaves the recipe card out on some sites (allrecipes: no steps in `readableText`), so a re-read found nothing.
- Request: static English system prompt (`RecipePrompt.SYSTEM_PROMPT`, version `p5` in the extractor version) with
  `cache_control` ephemeral on it, no top-level caching; structured output schema (internal, from `$defs/recipe`,
  no `images`, plus `recipeFound` and `dishPhoto`); effort `medium` for both models, thinking left at the model default; max_tokens
  16000. Input: attachments (images, PDFs base64) then the phone's text (`raw.text`, `pdfText`, `ocr.text`,
  `web.readableText`). Images > 2000 px are downscaled, tall screenshots cut into 2000 px tiles; a PDF that does not
  fit into one request (~23 MiB) is left out (no Files API).
- LLM output has provenance stage `llm`, a weak stage in `ReviewPolicy`: every LLM result `needsReview`.
- `POST /v1/captures/{captureId}/llm-extraction`: any device forces the cascade from Haiku (no limits for now).
- Usage per run on `processing_run` (model, stop_reason, tokens incl. cache, latency, `cost_usd` with the configured
  prices at finish time; Haiku 5.5 has long-context prices above 100k prompt tokens). Per recipe:
  `GET /v1/recipes/{recipeId}/extraction-stats`, aggregated from the run rows on every request.
- Admin: comparison runs (`/v1/admin/llm-comparisons`) are never applied and never change `capture_state`;
  `latestCompletedRun` ignores them. Reprocessing: `/v1/admin/llm-extractions` (capture ids or a state reason).
- API key dead man's switch: daily `max_tokens: 0` request to Haiku (`ApiKeyCheck`, own schedule, not the job
  queue), recorded in `api_key_check`, pings `DEADMAN_API_KEY_URL` only on success; `POST /v1/admin/api-key-check`
  runs it now. The provider's `/fail` endpoint is not used.
- Unit tests that scale images need `java.awt.headless=true` (set in the surefire configuration); without it they
  hang on macOS.

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
