#!/usr/bin/env bash
# Nightly PostgreSQL dump into /srv/recipedealer/dumps (keeps the last 7), then pings the dead-man's switch
# (DEADMAN_URL in .env, optional). Scheduled by /etc/cron.d/recipedealer-backup, before the Hetzner backup window.
# Blobs are not in the dump: they live in /srv/recipedealer/blobs and are covered by the Hetzner server backup.
set -euo pipefail

APP_DIR=/srv/recipedealer/app
DUMP_DIR=/srv/recipedealer/dumps
KEEP=7

cd "$APP_DIR"
timestamp=$(date -u +%Y%m%dT%H%M%SZ)
name="recipedealer-$timestamp.dump"

# /dumps inside the postgres container is DUMP_DIR on the host. Write to a temp name, rename when complete.
docker compose exec -T postgres pg_dump -U recipedealer -d recipedealer --format=custom --file="/dumps/$name.partial"
mv "$DUMP_DIR/$name.partial" "$DUMP_DIR/$name"
echo "$(date -u +%FT%TZ) wrote $DUMP_DIR/$name ($(du -h "$DUMP_DIR/$name" | cut -f1))"

ls -1t "$DUMP_DIR"/recipedealer-*.dump | tail -n +$((KEEP + 1)) | xargs -r rm -f --
rm -f "$DUMP_DIR"/*.partial

deadman_url=$(grep -E '^DEADMAN_URL=' .env 2>/dev/null | cut -d= -f2- | tr -d "'\"" || true)
if [[ -n "$deadman_url" ]]; then
    curl -fsS -m 10 --retry 3 "$deadman_url" > /dev/null
    echo "$(date -u +%FT%TZ) pinged dead-man's switch"
fi
