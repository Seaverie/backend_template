#!/usr/bin/env bash
# Bootstraps database roles. Runs once as the PostgreSQL superuser (docker-entrypoint-initdb.d or a
# DBA runbook). The application never connects as a superuser.
#
#   tc_owner : owns the schema, runs Alembic migrations and dynamic DDL. NOT superuser, NOBYPASSRLS,
#              and every tenant table uses FORCE ROW LEVEL SECURITY so even the owner is filtered.
#   tc_app   : runtime role. DML only on explicitly granted tables, NOBYPASSRLS.
set -euo pipefail

: "${TC_DB_NAME:=tenantcore}"
: "${TC_OWNER_PASSWORD:?TC_OWNER_PASSWORD is required}"
: "${TC_APP_PASSWORD:?TC_APP_PASSWORD is required}"

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER:-postgres}" \
  -v owner_pw="$TC_OWNER_PASSWORD" -v app_pw="$TC_APP_PASSWORD" -v dbname="$TC_DB_NAME" <<'SQL'
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
