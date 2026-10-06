package com.revealz.backend.ops;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.revealz.backend.web.ApiException;

@Service
class OpsProcessService {
    private final Path backupDir;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String pgDump;
    private final String pgRestore;

    OpsProcessService(@Value("${ops.data-dir:./ops-data}") String dataDir,
            @Value("${catalog.datasource.url:}") String jdbcUrl,
            @Value("${catalog.datasource.username:}") String username,
            @Value("${catalog.datasource.password:}") String password,
            @Value("${ops.pg-dump:pg_dump}") String pgDump,
            @Value("${ops.pg-restore:pg_restore}") String pgRestore) {
        backupDir = Path.of(dataDir).toAbsolutePath().normalize().resolve("backups");
        this.jdbcUrl = jdbcUrl; this.username = username; this.password = password; this.pgDump = pgDump; this.pgRestore = pgRestore;
    }

    List<Map<String, Object>> list() {
        if (!Files.isDirectory(backupDir)) return List.of();
        try (var paths = Files.list(backupDir)) {
            return paths.filter(this::allowed).map(this::metadata)
                    .sorted(Comparator.comparing(item -> String.valueOf(item.get("mtime")), Comparator.reverseOrder())).toList();
        } catch (IOException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "backup_list_failed"); }
    }

    Map<String, Object> backup(String rawLabel) {
        String label = rawLabel == null ? "" : rawLabel.trim().replaceAll("[\\\\/:*?\"<>|]", "").replaceAll("\\s+", "_");
        if (label.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "backup_label_required");
        if (label.length() > 80) label = label.substring(0, 80);
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC).format(Instant.now());
        Path output = backupDir.resolve(stamp + "_" + label + ".dump");
        try { Files.createDirectories(backupDir); run(pgDump, List.of("-Fc", "-h", database().host, "-p", database().port,
                "-U", username, "-d", database().name, "-f", output.toString()), 120); }
        catch (IOException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "backup_failed"); }
        Map<String, Object> file = metadata(output);
        return Map.of("ok", true, "name", file.get("name"), "bytes", file.get("bytes"), "backups", list());
    }

    Map<String, Object> restore(String rawName) {
        String requested = rawName == null ? "" : rawName.trim();
        if (requested.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "backup_name_required");
        String name = Path.of(requested).getFileName().toString();
        Path file = backupDir.resolve(name).normalize();
        if (!file.getParent().equals(backupDir) || !allowed(file)) throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_backup_name");
        if (!Files.isRegularFile(file)) throw new ApiException(HttpStatus.NOT_FOUND, "backup_not_found");
        try { run(pgRestore, List.of("-Fc", "-h", database().host, "-p", database().port, "-U", username,
                "-d", database().name, "--clean", "--if-exists", file.toString()), 300); }
        catch (IOException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "restore_failed"); }
        return Map.of("ok", true, "restored", name);
    }

    private void run(String binary, List<String> args, long timeoutSeconds) throws IOException {
        List<String> command = new java.util.ArrayList<>(); command.add(binary); command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command); builder.environment().put("PGPASSWORD", password);
        Process process = builder.redirectErrorStream(true).start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("timeout"); }
            if (process.exitValue() != 0) throw new IOException("exit=" + process.exitValue());
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IOException(exception); }
    }

    private Database database() {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "meta_db_not_configured");
        URI uri = URI.create(jdbcUrl.substring(5));
        return new Database(uri.getHost(), uri.getPort() > 0 ? String.valueOf(uri.getPort()) : "5432", uri.getPath().replaceFirst("^/", ""));
    }
    private boolean allowed(Path path) { String name = path.getFileName().toString(); return name.endsWith(".dump") || name.endsWith(".sql"); }
    private Map<String, Object> metadata(Path path) {
        try { return Map.of("name", path.getFileName().toString(), "bytes", Files.size(path),
                "mtime", Files.getLastModifiedTime(path).toInstant().toString()); }
        catch (IOException exception) { throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "backup_stat_failed"); }
    }
    private record Database(String host, String port, String name) { }
}
