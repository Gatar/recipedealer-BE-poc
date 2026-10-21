#!/usr/bin/env bash
# Waits until the app answers inside the compose network (401 from /v1/health without a token means it is up).
set -uo pipefail
cd "$(dirname "$0")"
for _ in $(seq 1 30); do
    code=$(docker compose exec -T caddy sh -c \
        "wget -qO- --server-response http://app:8080/v1/health 2>&1 | grep -oE 'HTTP/[0-9.]+ [0-9]{3}' | tail -1 | cut -d' ' -f2")
    if [[ "$code" == "401" ]]; then
        echo "app is up"
        exit 0
    fi
    sleep 5
done
echo "app did not come up" >&2
docker compose logs --tail 100 app >&2
exit 1
