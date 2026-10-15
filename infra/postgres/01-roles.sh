#!/usr/bin/env bash
# Creates the database roles. Runs once, as the PostgreSQL superuser, the first time the postgres
# container starts (docker-entrypoint-initdb.d). The application never connects as a superuser.
#
#   tc_owner : owns the schema and runs migrations. NOT superuser, NOBYPASSRLS; every tenant table
#              uses FORCE ROW LEVEL SECURITY so even the owner is filtered.
#   tc_app   : runtime role. Only the grants the migrations give it, NOBYPASSRLS.
set -euo pipefail

: "${TC_DB_NAME:=tenantcore}"
: "${TC_DB_OWNER_PASSWORD:?TC_DB_OWNER_PASSWORD is required}"
: "${TC_DB_APP_PASSWORD:?TC_DB_APP_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER:-postgres}" --dbname postgres \
  -v owner_pw="$TC_DB_OWNER_PASSWORD" -v app_pw="$TC_DB_APP_PASSWORD" -v dbname="$TC_DB_NAME" <<'SQL'
CREATE ROLE tc_owner LOGIN PASSWORD :'owner_pw' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE tc_app   LOGIN PASSWORD :'app_pw'   NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS NOINHERIT;
ALTER ROLE tc_app SET statement_timeout = '30s';
ALTER ROLE tc_app SET idle_in_transaction_session_timeout = '60s';
CREATE DATABASE :"dbname" OWNER tc_owner;
\connect :"dbname"
REVOKE ALL ON DATABASE :"dbname" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"dbname" TO tc_app;
ALTER SCHEMA public OWNER TO tc_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO tc_app;
SQL
