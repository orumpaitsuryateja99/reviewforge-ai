#!/usr/bin/env bash
# End-to-end smoke test against a running stack (docker compose up -d).
# Checks every boundary that does not require GitHub or model credentials, including a real
# Maven execution inside the isolated runner.
set -uo pipefail

API="${API_URL:-http://localhost:8080}"
UI="${UI_URL:-http://localhost:3000}"
RUNNER_CONTAINER="${RUNNER_CONTAINER:-reviewforge-runner-1}"

passed=0
failed=0

check() {
  local name="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    printf '  ok    %-52s %s\n' "$name" "$actual"
    passed=$((passed + 1))
  else
    printf '  FAIL  %-52s expected %s, got %s\n' "$name" "$expected" "$actual"
    failed=$((failed + 1))
  fi
}

contains() {
  local name="$1" needle="$2" haystack="$3"
  if printf '%s' "$haystack" | grep -q "$needle"; then
    printf '  ok    %-52s contains %s\n' "$name" "$needle"
    passed=$((passed + 1))
  else
    printf '  FAIL  %-52s missing %s in: %s\n' "$name" "$needle" "${haystack:0:6000}"
    failed=$((failed + 1))
  fi
}

status_of() { curl -s -o /dev/null -w '%{http_code}' --max-time 15 "$@"; }

echo "ReviewForge smoke test"
echo
echo "control plane"
contains "health"                "reviewforge-api"   "$(curl -s --max-time 15 "$API/api/v1/health")"
contains "readiness"             '"status":"UP"'     "$(curl -s --max-time 15 "$API/actuator/health/readiness")"
contains "capabilities"          '"testRunner"'      "$(curl -s --max-time 15 "$API/api/v1/capabilities")"
check    "repositories require a session" "401"      "$(status_of "$API/api/v1/repositories")"
check    "analyses require a session"     "401"      "$(status_of -X POST "$API/api/v1/pull-requests/00000000-0000-0000-0000-000000000000/analyses")"
contains "problem+json carries a code"    '"code"'   "$(curl -s --max-time 15 "$API/api/v1/repositories")"

echo
echo "dashboard"
check    "dashboard responds"    "200"               "$(status_of "$UI/")"
contains "proxy reaches the API" '"review"'          "$(curl -s --max-time 15 "$UI/api/platform/capabilities")"
check    "proxy rejects a bad path" "400"            "$(status_of "$UI/api/platform/..%2f..")"

echo
echo "isolated runner"
if ! docker inspect "$RUNNER_CONTAINER" >/dev/null 2>&1; then
  echo "  SKIP  runner container $RUNNER_CONTAINER not found"
else
  check  "runner is not published to the host" "000" "$(status_of --max-time 3 http://localhost:8090/runner/v1/health)"
  contains "runner health (internal)" "reviewforge-runner" \
    "$(docker exec "$RUNNER_CONTAINER" curl -sf --max-time 10 http://127.0.0.1:8090/runner/v1/health)"

  work="$(mktemp -d)"
  trap 'rm -rf "$work"' EXIT
  mkdir -p "$work/acme-orders-smoke/src/main/java/com/acme"
  cat > "$work/acme-orders-smoke/pom.xml" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.5.5</version>
    <relativePath/>
  </parent>
  <groupId>com.acme</groupId>
  <artifactId>orders</artifactId>
  <version>1.0.0</version>
  <properties>
    <java.version>25</java.version>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-test</artifactId>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
XML
  cat > "$work/acme-orders-smoke/src/main/java/com/acme/OrderService.java" <<'JAVA'
package com.acme;

public class OrderService {

    private int available;

    public OrderService(int available) {
        this.available = available;
    }

    /** Reserves even when the quantity exceeds stock: the defect the generated test proves. */
    public boolean reserve(int quantity) {
        if (available > 0) {
            available -= quantity;
            return true;
        }
        return false;
    }
}
JAVA
  tar -czf "$work/snapshot.tar.gz" -C "$work" acme-orders-smoke

  cat > "$work/pass.json" <<'JSON'
{
  "patchPath": "src/test/java/com/acme/OrderServiceReviewForgeTest.java",
  "patchContent": "package com.acme;\n\nimport static org.junit.jupiter.api.Assertions.assertTrue;\n\nimport org.junit.jupiter.api.Test;\n\nclass OrderServiceReviewForgeTest {\n\n    @Test\n    void reservesWhenStockIsAvailable() {\n        assertTrue(new OrderService(5).reserve(1));\n    }\n}\n",
  "profile": "MAVEN_SINGLE_TEST",
  "testSelector": "com.acme.OrderServiceReviewForgeTest",
  "timeoutSeconds": 420
}
JSON
  cat > "$work/fail.json" <<'JSON'
{
  "patchPath": "src/test/java/com/acme/OrderServiceReviewForgeTest.java",
  "patchContent": "package com.acme;\n\nimport static org.junit.jupiter.api.Assertions.assertFalse;\n\nimport org.junit.jupiter.api.Test;\n\nclass OrderServiceReviewForgeTest {\n\n    @Test\n    void refusesToOversell() {\n        assertFalse(new OrderService(1).reserve(5));\n    }\n}\n",
  "profile": "MAVEN_SINGLE_TEST",
  "testSelector": "com.acme.OrderServiceReviewForgeTest",
  "timeoutSeconds": 420
}
JSON
  cat > "$work/escape.json" <<'JSON'
{
  "patchPath": "src/test/../../../../etc/cron.d/pwned.java",
  "patchContent": "class X {}",
  "profile": "MAVEN_SINGLE_TEST",
  "testSelector": "com.acme.X",
  "timeoutSeconds": 60
}
JSON
  cat > "$work/profile.json" <<'JSON'
{
  "patchPath": "src/test/java/com/acme/X.java",
  "patchContent": "class X {}",
  "profile": "BASH",
  "testSelector": "com.acme.X",
  "timeoutSeconds": 60
}
JSON

  # `docker cp` refuses a read-only rootfs even when the destination is the writable /tmp
  # mount. Stream each fixture through the container process instead.
  for file in snapshot.tar.gz pass.json fail.json escape.json profile.json; do
    docker exec -i "$RUNNER_CONTAINER" sh -c 'umask 077; tee "$1" >/dev/null' sh \
      "/tmp/smoke-$file" < "$work/$file"
  done

  run_case() {
    docker exec "$RUNNER_CONTAINER" curl -sS --max-time 600 -X POST \
      http://127.0.0.1:8090/runner/v1/runs \
      -F 'snapshot=@/tmp/smoke-snapshot.tar.gz' \
      -F "request=@/tmp/smoke-$1;type=application/json"
  }
  run_status() {
    docker exec "$RUNNER_CONTAINER" curl -sS -o /dev/null -w '%{http_code}' --max-time 60 -X POST \
      http://127.0.0.1:8090/runner/v1/runs \
      -F 'snapshot=@/tmp/smoke-snapshot.tar.gz' \
      -F "request=@/tmp/smoke-$1;type=application/json"
  }

  echo "  (compiling and running a real Maven project from the image's offline dependency set)"
  passing="$(run_case pass.json)"
  contains "passing test reports PASSED"   '"status":"PASSED"'   "$passing"
  contains "passing test counts one test"  '"testsPassed":1'     "$passing"

  failing="$(run_case fail.json)"
  contains "failing test reports FAILED"   '"status":"FAILED"'   "$failing"
  contains "failing test counts a failure" '"testsFailed":1'     "$failing"

  check "path escape is refused"           "400" "$(run_status escape.json)"
  check "non-allowlisted profile refused"  "400" "$(run_status profile.json)"
  check "workspaces are deleted"           "0"   "$(docker exec "$RUNNER_CONTAINER" sh -c 'ls -A /tmp/reviewforge-runs 2>/dev/null | wc -l' | tr -d ' ')"

  docker exec "$RUNNER_CONTAINER" rm -f \
    /tmp/smoke-snapshot.tar.gz /tmp/smoke-pass.json /tmp/smoke-fail.json \
    /tmp/smoke-escape.json /tmp/smoke-profile.json
fi

echo
echo "passed: $passed   failed: $failed"
[ "$failed" -eq 0 ]
