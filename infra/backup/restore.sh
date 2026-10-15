#!/usr/bin/env bash
# Restores one backup over the current data. DESTRUCTIVE: the database and the stored files are
# replaced by the backup's contents.
#
#   docker compose stop api
#   docker compose run --rm backup bash /opt/backup/restore.sh 20261015-020000     # a backup's timestamp
#   docker compose start api
#
# List the available timestamps with:  docker compose run --rm backup ls /backups
set -euo pipefail

stamp="${1:?usage: restore.sh <timestamp>, e.g. 20261015-020000}"
: "${PGHOST:=postgres}" "${PGUSER:=postgres}" "${PGDATABASE:=tenantcore}"
: "${PGPASSWORD:?PGPASSWORD is required}"
: "${BACKUP_DIR:=/backups}" "${STORAGE_DIR:=/storage}"
export PGHOST PGUSER PGPASSWORD

db="$BACKUP_DIR/tenantcore-$stamp.dump"
files="$BACKUP_DIR/storage-$stamp.tar.gz"
[ -f "$db" ] || { echo "no such backup: $db" >&2; exit 1; }
pg_restore --list "$db" > /dev/null

if [ "${RESTORE_YES:-}" != "yes" ]; then
  read -r -p "Replace database '$PGDATABASE' and all stored files with backup $stamp? Type the database name: " answer
  [ "$answer" = "$PGDATABASE" ] || { echo "aborted"; exit 1; }
fi

active=$(psql -d postgres -Atc "SELECT count(*) FROM pg_stat_activity WHERE datname = '$PGDATABASE' AND usename <> current_user")
if [ "$active" != "0" ]; then
  echo "$active connection(s) to $PGDATABASE are open. Stop the api first: docker compose stop api" >&2
  exit 1
fi

# The roles (tc_owner, tc_app) live outside the database and are left as they are.
dropdb --if-exists "$PGDATABASE"
pg_restore --create --exit-on-error --dbname=postgres "$db"
echo "database restored from $db"

if [ -f "$files" ]; then
  find "$STORAGE_DIR" -mindepth 1 -delete
  tar -C "$STORAGE_DIR" -xzf "$files"
  chown -R 10001:10001 "$STORAGE_DIR"   # the api runs as uid 10001
  echo "files restored from $files"
else
  echo "no storage archive for $stamp; files were left as they are"
fi
