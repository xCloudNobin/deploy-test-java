# Java Taskboard

A meaningful plain **Java 21** application for the xCloud app-compatibility
suite: a project/task board written directly against the JDK standard-library
HTTP server (`com.sun.net.httpserver.HttpServer`) with **SQLite JDBC**
persistence — **no Spring, no servlet container, no web framework**.

It is a production-process fixture, not a success-page shell: every workflow
reads and writes through parameterized SQLite queries, all input is validated
server-side with meaningful error payloads, and `scripts/verify.sh` exercises
the real shaded JAR end to end.

## Feature summary

- Standard-library `com.sun.net.httpserver.HttpServer` + `org.xerial:sqlite-jdbc`.
  Only runtime dependency is the SQLite JDBC driver.
- Projects and tasks with status/priority, search (`q`), and status/priority
  filters — all over a JSON API consumed by a small DOM-rendered client.
- Validated CRUD: blank/over-long/mistyped fields, invalid status/priority,
  malformed JSON, missing references and not-found resources all return
  meaningful JSON errors (400/404); output is escaped client-side by
  rendering via `textContent` only (no `innerHTML` with user data).
- Parameterized SQL everywhere (LIKE wildcards escaped) — no string-built
  queries from user input.
- Idempotent schema setup (`CREATE TABLE IF NOT EXISTS`) and repeatable seed
  data, guarded by a one-time seed flag so re-opens never duplicate rows.
- Persistence: explicit SQLite file (`DATA_DIR`/`DATABASE_PATH`); the app
  never stores permanent state in an ephemeral release directory.
- `/api/health/live` (process alive) and `/api/health/ready` (does a real
  database open + read/write; **503** while the database is unavailable).
- Non-sensitive release marker: `scripts/build.sh` writes `VERSION` (git SHA
  by default) and builds the shaded JAR; the marker is served by `/api/meta`
  and shown in the UI footer.
- Graceful SIGTERM/SIGINT shutdown (server stop + database close), logs to
  stdout/stderr.

## Runtime and dependencies

- Java **21** (OpenJDK 21 validated), built with Maven and pinned plugin
  versions; outputs a self-contained shaded JAR.
- Runtime dependency: `org.xerial:sqlite-jdbc` (pinned in `pom.xml`).
- Test scope: JUnit 5.
- No lockfile is used; `pom.xml` pins the Maven plugin versions and the
  `org.xerial:sqlite-jdbc` version so builds are reproducible.

Runtime versions (this verification):

| Component | Version |
|-----------|---------|
| Java      | 21.0.12 (OpenJDK 21.0.12+8-1-24.04) |
| Maven     | 3.8.7 |
| SQLite    | via `org.xerial:sqlite-jdbc` 3.46.1.3 |

## Quick start (development)

```bash
mvn compile
cp .env.example .env       # review and adjust
PORT=8080 mvn exec:java     # (or use scripts/start.sh pattern below)
```

Prefer building the shaded JAR and running it exactly as production does:

```bash
bash scripts/build.sh       # writes VERSION + builds target/deploy-test-java.jar
PORT=8080 DATA_DIR=./data java -jar target/deploy-test-java.jar
```

Open http://localhost:8080 — the seeder creates two demo projects and a few
tasks on first boot.

## Production start

```bash
mvn -B clean package -DskipTests        # reproducible shaded build
bash scripts/build.sh                   # marker + rebuild target/deploy-test-java.jar
java -jar target/deploy-test-java.jar   # BIND_HOST=0.0.0.0 PORT=8080 by default
```

- Binds to `BIND_HOST:PORT` (defaults **0.0.0.0:8080**).
- Logs go to stdout/stderr; the process answers SIGTERM/SIGINT with a clean
  shutdown.

## Health and readiness

| Endpoint | Meaning |
|----------|---------|
| `GET /api/health/live`  | Process is alive (always 200 while serving). |
| `GET /api/health/ready` | Opens a fresh SQLite connection at `DATABASE_PATH`, performs a real read/write against the `heartbeat` table, then closes it. **503** when the database is unavailable, with a `status: "unavailable"` body and the underlying reason. |

`scripts/smoke.sh` proves the readiness contract: it points the process at an
unopenable path, observes readiness drop to 503 (liveness stays 200), then
restarts on a healthy path and observes readiness recover to 200.

## Environment variables

See `.env.example` for the full commented list.

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `PORT` | no | `8080` | bind port |
| `BIND_HOST` | no | `0.0.0.0` | bind address |
| `DATA_DIR` | no | `<repo>/data` | base data directory |
| `DATABASE_PATH` | no | `<DATA_DIR>/taskboard.db` | **persistent SQLite path** |
| `BUILD_MARKER` | no | git SHA | release marker in `/api/meta` and the UI footer |

No credentials or secrets are committed or required.

## Persistence

Data lives in the SQLite file at `DATABASE_PATH`, which defaults under
`DATA_DIR` (gitignored). For redeploys that reuse or replace the release
directory, mount a persistent volume at `DATA_DIR`/`DATABASE_PATH` so the
file survives. `scripts/smoke.sh` proves persistence: it creates a
"PERSIST" survivor task over HTTP, gracefully stops the production process,
restarts it on the **same database path**, and verifies the record is still
served with stable counts.

## Schema

Created by `src/main/java/dev/xcloudnobin/taskboard/Db.java`
(`CREATE TABLE IF NOT EXISTS`, idempotent):

- `project` — id, name, description, status (`active|archived`), timestamps.
- `task` — id, `project_id` FK (`ON DELETE CASCADE`), title, description,
  status (`todo|in_progress|done`), priority (`low|medium|high`), timestamps.
- `seed_flag` — marks the one-time seed as applied.
- `heartbeat` — backing table for the readiness write probe.

Seeding is repeatable: the second and subsequent `Db.open` calls never add
rows (`AppTest.seedDataIsIdempotent` asserts this).

## API

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/health/live` | liveness |
| GET | `/api/health/ready` | readiness (DB probe) |
| GET | `/api/meta` | release marker + runtime versions |
| GET/POST | `/api/projects` | list / create projects |
| GET/PATCH/DELETE | `/api/projects/{id}` | read / update / delete a project |
| GET/POST | `/api/tasks` | list (filters `q`, `status`, `priority`, `project_id`) / create tasks |
| GET/PATCH/DELETE | `/api/tasks/{id}` | read / update / delete a task |

The UI at `/` consumes the same JSON API.

## Automated verification

```bash
scripts/verify.sh
```

Runs, in order:

1. `scripts/build.sh` — writes `VERSION` marker and builds
   `target/deploy-test-java.jar`.
2. Clean dependency resolve — `mvn dependency:go-offline`.
3. `mvn test` — JUnit suite: CRUD, search/filter, validation negatives
   (blank/over-long/mistyped fields, invalid status/priority, malformed
   JSON, missing project, empty PATCH, 404s), LIKE-wildcard escaping, schema
   and seed idempotency, restart-style persistence, and readiness that
   genuinely returns 503 when the database is unavailable.
4. `scripts/smoke.sh` — real production process (`java -jar`):
   liveness/readiness, CRUD over HTTP, search/status filters, release marker,
   negative cases, graceful stop → restart persistence, database-unavailable
   readiness (503).

An optional end-to-end DB-outage check (move the live database file, watch
readiness drop, restore, watch it recover) is documented in
`VERIFICATION.md` and exercised via the blocked-path variant inside
`scripts/smoke.sh`.

Exit 0 only when every check passes.

## Repository layout

```
pom.xml                 pinned build + runtime versions (Java 21, shaded jar)
src/main/java/          config, sqlite db/schema/seed, JSON, server/router,
                        validation, Main entrypoint
src/main/resources/     public/ client UI (index.html, app.js, style.css)
src/test/java/          JUnit suite
scripts/                build.sh, smoke.sh, verify.sh
```

## License

MIT — see [LICENSE](LICENSE). This fixture is part of the MIT-licensed
[xCloud app-compatibility suite](https://github.com/xCloudNobin/app-compatibility).