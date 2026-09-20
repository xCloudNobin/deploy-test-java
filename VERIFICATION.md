# Verification

Recorded for the plain Java taskboard fixture
(`xCloudNobin/deploy-test-java`). Purely local evidence; no live xCloud
deployment is claimed here.

## Environment

| Component | Version |
|-----------|---------|
| Java (JDK) | OpenJDK 21.0.12+8-1-24.04 |
| Maven | 3.8.7 |
| OS | Ubuntu 24.04.3 LTS (x86_64) |
| SQLite | `org.xerial:sqlite-jdbc` 3.46.1.3 |

## Commands

```bash
scripts/verify.sh
```

which runs:

1. `scripts/build.sh` → writes `VERSION` + `mvn clean package` (shaded jar).
2. `mvn dependency:go-offline` (clean resolve).
3. `mvn test` (JUnit 5).
4. `scripts/smoke.sh` → real `java -jar` production process with liveness,
   readiness, CRUD over HTTP, search/filter, negative cases, graceful
   stop → restart persistence, and database-unavailable readiness.

## Result

| Check | Outcome |
|-------|---------|
| `mvn clean package` | PASS |
| `mvn test` (11 tests) | PASS |
| `scripts/smoke.sh` | PASS |

## Smoke coverage mapped to acceptance

- UI served at `/`, client JS/CSS served, unknown static/API → 404.
- Release marker present in `/api/meta` and runtime reports `java`.
- Project CRUD: create (201), list, read with tasks, patch (200), delete
  cascade (204).
- Task CRUD: create (201) across statuses, read-back, patch (200),
  delete (204) → 404 after.
- Search `q` includes matches and excludes non-matches; status filter
  includes/excludes correctly.
- Negative: blank title, missing `project_id`, invalid status, invalid
  priority, malformed JSON, blank project name, invalid project status,
  unknown task/project, empty PATCH, PUT on collection → all 400/404 with
  meaningful `fields` payloads.
- Database exists on disk at configured path after writes.
- Persistence: PERSIST survivor task survives graceful stop + restart on the
  same SQLite path; task counts stable before/after.
- Database unavailable: process starts and keeps liveness 200 while
  readiness → 503 (`status: "unavailable"`), API list route → 503, static UI
  still served.

## Checks not run / limitations

- No live xCloud deployment: this is `local-verified` only. Repeat the same
  checks against the exact commit after a platform deploy.
- Maven downloads dependencies from Maven Central; a fully offline pre-seeded
  cache was not used.
- No CSRF token layer: this fixture has no cookie-authenticated session
  (stateless JSON API), so there is no cookie-session CSRF surface.

## Artifacts

- Shaded jar: `target/deploy-test-java.jar`
- Release marker: `VERSION` (written by `scripts/build.sh`)