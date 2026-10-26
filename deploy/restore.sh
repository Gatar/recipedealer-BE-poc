#!/usr/bin/env bash
# Replaces the whole database with a backup and adds the backup's blobs. Run as root on the server.
#
#   restore.sh <backup> [--yes]
#
# <backup>: a ZIP from POST /v1/admin/backups (a path, or a name in $DATA_DIR/backups), or a bare pg_dump file
# (a nightly dump from $DATA_DIR/dumps, or a pre-restore dump this script wrote).
#
# Steps: check the backup, ask for confirmation, dump the current database to $DATA_DIR/dumps/pre-restore-<time>.dump
# (restore that file to undo), stop the app, recreate the database from the backup, copy the blobs that are missing
# (blobs are content-addressed, so existing files are kept; newer unreferenced blobs are harmless), start the app and
# wait until it answers. A backup from an older schema is migrated by Flyway when the app starts; a backup from a newer
# app version than the deployed one makes the app refuse to start.
set -euo pipefail

APP_DIR=${APP_DIR:-/srv/recipedealer/app}
DATA_DIR=${DATA_DIR:-/srv/recipedealer}
APP_UID=10001

die() {
    echo "restore: $*" >&2
    exit 1
}

[[ $# -ge 1 && $# -le 2 ]] || die "usage: restore.sh <backup.zip | backup name | file.dump> [--yes]"
[[ $# -eq 1 || "$2" == "--yes" ]] || die "unknown option $2"
backup=$1
if [[ ! -f "$backup" && -f "$DATA_DIR/backups/$backup" ]]; then
    backup="$DATA_DIR/backups/$backup"
fi
[[ -f "$backup" ]] || die "no such backup: $1"
backup=$(realpath "$backup")

cd "$APP_DIR"
pg() {
    docker compose exec -T postgres "$@"
}

stamp=$(date -u +%Y%m%dT%H%M%SZ)
# Under the dumps directory, which the postgres container sees as /dumps.
work_name="restore-$stamp"
work="$DATA_DIR/dumps/$work_name"
app_stopped=false
safety="pre-restore-$stamp.dump"
finish() {
    local status=$?
    rm -rf "$work"
    if [[ $status -ne 0 && $app_stopped == true ]]; then
        echo "restore: FAILED with the app stopped. Return to the previous state with:" >&2
        echo "  $0 $DATA_DIR/dumps/$safety --yes" >&2
    fi
}
trap finish EXIT
mkdir -p "$work"

case "$backup" in
    *.zip)
        python3 -m zipfile -e "$backup" "$work"
        [[ -f "$work/database.dump" ]] || die "$backup has no database.dump"
        if [[ -f "$work/manifest.json" ]]; then
            echo "Backup manifest:"
            cat "$work/manifest.json"
            echo
        fi
        ;;
    *)
        cp "$backup" "$work/database.dump"
        ;;
esac
pg pg_restore --list "/dumps/$work_name/database.dump" > /dev/null || die "$backup is not a valid pg_dump archive"

current=$(pg psql -U recipedealer -d recipedealer -tAc \
    "SELECT count(*) FROM recipe WHERE deleted_at IS NULL AND merged_into IS NULL" 2> /dev/null || echo "?")
echo "Restoring $backup over the current database ($current recipes)."
if [[ $# -lt 2 ]]; then
    read -r -p "This replaces the whole database. Type RESTORE to continue: " answer
    [[ "$answer" == "RESTORE" ]] || die "cancelled"
fi

pg pg_dump -U recipedealer -d recipedealer --format=custom --file="/dumps/$safety.partial"
mv "$DATA_DIR/dumps/$safety.partial" "$DATA_DIR/dumps/$safety"
echo "Current database saved to $DATA_DIR/dumps/$safety"

app_stopped=true
docker compose stop app
pg dropdb -U recipedealer --force recipedealer
pg createdb -U recipedealer -O recipedealer recipedealer
pg pg_restore -U recipedealer -d recipedealer --no-owner --exit-on-error "/dumps/$work_name/database.dump"
echo "Database restored."

if [[ -d "$work/blobs" ]]; then
    copied=0
    while IFS= read -r -d '' file; do
        relative=${file#"$work/blobs/"}
        if [[ ! -e "$DATA_DIR/blobs/$relative" ]]; then
            mkdir -p "$(dirname "$DATA_DIR/blobs/$relative")"
            cp "$file" "$DATA_DIR/blobs/$relative"
            copied=$((copied + 1))
        fi
    done < <(find "$work/blobs" -type f -print0)
    chown -R "$APP_UID:$APP_UID" "$DATA_DIR/blobs"
    echo "Blobs: $copied copied, the rest already present."
fi

docker compose start app
./wait-healthy.sh
app_stopped=false
echo "Restore of $backup finished."
