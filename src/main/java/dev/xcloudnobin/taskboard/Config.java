package dev.xcloudnobin.taskboard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runtime configuration sourced from environment variables (see .env.example).
 * There are no secrets here: only bind address, port and database paths.
 */
public final class Config {
    private final int port;
    private final String bind;
    private final String dataDir;
    private final String dbPath;
    private final String buildMarker;
    private final String baseDir;

    private Config(int port, String bind, String dataDir, String dbPath,
                   String buildMarker, String baseDir) {
        this.port = port;
        this.bind = bind;
        this.dataDir = dataDir;
        this.dbPath = dbPath;
        this.buildMarker = buildMarker;
        this.baseDir = baseDir;
    }

    public int port() {
        return port;
    }

    public String bind() {
        return bind;
    }

    public String dataDir() {
        return dataDir;
    }

    public String dbPath() {
        return dbPath;
    }

    public String buildMarker() {
        return buildMarker;
    }

    public String baseDir() {
        return baseDir;
    }

    public static Config fromEnv(Env env, Path baseDir) {
        String portRaw = env.getValue("PORT", "8080").trim();
        int port;
        try {
            port = Integer.parseInt(portRaw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("PORT must be an integer, got \"" + portRaw + "\"");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("PORT must be in [0, 65535], got \"" + portRaw + "\"");
        }

        String bind = env.getValue("BIND_HOST", "0.0.0.0").trim();

        String dataDir = env.getValue("DATA_DIR", baseDir.resolve("data").toString());
        String dbPath = env.getValue("DATABASE_PATH", Path.of(dataDir).resolve("taskboard.db").toString());

        String buildMarker = env.getValue("BUILD_MARKER", "").trim();
        if (buildMarker.isEmpty()) {
            buildMarker = readVersionFile(baseDir);
        }
        if (buildMarker.isEmpty()) {
            buildMarker = "develop";
        }

        return new Config(port, bind, dataDir, dbPath, buildMarker, baseDir.toString());
    }

    private static String readVersionFile(Path baseDir) {
        Path versionFile = baseDir.resolve("VERSION");
        if (Files.exists(versionFile)) {
            try {
                return Files.readString(versionFile, StandardCharsets.UTF_8).trim();
            } catch (IOException ignored) {
                // fall through to develop marker
            }
        }
        return "";
    }

    /** Small abstraction so tests can inject a fixed environment. */
    @FunctionalInterface
    public interface Env {
        String get(String name);

        default String getValue(String name, String fallback) {
            String v = get(name);
            return (v == null || v.isBlank()) ? fallback : v;
        }
    }

    public static Env systemEnv() {
        return System::getenv;
    }
}