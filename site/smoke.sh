#!/usr/bin/env bash
# S1a 손 기동 확인. 저장소 루트에서 돈다.
# 같은 compose 프로젝트(site)를 따로 띄워 두었다면 끝날 때 함께 내린다.
set -u
mkdir -p build
cleanup() {
  [ -n "${OPS_PID:-}" ] && kill "$OPS_PID" 2>/dev/null
  [ -n "${SITE_PID:-}" ] && kill "$SITE_PID" 2>/dev/null
  docker compose -f site/compose.yaml --env-file .env down -v >/dev/null 2>&1
}
trap cleanup EXIT

docker compose -f site/compose.yaml --env-file .env up -d --wait || exit 1
./gradlew :site:installDist :ops-service:installDist --console=plain -q || exit 1
set -a; . ./.env; set +a

site/build/install/site/bin/site > build/site.log 2>&1 &
SITE_PID=$!
curl -s -f -o /dev/null --retry 60 --retry-delay 1 --retry-all-errors "localhost:$REGISTRY_PORT/diag/robots" || exit 1

# 적재 토큰은 site 만 쥔다(스펙 §4).
env -u PICASSO_INGEST_TOKEN ops-service/build/install/ops-service/bin/ops-service > build/ops.log 2>&1 &
OPS_PID=$!
echo "robots: $(curl -s --retry 60 --retry-delay 1 --retry-all-errors "localhost:$OPS_PORT/api/robots")"
echo "operations: $(curl -s "localhost:$OPS_PORT/api/operations")"

# 1:1 시간 진행 확인. 명부의 기체 하나를 registry 에 직접 선언하고 45초 안에 CONFIRMED 가 되는지 본다.
curl -s -o /dev/null -w "declare: %{http_code}\n" -X POST "localhost:$REGISTRY_PORT/operations/robots" \
  -H "Authorization: Bearer $PICASSO_OPERATOR_TOKEN" -H "X-Actor: engineer/smoke" -H "Content-Type: application/json" \
  -d "{\"robot_id\":\"humanoid-01\",\"site\":\"$SITE_ID\",\"serial_number\":\"HA-0001\"}"
for i in $(seq 1 45); do
  if curl -s "localhost:$OPS_PORT/api/robots" | grep -q '"status":"CONFIRMED"'; then
    echo "CONFIRMED after ${i}s"
    exit 0
  fi
  sleep 1
done
echo "NOT CONFIRMED in 45s"
exit 1
