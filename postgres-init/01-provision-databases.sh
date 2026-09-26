#!/bin/bash
set -e

provision_service_db() {
  local role="$1"
  local password="$2"
  local database="$3"

  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    DO \$\$
    BEGIN
      IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${role}') THEN
        CREATE ROLE ${role} WITH LOGIN PASSWORD '${password}';
      END IF;
    END
    \$\$;
EOSQL

  if ! psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -tAc \
       "SELECT 1 FROM pg_database WHERE datname = '${database}'" | grep -q 1; then
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -c \
      "CREATE DATABASE ${database} OWNER ${role};"
  fi
}

provision_service_db "antar_auth"      "${AUTH_DB_PASSWORD}"      "antar_auth"
provision_service_db "antar_streaming" "${STREAMING_DB_PASSWORD}" "antar_streaming"