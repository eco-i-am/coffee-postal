#!/usr/bin/env bash
# Nightly database backup. Run from cron on the VM:  0 2 * * * /opt/coffee-postal/deploy/backup.sh
# Keeps 14 local dumps and copies each one to remote storage with rclone (configure a remote named "oci" and "offsite").
set -euo pipefail
cd "$(dirname "$0")"
set -a; source ../.env; set +a

STAMP=$(date +%F-%H%M)
OUT=/var/backups/coffee-postal
mkdir -p "$OUT"

docker compose --env-file ../.env -f compose.prod.yaml exec -T db \
  pg_dump -U "$POSTGRES_USER" -Fc "$POSTGRES_DB" > "$OUT/pos-$STAMP.dump"

# keep the last 14 local copies
ls -1t "$OUT"/pos-*.dump | tail -n +15 | xargs -r rm --

# copy off the box (skip silently if rclone remotes are not set up yet)
if command -v rclone >/dev/null; then
  rclone copy "$OUT/pos-$STAMP.dump" oci:coffee-postal-backups/ || echo "oci copy failed" >&2
  rclone copy "$OUT/pos-$STAMP.dump" offsite:coffee-postal-backups/ || echo "offsite copy failed" >&2
fi

# Restore test (do this before launch and monthly):
#   docker compose --env-file ../.env -f compose.prod.yaml exec -T db pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean < pos-XXXX.dump
