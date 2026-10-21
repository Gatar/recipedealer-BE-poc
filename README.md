# RecipeDealer backend (PoC)

Backend for RecipeDealer, a private recipe system for one household. Android devices (phones and a kitchen
tablet) send recipe *captures* (a structured recipe extracted on the phone plus all raw material); the backend
stores them forever, processes them asynchronously into versioned recipes, and serves browsing, sync, search
and editing.

## Status

Work in progress. Milestones:

0. Repository setup
1. Skeleton, database, Testcontainers, health endpoint
2. Authentication and device enrollment
3. Capture ingest and blob store
4. Job queue, passthrough processing, candidates, resolution
5. Read, sync, search, blobs
6. Edit locks and user revisions
7. Deployment files and CI/CD

## Architecture in short

- **Captures** are immutable raw input (original JSON bytes and attachments kept as content-addressed blobs),
  validated against the capture JSON Schema (`schemaVersion: 1`) shared with the Android app.
- **Processing runs** turn a capture into recipe candidates through a pluggable `RecipeExtractor`
  (v1: passthrough of the phone's recipe; phase 2: LLM/vision extractor behind the same interface).
  Jobs live in a PostgreSQL table (`FOR UPDATE SKIP LOCKED`, retries with backoff).
- **Recipes** have identity keys (normalized canonical URL, JSON-LD id) and immutable **revisions**
  (`MACHINE` or `USER` origin). Automatic changes never override a user's edit.
- **Deduplication** is decided by the user (NEW recipe or UPDATE of an existing one) based on candidates
  ranked by URL match and trigram title similarity.
- **Editing** is online only, guarded by a 5-minute edit lease and `If-Match` on the head revision.

## Stack

Java 25, Spring Boot 4.1, Maven, PostgreSQL 18 (`pg_trgm`, `unaccent`), Spring `JdbcClient` with plain SQL,
Flyway, JUnit 5 + Testcontainers. Deployed with Docker Compose (app, PostgreSQL, Caddy with automatic HTTPS)
on a single ARM64 VPS.

Build, run, deployment and backup instructions will be added as the milestones land.
