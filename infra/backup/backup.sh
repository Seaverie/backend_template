#!/usr/bin/env bash
# Takes one backup: a pg_dump of the database and a tarball of the file storage, then deletes
# backups older than BACKUP_KEEP_DAYS. Runs inside the "backup" compose service (postgres:16 image).
#
# The dump connects as the PostgreSQL superuser on purpose: every tenant table uses FORCE ROW LEVEL
# SECURITY, so the schema owner would only see the rows of "no tenant" and produce an empty dump.
set -euo pipefail

: "${PGHOST:=postgres}" "${PGUSER:=postgres}" "${PGDATABASE:=tenantcore}"
: "${PGPASSWORD:?PGPASSWORD (the postgres superuser password) is required}"
: "${BACKUP_DIR:=/backups}" "${STORAGE_DIR:=/storage}" "${BACKUP_KEEP_DAYS:=14}"
export PGHOST PGUSER PGDATABASE PGPASSWORD

stamp=$(date -u +%Y%m%d-%H%M%S)
db="$BACKUP_DIR/tenantcore-$stamp.dump"
files="$BACKUP_DIR/storage-$stamp.tar.gz"
mkdir -p "$BACKUP_DIR"

# Write to a temporary name and rename at the end, so a half-written file never looks like a backup.
pg_dump --format=custom --compress=6 --file="$db.part" "$PGDATABASE"
pg_restore --list "$db.part" > /dev/null   # the archive is readable
mv "$db.part" "$db"

if [ -d "$STORAGE_DIR" ]; then
  tar -C "$STORAGE_DIR" -czf "$files.part" .
  mv "$files.part" "$files"
fi

find "$BACKUP_DIR" -maxdepth 1 -type f \( -name 'tenantcore-*.dump' -o -name 'storage-*.tar.gz' \) \
  -mtime "+$BACKUP_KEEP_DAYS" -print -delete
find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.part' -mmin +240 -delete

echo "backup $stamp: $(du -h "$db" | cut -f1) database, $( [ -f "$files" ] && du -h "$files" | cut -f1 || echo 'no') files"
