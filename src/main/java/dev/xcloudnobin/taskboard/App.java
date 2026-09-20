package dev.xcloudnobin.taskboard;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain JDK HTTP server (com.sun.net.httpserver) for the taskboard API and UI.
 *
 * Routes follow the shared fixture baseline:
 *   GET  /api/health/live    liveness
 *   GET  /api/health/ready   readiness (real SQLite probe)
 *   GET  /api/meta           release marker / runtime info
 *   GET  /api/projects       list projects (with task counts)
 *   POST /api/projects       create a project
 *   GET  /api/projects/{id}  project with its tasks
 *   PATCH /api/projects/{id} update a project
 *   DELETE /api/projects/{id}
 *   GET  /api/tasks          list tasks (q/status/priority/project_id filters)
 *   POST /api/tasks          create a task
 *   GET  /api/tasks/{id}
 *   PATCH /api/tasks/{id}
 *   DELETE /api/tasks/{id}
 */
public final class App {
    public static final List<String> TASK_STATUSES = List.of("todo", "in_progress", "done");
    public static final List<String> PRIORITIES = List.of("low", "medium", "high");
    public static final List<String> PROJECT_STATUSES = List.of("active", "archived");

    private static final int TITLE_MAX = 200;
    private static final int DESCRIPTION_MAX = 2000;
    private static final int NAME_MAX = 120;

    private final Config config;
    private final Db db;
    private final HttpServer server;

    public App(Config config, Db db) throws IOException {
        this.config = config;
        this.db = db;
        this.server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        if (db != null) {
            db.close();
        }
    }

    // ------------------------------------------------------------------
    // Routing
    // ------------------------------------------------------------------

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod().toUpperCase();
            String path = ex.getRequestURI().getPath();

            if (path.startsWith("/api/") || path.equals("/api")) {
                handleApi(ex, method, path);
                return;
            }

            if (!method.equals("GET") && !method.equals("HEAD")) {
                send(ex, 405, "text/plain; charset=utf-8",
                        "Method Not Allowed\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            serveStatic(ex, path);
        } catch (BadRequest e) {
            send(ex, 400, "application/json; charset=utf-8",
                    jsonBytes(Map.of("error", e.getMessage(), "fields", e.fields())));
        } catch (NotFound e) {
            send(ex, 404, "application/json; charset=utf-8",
                    jsonBytes(Map.of("error", e.getMessage())));
        } catch (ServiceUnavailable e) {
            send(ex, 503, "application/json; charset=utf-8",
                    jsonBytes(Map.of("error", "Service Unavailable", "db", "unavailable")));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            e.printStackTrace(System.err);
            send(ex, 500, "application/json; charset=utf-8",
                    jsonBytes(Map.of("error", "Internal Server Error")));
        }
    }

    private void handleApi(HttpExchange ex, String method, String path) throws IOException {
        Route route = findRoute(method, path);
        if (route == null) {
            if (method.equals("OPTIONS")) {
                send(ex, 204, "", new byte[0]);
                return;
            }
            throw new NotFound("Not found");
        }
        route.handler().handle(ex);
    }

    // ------------------------------------------------------------------
    // Static content
    // ------------------------------------------------------------------

    private static final Map<String, String> MIME = Map.of(
            ".html", "text/html; charset=utf-8",
            ".js", "text/javascript; charset=utf-8",
            ".css", "text/css; charset=utf-8",
            ".svg", "image/svg+xml",
            ".ico", "image/x-icon"
    );

    private void serveStatic(HttpExchange ex, String path) throws IOException {
        String rel = path.equals("/") ? "/index.html" : path;
        if (rel.contains("..")) {
            send(ex, 404, "text/html; charset=utf-8", notFoundHtml());
            return;
        }
        String resource = "/public" + rel;
        try (InputStream in = App.class.getResourceAsStream(resource)) {
            if (in == null) {
                send(ex, 404, "text/html; charset=utf-8", notFoundHtml());
                return;
            }
            byte[] body = in.readAllBytes();
            String mime = lookupMime(rel);
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(body);
            }
        }
    }

    private static byte[] notFoundHtml() {
        return ("<!doctype html><meta charset=\"utf-8\"><title>Not found</title>"
                + "<h1>404 Not Found</h1>").getBytes(StandardCharsets.UTF_8);
    }

    private static String lookupMime(String rel) {
        int dot = rel.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return MIME.getOrDefault(rel.substring(dot), "application/octet-stream");
    }

    // ------------------------------------------------------------------
    // API handlers
    // ------------------------------------------------------------------

    private byte[] jsonBytes(Object value) {
        return Json.write(value).getBytes(StandardCharsets.UTF_8);
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null && !contentType.isEmpty()) {
            ex.getResponseHeaders().set("Content-Type", contentType);
        }
        if (body.length == 0) {
            ex.sendResponseHeaders(status, -1);
        } else {
            ex.sendResponseHeaders(status, body.length);
            ex.getResponseBody().write(body);
        }
        ex.close();
    }

    private String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------
    // Handler definitions
    // ------------------------------------------------------------------

    private interface ApiHandler {
        void handle(HttpExchange ex) throws IOException;
    }

    private record Route(String method, String pattern, ApiHandler handler) {
    }

    private Route findRoute(String method, String path) {
        if (method.equals("GET") && (path.equals("/api/health/live") || path.equals("/api/health/live/"))) {
            return new Route(method, path, ex -> send(ex, 200, "application/json; charset=utf-8",
                    jsonBytes(Map.of("status", "alive", "timestamp", nowIso()))));
        }
        if (method.equals("GET") && (path.equals("/api/health/ready") || path.equals("/api/health/ready/"))) {
            return new Route(method, path, ex -> handleReady(ex));
        }
        if (method.equals("GET") && (path.equals("/api/meta") || path.equals("/api/meta/"))) {
            return new Route(method, path, ex -> handleMeta(ex));
        }

        if (path.equals("/api/projects") || path.equals("/api/projects/")) {
            return switch (method) {
                case "GET" -> new Route(method, path, ex -> handleListProjects(ex));
                case "POST" -> new Route(method, path, ex -> handleCreateProject(ex));
                default -> null;
            };
        }

        String projectById = "^/api/projects/(\\d+)/?$";
        if (path.matches(projectById)) {
            long id = parseLongPathVariable(path);
            int start = path.indexOf("/api/projects/") + "/api/projects/".length();
            String raw = path.substring(start);
            return switch (method) {
                case "GET" -> new Route(method, path, ex -> handleGetProject(ex, id));
                case "PATCH" -> new Route(method, path, ex -> handleUpdateProject(ex, id));
                case "DELETE" -> new Route(method, path, ex -> handleDeleteProject(ex, id));
                default -> null;
            };
        }

        if (path.equals("/api/tasks") || path.equals("/api/tasks/")) {
            return switch (method) {
                case "GET" -> new Route(method, path, ex -> handleListTasks(ex));
                case "POST" -> new Route(method, path, ex -> handleCreateTask(ex));
                default -> null;
            };
        }

        String taskById = "^/api/tasks/(\\d+)/?$";
        if (path.matches(taskById)) {
            long id = parseLongPathVariable(path);
            return switch (method) {
                case "GET" -> new Route(method, path, ex -> handleGetTask(ex, id));
                case "PATCH" -> new Route(method, path, ex -> handleUpdateTask(ex, id));
                case "DELETE" -> new Route(method, path, ex -> handleDeleteTask(ex, id));
                default -> null;
            };
        }

        return null;
    }

    private static long parseLongPathVariable(String path) {
        String[] parts = path.split("/");
        try {
            return Long.parseLong(parts[parts.length - 1]);
        } catch (NumberFormatException e) {
            throw new NotFound("Not found");
        }
    }

    private static String nowIso() {
        return java.time.Instant.now().toString();
    }

    private void handleReady(HttpExchange ex) throws IOException {
        try {
            ProbeResult probe = probeDb(config.dbPath());
            if (probe.ok()) {
                send(ex, 200, "application/json; charset=utf-8",
                        jsonBytes(Map.of("status", "ready", "db", "sqlite", "checked_at", probe.detail())));
            } else {
                send(ex, 503, "application/json; charset=utf-8",
                        jsonBytes(Map.of("status", "unavailable", "db", "sqlite", "detail", probe.detail())));
            }
        } catch (Exception e) {
            send(ex, 503, "application/json; charset=utf-8",
                    jsonBytes(Map.of("status", "unavailable", "db", "sqlite",
                            "detail", String.valueOf(e.getMessage()))));
        }
    }

    private void handleMeta(HttpExchange ex) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "deploy-test-java");
        body.put("description",
                "Plain Java taskboard (com.sun.net.httpserver): SQLite JDBC persistence, validated CRUD, search/filter.");
        body.put("release", config.buildMarker());
        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("name", "java");
        runtime.put("version", System.getProperty("java.version"));
        body.put("runtime", runtime);
        Map<String, Object> database = new LinkedHashMap<>();
        database.put("engine", "sqlite");
        database.put("path", config.dbPath());
        try {
            database.put("sqlite_version", Db.sqliteVersion());
        } catch (SQLException e) {
            database.put("sqlite_version", "unknown");
        }
        body.put("database", database);
        send(ex, 200, "application/json; charset=utf-8", jsonBytes(body));
    }

    private void handleListProjects(HttpExchange ex) throws IOException {
        requireDb();
        List<Map<String, Object>> projects = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement("""
                SELECT p.id, p.name, p.description, p.status, p.created_at, p.updated_at,
                       COUNT(t.id) AS task_total,
                       SUM(CASE WHEN t.status = 'todo' THEN 1 ELSE 0 END) AS todo,
                       SUM(CASE WHEN t.status = 'in_progress' THEN 1 ELSE 0 END) AS in_progress,
                       SUM(CASE WHEN t.status = 'done' THEN 1 ELSE 0 END) AS done
                  FROM project p LEFT JOIN task t ON t.project_id = p.id
                 GROUP BY p.id ORDER BY p.id ASC
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> p = projectFrom(rs);
                    p.put("task_total", rs.getLong("task_total"));
                    p.put("todo", rs.getLong("todo"));
                    p.put("in_progress", rs.getLong("in_progress"));
                    p.put("done", rs.getLong("done"));
                    projects.add(p);
                }
            }
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
        send(ex, 200, "application/json; charset=utf-8", jsonBytes(Map.of("projects", projects)));
    }

    private void handleCreateProject(HttpExchange ex) throws IOException {
        requireDb();
        Map<String, Object> body = parseObjectBody(ex);
        ProjectInput input = validateProjectInput(body);
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO project (name, description, status) VALUES (?, ?, ?)")) {
            ps.setString(1, input.name());
            ps.setString(2, input.description());
            ps.setString(3, input.status());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
        long id = lastId("project");
        Map<String, Object> project = getProject(id);
        send(ex, 201, "application/json; charset=utf-8", jsonBytes(Map.of("project", project)));
    }

    private long lastId(String table) {
        String sql = "SELECT last_insert_rowid() AS id";
        if (table.equals("project") || table.equals("task")) {
            sql = "SELECT last_insert_rowid() AS id";
        }
        try (PreparedStatement ps = db.connection().prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("id") : -1;
            }
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
    }

    private void handleGetProject(HttpExchange ex, long id) throws IOException {
        requireDb();
        Map<String, Object> project = getProject(id);
        if (project == null) {
            throw new NotFound("Project not found");
        }
        List<Map<String, Object>> tasks = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(
                "SELECT * FROM task WHERE project_id = ? ORDER BY id DESC")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(taskFrom(rs));
                }
            }
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
        send(ex, 200, "application/json; charset=utf-8",
                jsonBytes(Map.of("project", project, "tasks", tasks)));
    }

    private void handleUpdateProject(HttpExchange ex, long id) throws IOException {
        requireDb();
        Map<String, Object> current = getProject(id);
        if (current == null) {
            throw new NotFound("Project not found");
        }
        Map<String, Object> body = parseObjectBody(ex);
        if (!body.containsKey("name") && !body.containsKey("description") && !body.containsKey("status")) {
            throw new BadRequest("Nothing to update: provide at least one of name, description, status");
        }
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("name", body.getOrDefault("name", current.get("name")));
        merged.put("description", body.getOrDefault("description", current.get("description")));
        merged.put("status", body.getOrDefault("status", current.get("status")));
        ProjectInput input = validateProjectInput(merged);
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE project SET name = ?, description = ?, status = ?, updated_at = datetime('now') WHERE id = ?")) {
            ps.setString(1, input.name());
            ps.setString(2, input.description());
            ps.setString(3, input.status());
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
        send(ex, 200, "application/json; charset=utf-8",
                jsonBytes(Map.of("project", getProject(id))));
    }

    private void handleDeleteProject(HttpExchange ex, long id) throws IOException {
        requireDb();
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM project WHERE id = ?")) {
            ps.setLong(1, id);
            int changed = ps.executeUpdate();
            if (changed == 0) {
                throw new NotFound("Project not found");
            }
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
        send(ex, 204, "", new byte[0]);
    }

    private void handleListTasks(HttpExchange ex) throws IOException {
        requireDb();
        var params = ex.getRequestURI().getQuery() == null ? Map.<String, String>of()
                : splitQuery(ex.getRequestURI().getQuery());
        String q = params.getOrDefault("q", "").trim();
        String statusFilter = params.getOrDefault("status", null);
        String priorityFilter = params.getOrDefault("priority", null);
        String projectRaw = params.getOrDefault("project_id", null);
        Long projectId = null;
        if (projectRaw != null && !projectRaw.isBlank()) {
            try {
                projectId = Long.parseLong(projectRaw);
                if (projectId < 1) {
                    throw new BadRequest("project_id must be a positive integer");
                }
            } catch (NumberFormatException e) {
                throw new BadRequest("project_id must be a positive integer");
            }
        }

        if (statusFilter != null && !TASK_STATUSES.contains(statusFilter)) {
            throw new BadRequest("status filter must be one of: " + String.join(", ", TASK_STATUSES));
        }
        if (priorityFilter != null && !PRIORITIES.contains(priorityFilter)) {
            throw new BadRequest("priority filter must be one of: " + String.join(", ", PRIORITIES));
        }

        String like = (q == null || q.isEmpty()) ? null
                : "%" + escapeLike(q) + "%";

        StringBuilder sql = new StringBuilder("""
                SELECT t.id, t.project_id, p.name AS project_name, t.title, t.description,
                       t.status, t.priority, t.created_at, t.updated_at
                  FROM task t JOIN project p ON p.id = t.project_id
                 WHERE (1 = 1)
                """);
        List<Object> args = new ArrayList<>();
        if (projectId != null) {
            sql.append(" AND t.project_id = ?");
            args.add(projectId);
        }
        if (statusFilter != null) {
            sql.append(" AND t.status = ?");
            args.add(statusFilter);
        }
        if (priorityFilter != null) {
            sql.append(" AND t.priority = ?");
            args.add(priorityFilter);
        }
        if (like != null) {
            sql.append(" AND (t.title LIKE ? ESCAPE '\\' OR t.description LIKE ? ESCAPE '\\')");
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY t.id DESC");

        List<Map<String, Object>> tasks = new ArrayList<>();
        try (PreparedStatement ps = db.connection().prepareStatement(sql.toString())) {
            for (int i = 0; i < args.size(); i++) {
                ps.setObject(i + 1, args.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(taskFrom(rs));
                }
            }
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("q", q);
        query.put("status", statusFilter);
        query.put("priority", priorityFilter);
        query.put("project_id", projectId);
        send(ex, 200, "application/json; charset=utf-8",
                jsonBytes(Map.of("tasks", tasks, "count", tasks.size(), "query", query)));
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private Map<String, String> splitQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                out.put(java.net.URLDecoder.decode(part, StandardCharsets.UTF_8), "");
            } else {
                out.put(java.net.URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private void handleCreateTask(HttpExchange ex) throws IOException {
        requireDb();
        Map<String, Object> body = parseObjectBody(ex);
        TaskInput input = validateTaskInput(body, true);
        if (getProject(input.projectId()) == null) {
            throw new BadRequest("project_id does not exist", Map.of("project_id", "no project with that id"));
        }
        try (PreparedStatement ps = db.connection().prepareStatement(
                "INSERT INTO task (project_id, title, description, status, priority) VALUES (?, ?, ?, ?, ?)")) {
            ps.setLong(1, input.projectId());
            ps.setString(2, input.title());
            ps.setString(3, input.description());
            ps.setString(4, input.status());
            ps.setString(5, input.priority());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
        long id = lastId("task");
        send(ex, 201, "application/json; charset=utf-8", jsonBytes(Map.of("task", getTask(id))));
    }

    private void handleGetTask(HttpExchange ex, long id) throws IOException {
        requireDb();
        Map<String, Object> task = getTask(id);
        if (task == null) {
            throw new NotFound("Task not found");
        }
        send(ex, 200, "application/json; charset=utf-8", jsonBytes(Map.of("task", task)));
    }

    private void handleUpdateTask(HttpExchange ex, long id) throws IOException {
        requireDb();
        Map<String, Object> current = getTask(id);
        if (current == null) {
            throw new NotFound("Task not found");
        }
        Map<String, Object> body = parseObjectBody(ex);
        List<String> keys = List.of("title", "description", "status", "priority", "project_id");
        if (keys.stream().noneMatch(body::containsKey)) {
            throw new BadRequest("Nothing to update: provide at least one of title, description, status, priority");
        }
        TaskInput input = validateTaskInput(body, false, current);
        if (input.projectId() != 0 && getProject(input.projectId()) == null) {
            throw new BadRequest("project_id does not exist", Map.of("project_id", "no project with that id"));
        }
        long targetProject = input.projectId() != 0 ? input.projectId() : (Long) current.get("project_id");
        try (PreparedStatement ps = db.connection().prepareStatement(
                "UPDATE task SET project_id = ?, title = ?, description = ?, status = ?, priority = ?, updated_at = datetime('now') WHERE id = ?")) {
            ps.setLong(1, targetProject);
            ps.setString(2, input.title());
            ps.setString(3, input.description());
            ps.setString(4, input.status());
            ps.setString(5, input.priority());
            ps.setLong(6, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
        send(ex, 200, "application/json; charset=utf-8", jsonBytes(Map.of("task", getTask(id))));
    }

    private void handleDeleteTask(HttpExchange ex, long id) throws IOException {
        requireDb();
        try (PreparedStatement ps = db.connection().prepareStatement("DELETE FROM task WHERE id = ?")) {
            ps.setLong(1, id);
            int changed = ps.executeUpdate();
            if (changed == 0) {
                throw new NotFound("Task not found");
            }
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
        send(ex, 204, "", new byte[0]);
    }

    // ------------------------------------------------------------------
    // Persistence accessors
    // ------------------------------------------------------------------

    private void requireDb() {
        if (db == null) {
            throw new ServiceUnavailable("database unavailable");
        }
    }

    public Map<String, Object> getProject(long id) {
        try (PreparedStatement ps = db.connection().prepareStatement("SELECT * FROM project WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return projectFrom(rs);
            }
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
    }

    public Map<String, Object> getTask(long id) {
        try (PreparedStatement ps = db.connection().prepareStatement("""
                SELECT t.id, t.project_id, p.name AS project_name, t.title, t.description,
                       t.status, t.priority, t.created_at, t.updated_at
                  FROM task t JOIN project p ON p.id = t.project_id WHERE t.id = ?
                """)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return taskFrom(rs);
            }
        } catch (SQLException e) {
            throw new ServiceUnavailable("database unavailable");
        }
    }

    private static Map<String, Object> projectFrom(ResultSet rs) throws SQLException {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", rs.getLong("id"));
        p.put("name", rs.getString("name"));
        p.put("description", rs.getString("description") == null ? "" : rs.getString("description"));
        p.put("status", rs.getString("status"));
        p.put("created_at", rs.getString("created_at"));
        p.put("updated_at", rs.getString("updated_at"));
        return p;
    }

    private static Map<String, Object> taskFrom(ResultSet rs) throws SQLException {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", rs.getLong("id"));
        t.put("project_id", rs.getLong("project_id"));
        try {
            t.put("project_name", rs.getString("project_name"));
        } catch (SQLException e) {
            // column absent when selecting task by itself
        }
        t.put("title", rs.getString("title"));
        t.put("description", rs.getString("description") == null ? "" : rs.getString("description"));
        t.put("status", rs.getString("status"));
        t.put("priority", rs.getString("priority"));
        t.put("created_at", rs.getString("created_at"));
        t.put("updated_at", rs.getString("updated_at"));
        return t;
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private Map<String, Object> parseObjectBody(HttpExchange ex) throws IOException {
        String raw = readBody(ex);
        if (raw == null || raw.isBlank()) {
            throw new BadRequest("Request body must be a JSON object");
        }
        try {
            Object parsed = Json.parse(raw);
            if (!(parsed instanceof Map<?, ?>)) {
                throw new BadRequest("Request body must be a JSON object");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        } catch (Json.JsonParseException e) {
            throw new BadRequest("Request body is not valid JSON: " + e.getMessage());
        }
    }

    private static String optionalString(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String)) {
            throw new BadRequest("Field must be a string");
        }
        return (String) value;
    }

    private record ProjectInput(String name, String description, String status) {
    }

    private ProjectInput validateProjectInput(Map<String, Object> body) {
        Map<String, String> fields = new LinkedHashMap<>();

        String name = null;
        Object nameValue = body.get("name");
        if (!(nameValue instanceof String)) {
            fields.put("name", "name is required and must be a string");
        } else {
            name = ((String) nameValue).trim();
            if (name.isEmpty()) {
                fields.put("name", "name is required and must not be blank");
            } else if (name.length() > NAME_MAX) {
                fields.put("name", "name must be " + NAME_MAX + " characters or fewer");
            }
        }

        String description = "";
        String descValue = optionalString(body.get("description"));
        if (descValue != null) {
            description = descValue.trim();
            if (description.length() > DESCRIPTION_MAX) {
                fields.put("description", "description must be " + DESCRIPTION_MAX + " characters or fewer");
            }
        }

        String status = "active";
        Object statusValue = body.get("status");
        if (statusValue != null && !statusValue.toString().isEmpty()) {
            if (!PROJECT_STATUSES.contains(statusValue.toString())) {
                fields.put("status", "Status must be one of: " + String.join(", ", PROJECT_STATUSES));
            } else {
                status = statusValue.toString();
            }
        }

        if (!fields.isEmpty()) {
            throw new BadRequest("Validation failed", fields);
        }
        return new ProjectInput(name, description, status);
    }

    private record TaskInput(long projectId, String title, String description,
                             String status, String priority) {
    }

    private TaskInput validateTaskInput(Map<String, Object> body, boolean requireProject) {
        return validateTaskInput(body, requireProject, null);
    }

    private TaskInput validateTaskInput(Map<String, Object> body, boolean requireProject,
                                        Map<String, Object> current) {
        Map<String, String> fields = new LinkedHashMap<>();

        long projectId = 0;
        Object projectValue = body.get("project_id");
        if (projectValue == null) {
            if (requireProject && current == null) {
                fields.put("project_id", "project_id is required");
            } else if (current != null) {
                projectId = (Long) current.get("project_id");
            }
        } else if (projectValue instanceof Number n && n.longValue() >= 1) {
            projectId = n.longValue();
        } else {
            fields.put("project_id", "project_id must be a positive integer");
        }

        String title = "";
        Object titleValue = body.get("title");
        if (!(titleValue instanceof String)) {
            if (current != null && !body.containsKey("title")) {
                title = (String) current.get("title");
            } else {
                fields.put("title", "title is required and must be a string");
            }
        } else {
            title = ((String) titleValue).trim();
            if (title.isEmpty()) {
                fields.put("title", "title is required and must not be blank");
            } else if (title.length() > TITLE_MAX) {
                fields.put("title", "title must be " + TITLE_MAX + " characters or fewer");
            }
        }

        String description = "";
        String descValue = optionalString(body.get("description"));
        if (descValue != null) {
            description = descValue.trim();
            if (description.length() > DESCRIPTION_MAX) {
                fields.put("description", "description must be " + DESCRIPTION_MAX + " characters or fewer");
            }
        } else if (current != null && !body.containsKey("description")) {
            description = (String) current.get("description");
        }

        String status = "todo";
        Object statusValue = body.get("status");
        if (statusValue != null && !statusValue.toString().isEmpty()) {
            if (!TASK_STATUSES.contains(statusValue.toString())) {
                fields.put("status", "Status must be one of: " + String.join(", ", TASK_STATUSES));
            } else {
                status = statusValue.toString();
            }
        } else if (current != null && !body.containsKey("status")) {
            status = (String) current.get("status");
        }

        String priority = "medium";
        Object priorityValue = body.get("priority");
        if (priorityValue != null && !priorityValue.toString().isEmpty()) {
            if (!PRIORITIES.contains(priorityValue.toString())) {
                fields.put("priority", "Priority must be one of: " + String.join(", ", PRIORITIES));
            } else {
                priority = priorityValue.toString();
            }
        } else if (current != null && !body.containsKey("priority")) {
            priority = (String) current.get("priority");
        }

        if (!fields.isEmpty()) {
            throw new BadRequest("Validation failed", fields);
        }
        return new TaskInput(projectId, title, description, status, priority);
    }

    // ------------------------------------------------------------------
    // Error types
    // ------------------------------------------------------------------

    public static class BadRequest extends RuntimeException {
        private final Map<String, String> fields;

        public BadRequest(String message) {
            this(message, Map.of());
        }

        public BadRequest(String message, Map<String, String> fields) {
            super(message);
            this.fields = fields;
        }

        public Map<String, String> fields() {
            return fields;
        }
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    public static class ServiceUnavailable extends RuntimeException {
        public ServiceUnavailable(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------
    // Readiness probe (real database round-trip)
    // ------------------------------------------------------------------

    public record ProbeResult(boolean ok, String detail) {
    }

    public static ProbeResult probeDb(String dbPath) {
        java.sql.Connection conn = null;
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON;");
                st.execute("INSERT OR REPLACE INTO heartbeat (id, checked_at) VALUES (1, datetime('now'))");
                try (ResultSet rs = st.executeQuery("SELECT checked_at FROM heartbeat WHERE id = 1")) {
                    String checkedAt = rs.next() ? rs.getString(1) : "";
                    return new ProbeResult(true, checkedAt);
                }
            }
        } catch (Exception e) {
            String detail = e.getMessage() == null ? "database probe failed" : e.getMessage();
            return new ProbeResult(false, detail);
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
        }
    }
}