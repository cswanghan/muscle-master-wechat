#!/usr/bin/env bash
# Apply Flyway V1–V3 against compose MySQL and assert unique keys / seed rows.
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
DB="${MYSQL_DATABASE:-muscle_master}"

mysql_cli() {
  mysql --protocol=TCP -h "$HOST" -P "$PORT" -u "$USER" "-p$PASS" "$DB" --default-character-set=utf8mb4 "$@"
}

if ! command -v mysql >/dev/null 2>&1; then
  echo "verify-schema.sh: mysql client not found. Install mysql-client or run via compose."
  echo "Contract without MySQL: mvn -f server/pom.xml -q -Dtest=SchemaContractTest test"
  exit 2
fi

if ! mysql_cli -e "SELECT 1" >/dev/null 2>&1; then
  echo "verify-schema.sh: cannot connect to mysql://$USER@$HOST:$PORT/$DB"
  echo "Start compose MySQL, then re-run. Schema contract still holds via SchemaContractTest."
  exit 2
fi

echo "== applying $MIG/V1__init.sql V2__rbac_seed.sql V3__demo_store.sql"
mysql_cli < "$MIG/V1__init.sql"
mysql_cli < "$MIG/V2__rbac_seed.sql"
mysql_cli < "$MIG/V3__demo_store.sql"

fail=0
assert_uk() {
  local table="$1" index="$2"
  local found
  found="$(mysql_cli -N -e "SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema='$DB' AND table_name='$table' AND index_name='$index' AND non_unique=0")"
  if [[ "$found" == "0" ]]; then
    echo "FAIL missing unique $table.$index"
    fail=1
  else
    echo "OK   unique $table.$index"
  fi
}

assert_uk therapist_slot uk_therapist_slot
assert_uk bed_slot uk_bed_slot
assert_uk slot_occupancy uk_occ
assert_uk customer uk_customer_openid

nullable="$(mysql_cli -N -e "SELECT IS_NULLABLE FROM information_schema.columns
  WHERE table_schema='$DB' AND table_name='customer' AND column_name='wx_openid'")"
if [[ "$nullable" != "YES" ]]; then
  echo "FAIL customer.wx_openid IS_NULLABLE=$nullable"
  fail=1
else
  echo "OK   customer.wx_openid nullable"
fi

if mysql_cli -N -e "SELECT column_name FROM information_schema.columns
  WHERE table_schema='$DB' AND column_name='balance_fen'" | grep -q .; then
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
