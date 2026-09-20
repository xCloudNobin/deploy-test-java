package dev.xcloudnobin.taskboard;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppTest {
    private static Path workDir;
    private static Path dbPath;
    private static Db db;
    private static App app;
    private static String base;
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private static Config testConfig(String db) {
        return Config.fromEnv(name -> {
            return switch (name) {
                case "PORT" -> "0";
                case "BIND_HOST" -> "127.0.0.1";
                case "DATABASE_PATH" -> db;
                case "BUILD_MARKER" -> "test-marker";
                default -> null;
            };
        }, workDir);
    }

    @BeforeAll
    static void setUp() throws IOException {
        workDir = Files.createTempDirectory("java-taskboard-test-");
        dbPath = workDir.resolve("taskboard.db");
        db = Db.open(dbPath);
        app = new App(testConfig(dbPath.toString()), db);
        app.start();
        base = "http://127.0.0.1:" + app.port();
    }

    @AfterAll
    static void tearDown() throws IOException {
        app.stop();
        deleteRecursively(workDir);
    }

    @AfterEach
    void resetActiveProjectTracking() {
        // no global state to reset; each test picks its own project
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (dir == null || !Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    private HttpResponse<String> request(String method, String path, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path));
        if (body != null) {
            b.header("content-type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private long createProject(String name) throws Exception {
        HttpResponse<String> res = request("POST", "/api/projects",
                Json.write(Map.of("name", name, "description", "from test")));
        assertEquals(201, res.statusCode());
        Map<String, Object> body = Json.parseObject(res.body());
        Map<?, ?> project = (Map<?, ?>) body.get("project");
        return ((Number) project.get("id")).longValue();
    }

    private long createTask(long projectId, String title) throws Exception {
        HttpResponse<String> res = request("POST", "/api/tasks",
                Json.write(Map.of("project_id", projectId, "title", title,
                        "status", "todo", "priority", "medium")));
        assertEquals(201, res.statusCode());
        Map<String, Object> body = Json.parseObject(res.body());
        Map<?, ?> task = (Map<?, ?>) body.get("task");
        return ((Number) task.get("id")).longValue();
    }

    @Test
    void healthEndpoints() throws Exception {
        HttpResponse<String> live = request("GET", "/api/health/live", null);
        assertEquals(200, live.statusCode());
        Map<String, Object> liveBody = Json.parseObject(live.body());
        assertEquals("alive", liveBody.get("status"));

        HttpResponse<String> ready = request("GET", "/api/health/ready", null);
        assertEquals(200, ready.statusCode());
        Map<String, Object> readyBody = Json.parseObject(ready.body());
        assertEquals("ready", readyBody.get("status"));
        assertNotNull(readyBody.get("checked_at"));
    }

    @Test
    void metaExposesReleaseMarker() throws Exception {
        HttpResponse<String> res = request("GET", "/api/meta", null);
        assertEquals(200, res.statusCode());
        Map<String, Object> body = Json.parseObject(res.body());
        assertEquals("test-marker", body.get("release"));
        assertEquals("deploy-test-java", body.get("name"));
    }

    @Test
    void seedDataIsIdempotent() throws Exception {
        HttpResponse<String> res = request("GET", "/api/projects", null);
        assertEquals(200, res.statusCode());
        Map<String, Object> body = Json.parseObject(res.body());
        java.util.List<?> projects = (java.util.List<?>) body.get("projects");
        assertTrue(projects.size() >= 2);
        // re-open same file and count must not grow
        Db reopen = Db.open(dbPath);
        try {
            HttpResponse<String> again = request("GET", "/api/projects", null);
            assertEquals(200, again.statusCode());
            Map<String, Object> body2 = Json.parseObject(again.body());
            assertEquals(projects.size(), ((java.util.List<?>) body2.get("projects")).size());
        } finally {
            reopen.close();
        }
    }

    @Test
    void createProjectValidation() throws Exception {
        HttpResponse<String> blank = request("POST", "/api/projects",
                Json.write(Map.of("name", "   ")));
        assertEquals(400, blank.statusCode());
        Map<String, Object> blankBody = Json.parseObject(blank.body());
        assertTrue(blankBody.containsKey("fields"));
        assertTrue(String.valueOf(blankBody.get("fields")).contains("name"));

        HttpResponse<String> badStatus = request("POST", "/api/projects",
                Json.write(Map.of("name", "x", "status", "warp")));
        assertEquals(400, badStatus.statusCode());

        HttpResponse<String> malformed = request("POST", "/api/projects", "{nope");
        assertEquals(400, malformed.statusCode());
    }

    @Test
    void taskCrudAndSearchFilter() throws Exception {
        long projectId = createProject("CRUD project");
        long taskId = createTask(projectId, "Alpha task");

        HttpResponse<String> got = request("GET", "/api/tasks/" + taskId, null);
        assertEquals(200, got.statusCode());
        assertTrue(got.body().contains("Alpha task"));

        HttpResponse<String> updated = request("PATCH", "/api/tasks/" + taskId,
                Json.write(Map.of("title", "Alpha task updated", "status", "done",
                        "priority", "high")));
        assertEquals(200, updated.statusCode());
        assertTrue(updated.body().contains("Alpha task updated"));

        HttpResponse<String> searched = request("GET", "/api/tasks?q=Alpha", null);
        assertEquals(200, searched.statusCode());
        assertTrue(searched.body().contains("Alpha task updated"));

        HttpResponse<String> filtered = request("GET", "/api/tasks?status=done&project_id=" + projectId, null);
        assertEquals(200, filtered.statusCode());
        assertTrue(filtered.body().contains("Alpha task updated"));

        HttpResponse<String> excluded = request("GET", "/api/tasks?status=todo&project_id=" + projectId, null);
        assertEquals(200, excluded.statusCode());
        assertTrue(!excluded.body().contains("Alpha task updated"));

        HttpResponse<String> deleted = request("DELETE", "/api/tasks/" + taskId, null);
        assertEquals(204, deleted.statusCode());
        HttpResponse<String> gone = request("GET", "/api/tasks/" + taskId, null);
        assertEquals(404, gone.statusCode());
    }

    @Test
    void negativeValidation() throws Exception {
        long projectId = createProject("Negative project");
        String base = "{\"project_id\":" + projectId + ",";

        HttpResponse<String> blankTitle = request("POST", "/api/tasks", base + "\"title\":\"   \"}");
        assertEquals(400, blankTitle.statusCode());

        HttpResponse<String> missingProject = request("POST", "/api/tasks",
                Json.write(Map.of("title", "no project")));
        assertEquals(400, missingProject.statusCode());
        Map<String, Object> body = Json.parseObject(missingProject.body());
        assertTrue(String.valueOf(body.get("fields")).contains("project_id"));

        HttpResponse<String> badStatus = request("POST", "/api/tasks", base + "\"title\":\"x\",\"status\":\"warp\"}");
        assertEquals(400, badStatus.statusCode());

        HttpResponse<String> badPriority = request("POST", "/api/tasks", base + "\"title\":\"x\",\"priority\":\"urgent\"}");
        assertEquals(400, badPriority.statusCode());

        HttpResponse<String> unknownProject = request("POST", "/api/tasks",
                Json.write(Map.of("project_id", 999999, "title", "x")));
        assertEquals(400, unknownProject.statusCode());

        HttpResponse<String> notFound = request("GET", "/api/tasks/999999", null);
        assertEquals(404, notFound.statusCode());

        long taskId = createTask(projectId, "patch test");
        HttpResponse<String> emptyPatch = request("PATCH", "/api/tasks/" + taskId, "{}");
        assertEquals(400, emptyPatch.statusCode());

        HttpResponse<String> unhandled = request("PUT", "/api/tasks", "{}");
        assertEquals(404, unhandled.statusCode());

        HttpResponse<String> unknownApi = request("GET", "/api/unknown", null);
        assertEquals(404, unknownApi.statusCode());
    }

    @Test
    void projectCrud() throws Exception {
        long projectId = createProject("Project to rename");
        HttpResponse<String> updated = request("PATCH", "/api/projects/" + projectId,
                Json.write(Map.of("name", "Project renamed")));
        assertEquals(200, updated.statusCode());
        assertTrue(updated.body().contains("Project renamed"));

        HttpResponse<String> read = request("GET", "/api/projects/" + projectId, null);
        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("Project renamed"));

        HttpResponse<String> wrongStatus = request("PATCH", "/api/projects/" + projectId,
                Json.write(Map.of("status", "nope")));
        assertEquals(400, wrongStatus.statusCode());

        long taskId = createTask(projectId, "child task");
        HttpResponse<String> deleteWithTask = request("DELETE", "/api/projects/" + projectId, null);
        assertEquals(204, deleteWithTask.statusCode());
        // ON DELETE CASCADE
        HttpResponse<String> orphan = request("GET", "/api/tasks/" + taskId, null);
        assertEquals(404, orphan.statusCode());
    }

    @Test
    void persistenceAcrossRestart() throws Exception {
        Path restartDb = workDir.resolve("restart").resolve("taskboard.db");
        Files.createDirectories(restartDb.getParent());
        Db db1 = Db.open(restartDb);
        App app1 = new App(testConfig(restartDb.toString()), db1);
        app1.start();
        String base1 = "http://127.0.0.1:" + app1.port();
        try {
            long projectId = createProjectVia(base1, "Persistence project");
            long taskId = createTaskVia(base1, projectId, "PERSIST-survivor");
            app1.stop();

            Db db2 = Db.open(restartDb);
            App app2 = new App(testConfig(restartDb.toString()), db2);
            app2.start();
            String base2 = "http://127.0.0.1:" + app2.port();
            try {
                HttpRequest r = HttpRequest.newBuilder(URI.create(base2 + "/api/tasks/" + taskId)).GET().build();
                HttpResponse<String> res = CLIENT.send(r, HttpResponse.BodyHandlers.ofString());
                assertEquals(200, res.statusCode());
                assertTrue(res.body().contains("PERSIST-survivor"));
            } finally {
                app2.stop();
            }
        } finally {
            if (app1 != null) app1.stop();
        }
    }

    private long createProjectVia(String baseUrl, String name) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(baseUrl + "/api/projects"))
                .header("content-type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(Json.write(Map.of("name", name))))
                .build();
        HttpResponse<String> res = CLIENT.send(r, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode());
        Map<String, Object> body = Json.parseObject(res.body());
        Map<?, ?> project = (Map<?, ?>) body.get("project");
        return ((Number) project.get("id")).longValue();
    }

    private long createTaskVia(String baseUrl, long projectId, String title) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tasks"))
                .header("content-type", "application/json")
                .method("POST", HttpRequest.BodyPublishers.ofString(
                        Json.write(Map.of("project_id", projectId, "title", title))))
                .build();
        HttpResponse<String> res = CLIENT.send(r, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, res.statusCode());
        Map<String, Object> body = Json.parseObject(res.body());
        Map<?, ?> task = (Map<?, ?>) body.get("task");
        return ((Number) task.get("id")).longValue();
    }

    @Test
    void readinessFailsWhenDatabaseUnavailable() throws Exception {
        Path blocked = workDir.resolve("blocked.txt");
        Files.writeString(blocked, "a regular file, not a directory",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Path blockedDb = blocked.resolve("unreachable.db");

        boolean threw = false;
        try {
            Db.open(blockedDb);
        } catch (RuntimeException e) {
            threw = true;
        }
        assertTrue(threw, "opening a db under a regular file must fail");

        App degraded;
        try {
            degraded = new App(testConfig(blockedDb.toString()), null);
        } catch (Exception e) {
            // the app should still construct without a database
            degraded = null;
        }
        assertNotNull(degraded, "app must start even without a database");
        degraded.start();
        String baseUrl = "http://127.0.0.1:" + degraded.port();
        try {
            HttpRequest live = HttpRequest.newBuilder(URI.create(baseUrl + "/api/health/live")).GET().build();
            HttpResponse<String> liveRes = CLIENT.send(live, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, liveRes.statusCode());

            HttpRequest ready = HttpRequest.newBuilder(URI.create(baseUrl + "/api/health/ready")).GET().build();
            HttpResponse<String> readyRes = CLIENT.send(ready, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, readyRes.statusCode());
            assertTrue(readyRes.body().contains("unavailable"));

            HttpRequest list = HttpRequest.newBuilder(URI.create(baseUrl + "/api/projects")).GET().build();
            HttpResponse<String> listRes = CLIENT.send(list, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, listRes.statusCode());
        } finally {
            degraded.stop();
        }
    }

    @Test
    void uiServed() throws Exception {
        HttpResponse<String> index = request("GET", "/", null);
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("Java Taskboard"));

        HttpResponse<String> js = request("GET", "/app.js", null);
        assertEquals(200, js.statusCode());
        HttpResponse<String> css = request("GET", "/style.css", null);
        assertEquals(200, css.statusCode());

        HttpResponse<String> missing = request("GET", "/missing.txt", null);
        assertEquals(404, missing.statusCode());
    }

    @Test
    void invalidJsonReturns400WithMessage() throws Exception {
        HttpResponse<String> res = request("POST", "/api/tasks", "{bad json}");
        assertEquals(400, res.statusCode());
        assertTrue(res.body().contains("not valid JSON"));
    }
}