# RecipeDealer API (v1)

The reference for every HTTP endpoint of the backend. The Android client is `api/BackendClient.kt` in
`RecipeDealerAndroid`.

**Keeping it current:** every endpoint has a `### METHOD /path` heading below, spelled exactly like its Spring
mapping (path variable names included). `ApiDocumentationTest` fails the build when an endpoint exists in the code
but not here, or the other way round. A change to a request, a response or an error code updates this file in the
same commit.

## Conventions

- **Base URL:** `https://<server>` (Caddy terminates TLS and compresses responses).
- **Authentication:**
  - `/v1/admin/**`: HTTP Basic with the admin credentials (`RECIPEDEALER_ADMIN_USERNAME` / `_PASSWORD_HASH`).
    After 5 failed admin logins within an hour every admin call answers 429 `too_many_requests` until the limit
    refills (one attempt every 12 minutes).
  - `/v1/auth/**` (registration, login): no authentication.
  - Everything else: `Authorization: Bearer <device token>`. A token comes from registration, login or an admin
    enrollment and does not expire. A missing, unknown or revoked token answers 401 `unauthorized`; the app then
    asks the user to log in again.
- **JSON:** UTF-8, camelCase fields. Times are ISO-8601 UTC (`2026-10-26T16:30:00.123456Z`). IDs are UUIDs;
  server-generated ones are UUIDv7. Fields that have no value are `null`, except where marked *omitted when empty*.
- **Errors:** `{"error": "<code>", "details": ["<message>", …]}`. The code is stable and meant for the client; the
  details are English text for people. Common codes:

  | Status | `error` | When |
  |---|---|---|
  | 400 | `invalid_request` | Malformed JSON or multipart, invalid parameter or field |
  | 401 | `unauthorized` | No valid device token / admin credentials |
  | 403 | *(endpoint-specific)* | Authenticated, but not allowed, see the endpoint |
  | 404 | `not_found` | The resource does not exist. A 404 **without** this body means the route is missing (older backend) |
  | 409 | *(endpoint-specific)* | Conflict, see the endpoint |
  | 410 | `recipe_gone`, `recipe_deleted`, `recipe_merged` | The recipe was deleted or merged |
  | 413 | `payload_too_large` | Over 25 MiB per file or 50 MiB per request |
  | 429 | `too_many_requests` | Admin or login rate limit |

- **ETags:** a recipe's `ETag` is its head revision id, a blob's is its SHA-256. Caddy's gzip turns the header into
  a weak `W/"…"` tag; the server accepts both forms, and the app builds `If-Match` from `headRevisionId` rather than
  from the header.

## Shared objects

**Recipe** (`recipe` in recipe views, revisions and the sync feed; the body of an edit): the `recipe` object of the
capture contract, `$defs/recipe` in [`capture-v1.json`](../src/main/resources/schema/capture-v1.json). Edits are
validated against it.

**RecipeView** (`GET /v1/recipes/{recipeId}`, saving an edit):

```json
{
  "recipeId": "…", "headRevisionId": "…", "origin": "MACHINE | USER", "needsReview": false,
  "recipe": { … }, "sourceSummary": "kwestiasmaku.com", "thumbnailBlob": "<sha256> | null",
  "urls": ["https://…"], "tags": ["meal:dinner"],
  "createdAt": "…", "updatedAt": "…",
  "lock": {"lockedBy": "Ania (Pixel 9)", "expiresAt": "…"}
}
```

`lock` is `null` unless someone holds the edit lease. `tags` are tag ids in catalog order.

`thumbnailBlob` is the server's copy of the recipe's photo:

- the photo `recipe.images[0]` (an http(s) URL), downloaded by the server in the background within about a minute
  after it becomes the head's photo; until then, or if the download fails, the next option applies;
- else the newest photo of a capture behind the recipe:
  - for photos and screenshots read by the LLM extraction: the photo of the dish the model found in them, cut out of
    the original and turned upright; none when they show no dish (only the recipe text);
  - for photos and screenshots no LLM read (the phone's result was complete): the attachment itself;
  - for a PDF attachment: the largest image of the first page that has one, found by the server in the background
    within about a minute; none when the PDF has no images;
- else `null`.

A recipe whose thumbnail arrives later comes again in the sync feed.

**CaptureView** (all capture endpoints):

```json
{
  "captureId": "…", "status": "accepted | processing | needs_decision | needs_attention | done",
  "reason": "AWAITING_EXTRACTION | PROCESSING_FAILED | LLM_UNAVAILABLE | LLM_REQUEST_REJECTED | TARGET_GONE | null",
  "message": "… | null",
  "recipes": [{"recipeId": "…", "title": "…"}],
  "candidates": [{"index": 0, "title": "…", "ingredientCount": 9, "stepCount": 5, "needsReview": false,
                  "urls": ["https://…"], "resolution": "NEW | UPDATE", "targetRecipeId": "…"}],
  "receivedAt": "…", "capturedAt": "…", "sourceType": "…", "sourceUrl": "…", "title": "…"
}
```

- `recipes`: the recipes this capture created or updated.
- `candidates`: the extracted recipes. `resolution` and `targetRecipeId` are *omitted when empty* (no decision yet).
- `reason`:
  - `AWAITING_EXTRACTION`: nothing could be extracted yet.
  - `PROCESSING_FAILED`: processing failed after its retries.
  - `LLM_UNAVAILABLE`: automatic extraction could not run (the server's Claude API key is missing or invalid, or the
    account has no credit). Not retried; force extraction again once it is fixed.
  - `LLM_REQUEST_REJECTED`: the Claude API refused this capture's request (e.g. an attachment it cannot read).
  - `TARGET_GONE`: the recipe chosen with UPDATE was deleted; decide again.

**Extraction cascade.** Processing tries the steps in order and stops at the first result that passes validation
(title, at least one ingredient and one step; for LLM steps also a complete, schema-conforming answer):
the phone's own result (`heuristic`), then Claude Haiku (`haiku`), then Claude Sonnet (`sonnet`). LLM steps read the
capture's attachments (photos, screenshots, PDFs) and the text the phone read (`raw`). When no step passes, the best
non-empty result is used and marked `needsReview`; when every result is empty, the capture waits with
`AWAITING_EXTRACTION`. LLM results always need review. Fields the user edited on the phone (provenance `user`) are
never changed. Without an API key on the server the cascade ends after the heuristic.

**Tag catalog** (`GET /v1/tags` and every catalog change):

```json
{"categories": [{"name": "meal", "title": "Posiłek",
                 "tags": [{"id": "meal:breakfast", "name": "breakfast", "title": "Śniadanie", "recipeCount": 3}]}]}
```

Names are `[a-z0-9]` words joined by single hyphens, at most 40 characters; titles are at most 60 characters.
Categories and tags are in the user's order.

**BackupInfo** (backup endpoints):

```json
{
  "name": "recipedealer-20261026T163000Z-core.zip", "sizeBytes": 1234567,
  "manifest": {"format": 1, "createdAt": "…", "label": "core", "schemaVersion": "7", "recipes": 42,
               "deletedRecipes": 1, "revisions": 60, "captures": 45, "blobs": 130, "blobBytes": 98765432,
               "dumpSha256": "…"}
}
```

`schemaVersion` is the last applied Flyway migration. `label` is `null` without a label; `manifest` is *omitted*
for a ZIP whose manifest cannot be read.

## Accounts

A user has a unique name (case is ignored) and a password. A device is identified by its user and its name (case is
ignored): registering or logging in on a new device name adds a device; logging in again with the same name keeps the
device (and its history) and replaces its token, so the previous token stops working. Passwords have no rules
except: not empty, at most 72 bytes in UTF-8 (a bcrypt limit).

Rate limit for failed attempts (wrong password, wrong current password, wrong registration code):
- 5 per user name within 15 minutes; failed registrations share one budget;
- 50 in total within an hour.

While a limit is exhausted, the request answers 429 `too_many_requests`, even with the right password.

**Session** (registration and login):

```json
{"token": "rd_…", "userId": "…", "deviceId": "…", "userName": "Ania"}
```

The token is shown only in this response; the server keeps only its hash. `userName` is spelled as stored.

### POST /v1/auth/register

Creates a user with the first device. No token needed.

```json
{"userName": "Ania", "password": "…", "deviceName": "Pixel 9", "registrationCode": "…"}
```

- `userName`, `deviceName`: 1–100 characters.
- `registrationCode`: the household's code, `REGISTRATION_CODE` in the server's `.env`.
- At most 10 users in total (`MAX_USERS`), counting users enrolled by the admin, who is not limited.

Responses:

- **201** Session.
- **400** `invalid_request`: missing field, empty or too long password.
- **403** `registration_disabled`: the server has no registration code.
- **403** `invalid_registration_code`.
- **409** `user_exists`: also for a user enrolled by the admin; that user sets a password with `PUT /v1/me/password`.
- **409** `user_limit_reached`.
- **429** `too_many_requests`.

### POST /v1/auth/login

A new device token for an existing user, e.g. after reinstalling the app or on a new device. With a device name the
user already has, it replaces that device's token (and lifts a revocation); tokens of the user's other devices stay
valid. No token needed.

```json
{"userName": "Ania", "password": "…", "deviceName": "Pixel 9"}
```

- **201** Session.
- **400** `invalid_request`: missing field.
- **401** `invalid_credentials`: same answer for a wrong password, an unknown or deleted user and a user without a
  password.
- **429** `too_many_requests`.

### PUT /v1/me/password

Sets or changes the password of the token's user. This is how a user enrolled by the admin gets a password.

```json
{"password": "new", "currentPassword": "old"}
```

`currentPassword` is required once the user has a password.

- **204**.
- **400** `invalid_request`.
- **401** `unauthorized`: no valid token.
- **403** `invalid_credentials`: wrong or missing `currentPassword`.
- **429** `too_many_requests`.

## Captures

### PUT /v1/captures/{captureId}

Uploads a capture; `captureId` is generated on the phone and must equal `captureId` in the JSON. Idempotent.

- Body: `multipart/form-data` with
  - part `capture`: the capture JSON (`capture-v1.json`);
  - one part per attachment, named like that attachment's `attachments[].name`.
- **201** CaptureView: stored now, processing starts.
- **200** CaptureView: already stored; the stored capture wins and the new body is ignored.
- **400** `invalid_capture`: schema errors, a missing or duplicate part, or a URL/body `captureId` mismatch.
  `details` lists every problem. The phone shows them and does not retry.
- **413** `payload_too_large`.

### GET /v1/captures/{captureId}

The capture's processing state. **200** CaptureView · **404**.

### GET /v1/captures

The inbox. Query `status`: comma-separated statuses, default `needs_decision,needs_attention`.

- **200** `[CaptureView]`: at most 200, newest first.
- **400**: unknown status.

### PUT /v1/captures/{captureId}/extracted/{index}/resolution

The user's decision for candidate `index` (0-based). Idempotent. Allowed before processing finishes: the decision is
stored and applied when the candidate exists.

Processing decides NEW by itself (in the name of the capture's device, without tags) for a candidate without a
decision that matches no live recipe: no shared identity key (canonical URL, JSON-LD id), and nothing that
`GET /v1/recipes/candidates` would list for its title and, with a single candidate, the capture's URL. A capture
whose candidates are all decided goes to `done`; `needs_decision` means a real choice is left.

Body is one of:

- `{"action": "NEW", "tags": ["meal:dinner"]}`: a new recipe; `tags` is optional.
- `{"action": "UPDATE", "recipeId": "…"}`: becomes a revision of that recipe. A revision never moves a head the user
  wrote; it then stays a proposal.

Responses:

- **200** CaptureView.
- **400** `invalid_request`: bad index, `action` or `recipeId` combination, or tags with UPDATE.
- **400** `unknown_tag`.
- **404**: the capture does not exist, or processing finished without candidate `index`.
- **409** `already_resolved`: a different decision was already stored.
- **409** `recipe_gone`: the UPDATE target does not exist or was deleted.

### POST /v1/captures/{captureId}/llm-extraction

Runs the extraction cascade again, starting at the first LLM step (Claude Haiku). No body. The app reaches it from a
recipe through `captureId` of its revisions, or from a stuck capture in the inbox.

The result takes the normal path: a capture that already updated a recipe updates it again (a new revision; it never
moves a head the user wrote, it then stays a proposal); a stored decision is applied; otherwise the user decides. If
no LLM step finds a complete recipe for a capture that was already applied, nothing changes.

- **202** CaptureView, now `processing`.
- **404** `not_found`.
- **409** `extraction_in_progress`: the capture is being processed (a job is queued or running).
- **409** `no_llm_input`: the capture has no attachment or text an LLM could read.
- **503** `llm_unavailable`: the server has no Claude API key.

## Recipes

### GET /v1/recipes/candidates

Possible existing matches for a new capture; the client uses them before asking NEW or UPDATE. Query: `title`
and/or `url` (both optional).

**200** `[{recipeId, title, score, matchReason, sourceSummary, thumbnailBlob, updatedAt}]`, at most 10:

- exact normalized canonical URL first, with `matchReason` `URL` and `score` 1.0;
- then similar titles (trigram similarity), with `matchReason` `TITLE`.

### GET /v1/recipes

Sync feed for the offline cache. Query parameters:

- `updatedSince` (ISO time): only for the first page of an incremental sync without a cursor.
- `cursor`: from the previous page.
- `limit`: default 100, clamped to 1–500.

**200** `{items, nextCursor, hasMore}`. Each item is one of:

- a live recipe: `{recipeId, updatedAt, deleted: false, headRevisionId, needsReview, recipe, sourceSummary,
  thumbnailBlob, tags}`;
- a tombstone: `{recipeId, updatedAt, deleted: true}`, or `{recipeId, updatedAt, deleted: false, mergedInto}`.
  Content fields are omitted.

Items are ordered by `updatedAt`, then `recipeId`. Keep `nextCursor` for the next sync, and ask again at once while
`hasMore` is true. Changes from transactions still open on the server come in a later sync, so the cursor never
skips them. **400** `invalid_request`: bad cursor.

### GET /v1/recipes/search

Query `q`: matches titles and ingredients, tolerating missing Polish diacritics and typos.

**200** `[{recipeId, title, score, sourceSummary, thumbnailBlob, updatedAt}]`: best first, at most 30, live recipes
only.

### GET /v1/recipes/{recipeId}

The head revision.

- **200** RecipeView with `ETag: "<headRevisionId>"`.
- **304** when `If-None-Match` matches the head.
- **404**.
- **410** `recipe_deleted` / `recipe_merged`; for a merge, `details` names the target.

### GET /v1/recipes/{recipeId}/revisions

All revisions, newest first:

```json
[{"revisionId": "…", "parentId": "…", "head": true, "origin": "MACHINE | USER", "needsReview": false,
  "runId": "…", "captureId": "…", "userName": "Ania", "contentHash": "…", "createdAt": "…", "recipe": { … }}]
```

Fields without a value (`parentId`, `runId`, `captureId`, `userName`) are omitted. A non-head revision newer than the
head is a proposal: a machine update the user has not accepted. **200** · **404**.

### GET /v1/recipes/{recipeId}/extraction-stats

Extraction usage of the recipe ("Statystyki dla nerdów"): every processing run of the captures behind the recipe
(the captures of its revisions and decisions), including rejected cascade steps, forced runs and admin comparisons.
Heuristic runs are listed with zero usage. Totals are computed from the runs on every request.

```json
{
  "recipeId": "…",
  "totals": {"runs": 3, "llmRuns": 2, "inputTokens": 2100, "cacheCreationInputTokens": 0,
             "cacheReadInputTokens": 1000, "outputTokens": 350, "costUsd": 0.007135},
  "models": [{"model": "claude-haiku-5-5", "runs": 1, "inputTokens": 100, "cacheCreationInputTokens": 0,
              "cacheReadInputTokens": 0, "outputTokens": 50, "costUsd": 0.000035}],
  "runs": [{"runId": "…", "captureId": "…", "startedAt": "…", "finishedAt": "…",
            "extractor": "passthrough | claude", "extractorVersion": "1 | claude-haiku-5-5/p5",
            "step": "heuristic | haiku | sonnet", "purpose": "pipeline | forced | comparison",
            "model": "claude-haiku-5-5 | null", "status": "succeeded | rejected | awaiting_extraction | failed | running",
            "applied": false, "validationErrors": ["no_steps"], "errorType": "overloaded_error | null",
            "stopReason": "end_turn | max_tokens | refusal | null", "inputTokens": 100,
            "cacheCreationInputTokens": 0, "cacheReadInputTokens": 0, "outputTokens": 50, "latencyMs": 2100,
            "costUsd": 0.000035}]
}
```

- `runs`: oldest first. `applied`: the run's result became a revision of this recipe. `status` `rejected`: the run
  failed validation (`validationErrors`), the next step ran. `failed`: the call failed (`errorType`), e.g. a transient
  error that was retried.
- `costUsd`: computed with the prices configured when the run finished; `null` in a run for a model without a price
  (counted as 0 in sums). `latencyMs` is `null` for heuristic runs.

**200** · **404** (also for a deleted or merged recipe).

### POST /v1/recipes/{recipeId}/revisions

Saves an edit as a USER revision. Headers:

- `Lock-Id`: the active edit lease from `POST …/lock`;
- `If-Match: "<headRevisionId>"`: the head the editor started from.

Body: the recipe object.

Success:

- **201** RecipeView with a new `ETag`: a new head; the lock is released. This includes unchanged content over a head
  that `needsReview`: it is saved as USER and confirms the head.
- **200** RecipeView: the content equals the head; nothing is stored.

Errors:

- **400** `invalid_recipe`: schema errors in `details`.
- **404**.
- **409** `lock_required`: no `Lock-Id`.
- **409** `lock_expired`: the lease is missing or expired.
- **409** `locked` with `{"error": "locked", "lockedBy", "expiresAt"}`: someone else holds the lease.
- **410** `recipe_gone`.
- **412** `head_changed`: the head moved since the editor opened it.
- **428** `if_match_required`.

### DELETE /v1/recipes/{recipeId}

Soft delete. The recipe becomes a tombstone in the sync feed, and its URL and JSON-LD keys are freed for new
recipes. Needs `Lock-Id`; `If-Match` is optional and checked when present.

- **204**, also when the recipe was already deleted.
- **404**.
- **409** `lock_required` / `lock_expired` / `locked`.
- **412** `head_changed`.

## Edit lock

A 5-minute lease per recipe: acquire it when the editor opens, heartbeat every 2 minutes, release it on cancel.
Saving a revision consumes it.

### POST /v1/recipes/{recipeId}/lock

- **201** `{lockId, expiresAt}`.
- **404**.
- **409** `{"error": "locked", "lockedBy": "Ania (Pixel 9)", "expiresAt": "…"}`.
- **410** `recipe_gone`.

### PUT /v1/recipes/{recipeId}/lock/{lockId}

Heartbeat; extends the lease by 5 minutes.

- **200** `{lockId, expiresAt}`.
- **409** `lock_expired` / `locked`.

### DELETE /v1/recipes/{recipeId}/lock/{lockId}

Releases the lease. **204**, also when it had already expired.

## Tags

Tag ids are `category:tag` (e.g. `meal:breakfast`). Assigning tags is not recipe content: no lock and no revision,
but the recipe comes again in the sync feed with its new `tags`. Renaming, moving or deleting a tag in use does the
same for every recipe that has it.

### GET /v1/tags

**200** the tag catalog, with `recipeCount` per tag (live recipes).

### POST /v1/tags/categories

Body `{"name"?, "title", "position"?}`: `name` is derived from the title when missing; `position` is 0-based, the
end by default.

- **200** the whole catalog.
- **400** `invalid_request`.
- **409** `tag_exists`.

### PATCH /v1/tags/categories/{category}

Body `{"name"?, "title"?, "position"?}`; missing fields stay unchanged. A rename changes the ids of all its tags.

- **200** the catalog.
- **400**.
- **404** `not_found`.
- **409** `tag_exists`.

### DELETE /v1/tags/categories/{category}

Deletes the category with its tags and removes them from recipes. **200** the catalog · **404**.

### POST /v1/tags/categories/{category}/tags

Body `{"name"?, "title", "position"?}`.

- **200** the catalog.
- **400**.
- **404**: unknown category.
- **409** `tag_exists`.

### PATCH /v1/tags/categories/{category}/tags/{tag}

Body `{"name"?, "title"?, "category"?, "position"?}`. `category` moves the tag to another category; `position` is
then its place there.

- **200** the catalog.
- **400**.
- **404**.
- **409** `tag_exists`.

### DELETE /v1/tags/categories/{category}/tags/{tag}

Deletes the tag and removes it from recipes. **200** the catalog · **404**.

### PUT /v1/recipes/{recipeId}/tags

Body `{"tags": ["meal:dinner", …]}` replaces the recipe's tags.

- **200** `{"tags": […]}`, in catalog order.
- **400** `unknown_tag`.
- **404**.
- **410** `recipe_gone`.

## Blobs and health

### GET /v1/blobs/{sha256}

A capture body or attachment by its SHA-256 (e.g. `thumbnailBlob`).

- **200** the content, with the attachment's media type (`application/json` for a capture body) and
  `Cache-Control: private, max-age=31536000, immutable`.
- **304** with `If-None-Match`.
- **404**.

### GET /v1/health

**200** `{"status": "ok"}`. It needs a valid token, so the app uses it to check both the base URL and the token.

## Admin

HTTP Basic with the admin credentials; see Conventions for the rate limit.

### POST /v1/admin/enrollments

Body `{"userName": "Ania", "deviceName": "Pixel 9"}` (each 1–100 characters). The user is created when the name is
new; shared devices are users too (e.g. `"userName": "Tablet"`). It works without a password and beyond the user
limit; the user can set a password later with `PUT /v1/me/password`. A device name the user already has replaces that
device's token, as a login does.

- **201** `{token, userId, deviceId}`. The token is shown only this once.
- **400** `invalid_request`.

### GET /v1/admin/devices

**200** `[{deviceId, deviceName, userId, userName, createdAt, lastUsedAt, revokedAt}]`: devices of users that are not
deleted.

### DELETE /v1/admin/devices/{deviceId}

Revokes the device token; the user can log in on that device again. **204** · **404**.

### DELETE /v1/admin/users/{userId}

Deletes a user, also one with history:
- all its device tokens are revoked, its password is removed and its edit locks are released;
- its captures, decisions and revisions stay and still show its name;
- the name and the slot in the user limit are free, so the same name can register again as a new user.

**204**, also when already deleted · **404**.

### POST /v1/admin/backups

Creates a full backup now: a ZIP with `database.dump` (`pg_dump --format=custom`), `blobs/` and `manifest.json`, in
the server's backup directory. Body (optional) `{"label": "core"}`: `[a-z0-9]` words joined by single hyphens, at
most 40 characters; it becomes part of the name. Takes as long as the dump and the copy of all blobs.

- **201** BackupInfo.
- **400** `invalid_request`: bad label.
- **409** `backup_running`: another backup is in progress.
- **409** `backup_exists`: same name within the same second.
- **500** `backup_failed`.

Restoring is not part of the API: `deploy/restore.sh` on the server (README, Restore).

### GET /v1/admin/backups

**200** `[BackupInfo]`, newest first.

### GET /v1/admin/backups/{name}

Downloads the ZIP: **200** `application/zip` with `Content-Disposition: attachment; filename="<name>"` · **404**.

### DELETE /v1/admin/backups/{name}

**204** · **404**.

### POST /v1/admin/llm-comparisons

Model comparison: runs the given LLM steps for each capture as comparison runs. They are stored for the report only:
never applied, they never change the capture's state, and later decisions and reprocessing ignore them.

```json
{"captureIds": ["…"], "steps": ["haiku", "sonnet"]}
```

- **202** `{"queued": [{"captureId": "…", "step": "haiku", "jobId": "…"}]}`; the runs follow within seconds.
- **400** `invalid_request`: empty lists, more than 200 captures, or an unknown step.
- **404** `not_found`: a capture does not exist (nothing is queued).
- **503** `llm_unavailable`.

### GET /v1/admin/llm-comparisons

Query `captureIds` (comma-separated). Every run of each capture side by side:

```json
[{"captureId": "…", "sourceType": "IMAGE", "status": "needs_attention", "reason": "AWAITING_EXTRACTION",
  "runs": [{"run": { … }, "cacheHit": true, "candidates": [{ "title": "…", … }]}]}]
```

`run` has the fields of a run in `GET /v1/recipes/{recipeId}/extraction-stats` (`applied`: the run became a revision
of any recipe); `candidates` are the extracted recipes. **200** · **404** `not_found`.

### POST /v1/admin/llm-extractions

Reprocessing: the cascade from the first LLM step, as `POST /v1/captures/{captureId}/llm-extraction`, for several
captures. Body: either `{"captureIds": ["…"]}` or `{"reason": "AWAITING_EXTRACTION"}` (every capture whose state has
that reason).

- **202** `{"queued": ["…"], "skipped": [{"captureId": "…", "error": "not_found | extraction_in_progress | no_llm_input", "message": "…"}]}`.
- **400** `invalid_request`: both or neither of `captureIds` and `reason`, or more than 200 captures.
- **503** `llm_unavailable`.

### POST /v1/admin/api-key-check

Runs the API key check now: a minimal Claude request (`max_tokens: 0`) that succeeds only if the key authenticates
and the account can serve requests. On success the dead man's switch URL (`DEADMAN_API_KEY_URL`) is pinged, as by
the daily scheduled check. Every check is recorded.

**200** `{"id": "…", "checkedAt": "…", "success": true, "model": "claude-haiku-5-5", "errorType": null,
"httpStatus": null, "inputTokens": 8, "outputTokens": 0, "costUsd": 0.000001, "latencyMs": 450, "pinged": true}`;
on failure `success: false` with `errorType` (e.g. `authentication_error`, `billing_error`, `no_api_key`) and
`httpStatus`.
