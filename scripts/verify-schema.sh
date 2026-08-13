#!/usr/bin/env bash
# Apply V1–V4 against a throwaway MySQL schema and assert unique keys / seed rows.
#
# DESTRUCTIVE: DROP DATABASE IF EXISTS + CREATE DATABASE on MYSQL_VERIFY_DATABASE
# (default muscle_master_verify). Safe to re-run. Does NOT touch compose's
# muscle_master, so the server Flyway history on that DB stays authoritative.
# Seed the app DB with `docker compose up server` (Flyway), not this script.
#
# This host has no Docker; start MySQL first:
#   docker compose -f deploy/docker-compose.yml up -d mysql
# Then:
#   ./scripts/verify-schema.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIG="$ROOT/server/src/main/resources/db/migration"
HOST="${MYSQL_HOST:-127.0.0.1}"
PORT="${MYSQL_PORT:-3306}"
USER="${MYSQL_USER:-muscle}"
PASS="${MYSQL_PASSWORD:-muscle}"
ADMIN_USER="${MYSQL_ADMIN_USER:-root}"
ADMIN_PASS="${MYSQL_ADMIN_PASSWORD:-root}"
VERIFY_DB="${MYSQL_VERIFY_DATABASE:-muscle_master_verify}"

mysql_admin() {
  mysql --protocol=TCP -h "$HOST" -P "$PORT" -u "$ADMIN_USER" "-p$ADMIN_PASS" --default-character-set=utf8mb4 "$@"
}

mysql_cli() {
  mysql --protocol=TCP -h "$HOST" -P "$PORT" -u "$USER" "-p$PASS" "$VERIFY_DB" --default-character-set=utf8mb4 "$@"
}

if ! command -v mysql >/dev/null 2>&1; then
  echo "verify-schema.sh: mysql client not found. Install mysql-client or run via compose."
  echo "Contract without MySQL: mvn -f server/pom.xml -q -Dtest=SchemaContractTest test"
  exit 2
fi

if ! mysql_admin -e "SELECT 1" >/dev/null 2>&1; then
  echo "verify-schema.sh: cannot connect as $ADMIN_USER@$HOST:$PORT (needed to recreate $VERIFY_DB)"
  echo "Start compose MySQL, then re-run. Schema contract still holds via SchemaContractTest."
  exit 2
fi

echo "== DESTRUCTIVE: DROP+CREATE DATABASE \`$VERIFY_DB\` (not muscle_master)"
mysql_admin -e "DROP DATABASE IF EXISTS \`${VERIFY_DB}\`;
CREATE DATABASE \`${VERIFY_DB}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL PRIVILEGES ON \`${VERIFY_DB}\`.* TO '${USER}'@'%';
FLUSH PRIVILEGES;"

echo "== applying $MIG/V1–V4 → $VERIFY_DB"
mysql_cli < "$MIG/V1__init.sql"
mysql_cli < "$MIG/V2__rbac_seed.sql"
mysql_cli < "$MIG/V3__demo_store.sql"
mysql_cli < "$MIG/V4__locknew_free_indexes.sql"

fail=0
assert_uk() {
  local table="$1" index="$2"
  local found
  found="$(mysql_cli -N -e "SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema='$VERIFY_DB' AND table_name='$table' AND index_name='$index' AND non_unique=0")"
  if [[ "$found" == "0" ]]; then
    echo "FAIL missing unique $table.$index"
    fail=1
  else
    echo "OK   unique $table.$index"
  fi
}

assert_uk_cols() {
  local table="$1" index="$2" expected="$3"
  local cols
  cols="$(mysql_cli -N -e "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
    FROM information_schema.statistics
    WHERE table_schema='$VERIFY_DB' AND table_name='$table' AND index_name='$index' AND non_unique=0")"
  if [[ "$cols" != "$expected" ]]; then
    echo "FAIL $table.$index columns='$cols' expected='$expected'"
    fail=1
  else
    echo "OK   $table.$index columns=$cols"
  fi
}

assert_uk therapist_slot uk_therapist_slot
assert_uk bed_slot uk_bed_slot
assert_uk slot_occupancy uk_occ
assert_uk customer uk_customer_openid
assert_uk_cols therapist_slot uk_therapist_slot "therapist_id,slot_date,slot_no"
assert_uk_cols bed_slot uk_bed_slot "bed_id,slot_date,slot_no"
assert_uk_cols slot_occupancy uk_occ "resource_type,resource_id,slot_date,slot_no"

idx_ts="$(mysql_cli -N -e "SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='$VERIFY_DB' AND table_name='therapist_slot' AND index_name='idx_ts_free'")"
idx_bs="$(mysql_cli -N -e "SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema='$VERIFY_DB' AND table_name='bed_slot' AND index_name='idx_bs_free'")"
if [[ "$idx_ts" == "0" || "$idx_bs" == "0" ]]; then
  echo "FAIL missing idx_ts_free/idx_bs_free"
  fail=1
else
  echo "OK   idx_ts_free / idx_bs_free"
fi

nullable="$(mysql_cli -N -e "SELECT IS_NULLABLE FROM information_schema.columns
  WHERE table_schema='$VERIFY_DB' AND table_name='customer' AND column_name='wx_openid'")"
if [[ "$nullable" != "YES" ]]; then
  echo "FAIL customer.wx_openid IS_NULLABLE=$nullable"
  fail=1
else
  echo "OK   customer.wx_openid nullable"
fi

if mysql_cli -N -e "SELECT column_name FROM information_schema.columns
  WHERE table_schema='$VERIFY_DB' AND column_name='balance_fen'" | grep -q .; then
  echo "FAIL balance_fen exists"
  fail=1
else
  echo "OK   no balance_fen"
fi

counts="$(mysql_cli -N -e "
  SELECT (SELECT COUNT(*) FROM store),
         (SELECT COUNT(*) FROM therapist),
         (SELECT COUNT(*) FROM bed),
         (SELECT COUNT(*) FROM schedule_template),
         (SELECT COUNT(*) FROM role);")"
echo "counts store/therapist/bed/templates/roles: $counts"
if [[ "$counts" != $'1\t3\t2\t21\t7' ]]; then
  echo "FAIL expected 1/3/2/21/7"
  fail=1
else
  echo "OK   demo + RBAC counts"
fi

if [[ "$fail" -ne 0 ]]; then
  exit 1
fi
echo "verify-schema.sh PASS"
