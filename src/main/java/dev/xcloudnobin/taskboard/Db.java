package dev.xcloudnobin.taskboard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite persistence layer. The requested host application is a plain Java
 * program, so JDBC + org.xerial:sqlite-jdbc is the only runtime dependency.
 *
 * Schema setup is idempotent (CREATE TABLE IF NOT EXISTS) and seed data is
 * applied at most once, tracked by the seed_flag table.
 */
public final class Db {
    private final Path dbPath;
    private final Connection conn;

    private Db(Path dbPath, Connection conn) {
        this.dbPath = dbPath;
        this.conn = conn;
    }

    public static Db open(Path dbPath) {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON;");
                st.execute("PRAGMA journal_mode = WAL;");
            }
            Db db = new Db(dbPath, conn);
            db.migrate();
            db.seed();
            return db;
        } catch (SQLException | java.io.IOException e) {
            throw new RuntimeException("could not open database at " + dbPath + ": " + e.getMessage(), e);
        }
    }

    public Connection connection() {
        return conn;
    }

    public Path path() {
        return dbPath;
    }

    private void migrate() throws SQLException {
        String[] ddl = {
                "CREATE TABLE IF NOT EXISTS project (\n"
                        + "  id          INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                        + "  name        TEXT NOT NULL,\n"
                        + "  description TEXT NOT NULL DEFAULT '',\n"
                        + "  status      TEXT NOT NULL DEFAULT 'active'\n"
                        + "              CHECK (status IN ('active', 'archived')),\n"
                        + "  created_at  TEXT NOT NULL DEFAULT (datetime('now')),\n"
                        + "  updated_at  TEXT NOT NULL DEFAULT (datetime('now'))\n"
                        + ");",
                "CREATE TABLE IF NOT EXISTS task (\n"
                        + "  id          INTEGER PRIMARY KEY AUTOINCREMENT,\n"
                        + "  project_id  INTEGER NOT NULL REFERENCES project(id) ON DELETE CASCADE,\n"
                        + "  title       TEXT NOT NULL,\n"
                        + "  description TEXT NOT NULL DEFAULT '',\n"
                        + "  status      TEXT NOT NULL DEFAULT 'todo'\n"
                        + "              CHECK (status IN ('todo', 'in_progress', 'done')),\n"
                        + "  priority    TEXT NOT NULL DEFAULT 'medium'\n"
                        + "              CHECK (priority IN ('low', 'medium', 'high')),\n"
                        + "  created_at  TEXT NOT NULL DEFAULT (datetime('now')),\n"
                        + "  updated_at  TEXT NOT NULL DEFAULT (datetime('now'))\n"
                        + ");",
                "CREATE INDEX IF NOT EXISTS idx_task_project ON task(project_id, status);",
                "CREATE INDEX IF NOT EXISTS idx_task_status ON task(status);",
                "CREATE TABLE IF NOT EXISTS seed_flag (\n"
                        + "  id         INTEGER PRIMARY KEY CHECK (id = 1),\n"
                        + "  seeded_at  TEXT NOT NULL\n"
                        + ");",
                "CREATE TABLE IF NOT EXISTS heartbeat (\n"
                        + "  id          INTEGER PRIMARY KEY CHECK (id = 1),\n"
                        + "  checked_at  TEXT NOT NULL\n"
                        + ");",
        };
        try (Statement st = conn.createStatement()) {
            for (String sql : ddl) {
                st.execute(sql);
            }
        }
    }

    private void seed() throws SQLException {
        try (PreparedStatement flag = conn.prepareStatement("SELECT seeded_at FROM seed_flag WHERE id = 1")) {
            try (ResultSet rs = flag.executeQuery()) {
                if (rs.next()) {
                    return; // already seeded once
                }
            }
        }

        conn.setAutoCommit(false);
        try {
            long[] projectIds = new long[2];
            try (PreparedStatement insProject = conn.prepareStatement(
                    "INSERT INTO project (name, description, status) VALUES (?, ?, 'active')",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                insertProject(insProject, "Launch checklist",
                        "Demo project created by the idempotent seeder.", projectIds, 0);
                insertProject(insProject, "Support triage",
                        "Second demo board, also created once.", projectIds, 1);
            }

            try (PreparedStatement insTask = conn.prepareStatement(
                    "INSERT INTO task (project_id, title, description, status, priority) VALUES (?, ?, '', ?, ?)")) {
                insertTask(insTask, projectIds[0], "Write the launch blurb", "done", "high");
                insertTask(insTask, projectIds[0], "Schedule demo for the team", "in_progress", "medium");
                insertTask(insTask, projectIds[0], "Prepare rollback notes", "todo", "low");
                insertTask(insTask, projectIds[1], "Reproduce reported bug #42", "in_progress", "high");
            }

            try (PreparedStatement insFlag = conn.prepareStatement(
                    "REPLACE INTO seed_flag (id, seeded_at) VALUES (1, datetime('now'))")) {
                insFlag.executeUpdate();
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private void insertProject(PreparedStatement ps, String name, String description,
                               long[] ids, int index) throws SQLException {
        ps.setString(1, name);
        ps.setString(2, description);
        ps.executeUpdate();
        try (ResultSet keys = ps.getGeneratedKeys()) {
            if (keys.next()) {
                ids[index] = keys.getLong(1);
            }
        }
    }

    private void insertTask(PreparedStatement ps, long projectId, String title,
                            String status, String priority) throws SQLException {
        ps.setLong(1, projectId);
        ps.setString(2, title);
        ps.setString(3, status);
        ps.setString(4, priority);
        ps.executeUpdate();
    }

    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // best effort on shutdown
        }
    }

    /** Returns the java/OS release marker written at build time, if present. */
    public static String sqliteVersion() throws SQLException {
        try (Statement st = DriverManager.getConnection("jdbc:sqlite:").createStatement();
             ResultSet rs = st.executeQuery("select sqlite_version()")) {
            return rs.next() ? rs.getString(1) : "unknown";
        }
    }
}