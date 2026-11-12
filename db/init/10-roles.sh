#!/usr/bin/env bash
# Runs once inside the postgres container, as the superuser, when the data volume is new.
# Module 13's two roles, copied from its seed unchanged, plus what the study needs to read
# statistics:
#   seatwave_owner  owns the study schema, creates and drops indexes, reads every statistics view
#   seatwave_app    reads and writes rows (unused by the study; kept so the roles match Module 13)
# Passwords come from the Compose secrets, as psql variables, never pasted into SQL text.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_password="$(< /run/secrets/owner_password)" \
  -v app_password="$(< /run/secrets/app_password)" <<'SQL'
CREATE ROLE seatwave_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE seatwave_app LOGIN PASSWORD :'app_password';
GRANT CONNECT, CREATE ON DATABASE seatwave TO seatwave_owner;
GRANT CONNECT ON DATABASE seatwave TO seatwave_app;
REVOKE CONNECT ON DATABASE seatwave FROM PUBLIC;
-- The slow-query view of Section 6 and Section 10. Only the superuser may create it.
CREATE EXTENSION pg_stat_statements;
-- Lets the owner see every query's statistics and every table's sizes, and nothing more.
GRANT pg_read_all_stats TO seatwave_owner;
SQL
