#!/usr/bin/env bash
# Starts a throwaway PostgreSQL 16, applies the DevNex migration on top of a
# Supabase auth stub, runs the RLS suite, then a real two-session claim race.
# Usage: ./run_tests.sh [scratch-dir]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
PGBIN=${PGBIN:-/usr/lib/postgresql/16/bin}
DATA="${1:-$(mktemp -d)}/pgdata"; PORT=55432
mkdir -p "$DATA"; chown -R postgres "$(dirname "$DATA")" 2>/dev/null || true
run() { if [ "$(id -u)" = 0 ]; then su postgres -c "$*"; else bash -c "$*"; fi; }
run "$PGBIN/initdb -D $DATA -A trust -U postgres >/dev/null"
run "$PGBIN/pg_ctl -D $DATA -o '-p $PORT -k /tmp' -l $DATA/log -w start >/dev/null"
trap 'run "$PGBIN/pg_ctl -D $DATA -m fast stop >/dev/null"' EXIT
PSQL="psql -h /tmp -p $PORT -U postgres -v ON_ERROR_STOP=1 -q"
$PSQL -c "create database devnex" >/dev/null
$PSQL -d devnex -f "$HERE/00_local_supabase_stub.sql" >/dev/null
for m in "$HERE"/../supabase/migrations/*.sql; do $PSQL -d devnex -f "$m" >/dev/null; done
echo "== migration applied =="
$PSQL -d devnex -f "$HERE/10_rls_tests.sql" 2>&1 | sed 's/^psql:[^ ]* NOTICE:  //'
$PSQL -d devnex -f "$HERE/20_modules_tests.sql" 2>&1 | sed 's/^psql:[^ ]* NOTICE:  //'

echo "== concurrent claim race (two real sessions) =="
TASK=$($PSQL -d devnex -At \
  -c "select set_config('request.jwt.claim.sub','00000000-0000-0000-0000-00000000000a',false)" \
  -c "set role authenticated" \
  -c "insert into public.tasks (company_id, title, created_by, status, is_open_pool)
      select id, 'Race task', '00000000-0000-0000-0000-00000000000a', 'open', true
      from public.companies where slug='alpha-sports' returning id" | tail -1)
claim() { $PSQL -d devnex -At -c "select set_config('request.jwt.claim.sub','$1',false)" \
  -c "set role authenticated" -c "begin" -c "select public.claim_task('$TASK')" \
  -c "select pg_sleep(1)" -c "commit" | grep CLAIM; }
claim 00000000-0000-0000-0000-00000000000b > "$DATA/../r1" &
claim 00000000-0000-0000-0000-00000000000c > "$DATA/../r2" &
wait
R1=$(cat "$DATA/../r1"); R2=$(cat "$DATA/../r2")
echo "Ravi: $R1   Meera: $R2"
WINNERS=$(printf "%s\n%s\n" "$R1" "$R2" | grep -c CLAIM_SUCCESS || true)
OWNERS=$($PSQL -d devnex -At -c "select count(*) from public.task_claims where task_id='$TASK'")
if [ "$WINNERS" = 1 ] && [ "$OWNERS" = 1 ]; then echo "ok  - exactly one winner, one claim row"
else echo "TEST FAILED: race"; exit 1; fi
echo "== ALL CHECKS PASSED =="
