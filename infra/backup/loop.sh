#!/usr/bin/env bash
# Entry point of the "backup" compose service: one backup at start, then every BACKUP_INTERVAL_HOURS.
# A failed run is logged and retried at the next interval; the dashboard shows the backup as stale.
set -uo pipefail
# "docker compose run --rm backup <command>" runs that command instead (restore.sh, ls, ...).
if [ "$#" -gt 0 ]; then exec "$@"; fi
: "${BACKUP_INTERVAL_HOURS:=24}"
until pg_isready -h "${PGHOST:-postgres}" -q; do sleep 2; done
while true; do
  bash /opt/backup/backup.sh || echo "backup FAILED at $(date -u +%FT%TZ)" >&2
  sleep $(( BACKUP_INTERVAL_HOURS * 3600 ))
done
