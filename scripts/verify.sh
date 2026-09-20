#!/usr/bin/env bash
# Full verification for the Java taskboard fixture:
#
#   1. scripts/build.sh -> release marker (VERSION) + shaded jar
#   2. clean install -> mvn dependency:go-offline against a fresh local repo
#   3. mvn test -> JUnit suite (CRUD, search/filter, validation, persistence,
#      database-unavailable readiness)
#   4. scripts/smoke.sh -> real production process: CRUD, invalid input,
#      search/filter, restart persistence, database-unavailable readiness
#
# Usage:
#   scripts/verify.sh
#
# Exit codes: 0 = all checks passed, nonzero = a check failed. The first
# failing step aborts with its own nonzero code.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-$(command -v java)}"
[ -x "$JAVA_BIN" ] || { echo "java executable not found (set JAVA_HOME)" >&2; exit 1; }
command -v mvn >/dev/null 2>&1 || [ -x "$ROOT/mvnw" ] || { echo "mvn or mvnw not found (Maven 3.9+ recommended)" >&2; exit 1; }

MVN="mvn"
if [ -x "$ROOT/mvnw" ]; then
  MVN="$ROOT/mvnw"
fi

step() { printf '\n=== %s ===\n' "$*"; }

step "build release marker + shaded jar"
"$ROOT/scripts/build.sh"

step "clean dependency resolve"
rm -f "$ROOT/pom.xml.versionsBackup"
(cd "$ROOT" && "$MVN" -B -q dependency:go-offline)

step "test suite ($MVN test)"
(cd "$ROOT" && "$MVN" -B test)

step "production smoke: real process + CRUD + negatives + persistence + readiness"
"$ROOT/scripts/smoke.sh"

step "verification complete (all steps passed)"
printf '%s\n' "java: $("$JAVA_BIN" -version 2>&1 | head -1)"
printf '%s\n' "mvn: $("$MVN" -version 2>&1 | head -1)"
printf '%s\n' "release marker: $(cat "$ROOT/VERSION")"
printf '%s\n' "jar: $([ -f "$ROOT/target/deploy-test-java.jar" ] && echo present || echo missing)"