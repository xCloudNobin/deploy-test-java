package dev.xcloudnobin.taskboard;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Entry point for the plain Java taskboard.
 *
 * The server binds to BIND_HOST:PORT (default 0.0.0.0:8080), opens the SQLite
 * database at DATABASE_PATH (default DATA_DIR/taskboard.db) and answers
 * SIGTERM/SIGINT with a clean shutdown.
 *
 * If the database cannot be opened at startup (bad path, no disk space, ...)
 * the process still starts and serves liveness while readiness and database
 * routes report 503 - this keeps the health contract honest when a durable
 * store is missing.
 */
public final class Main {
    public static void main(String[] args) throws IOException {
        Config config = Config.fromEnv(Config.systemEnv(), Path.of("").toAbsolutePath());
        Path dbPath = Path.of(config.dbPath());

        Db db;
        try {
            db = Db.open(dbPath);
        } catch (RuntimeException e) {
            System.err.println("[taskboard] FATAL: could not open database at "
                    + config.dbPath() + "; serving liveness only, readiness and "
                    + "API routes will report 503. " + e.getMessage());
            db = null;
        }

        App app = new App(config, db);
        app.start();

        String bind = config.bind();
        // when PORT=0 is used for tests, report the actual bound port
        int boundPort = app.port();
        String shownBind = config.port() == 0 ? bind + ":" + boundPort
                : bind + ":" + config.port();
        System.out.printf("[taskboard] serving %s (java %s) at http://%s%n",
                config.buildMarker(), System.getProperty("java.version"), shownBind);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[taskboard] received shutdown, closing cleanly");
            app.stop();
        }, "taskboard-shutdown"));
    }

    private Main() {
    }
}