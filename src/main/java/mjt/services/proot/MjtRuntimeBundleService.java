package main.java.mjt.services.proot;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import main.java.mjt.system.StateStore;

/**
 * Installs an MJT-owned runtime bundle without relying on host Python, host PRoot,
 * curl, unzip, or a package manager. The bundle is fetched over HTTPS, checked
 * against an owner-provided SHA-256, safely extracted, and then used by
 * {@link ProotDistroService}.
 *
 * <p>The release bundle is intentionally an opaque deployment artifact. It must
 * contain a native PRoot binary, an embedded Python runtime, a pinned upstream
 * proot-distro launcher, and a bundle.properties integrity manifest. MJT never
 * builds or downloads unpinned third-party binaries at runtime.</p>
 */
public final class MjtRuntimeBundleService {
    static final int BUNDLE_FORMAT = 1;
    private static final long MAX_BUNDLE_BYTES = 768L * 1024L * 1024L;
    private static final String KEY_PREFIX = "proot.distro.runtime.bundle.";
    private static final String USER_AGENT = "MJT-Runtime-Bundle/1";

    public record BundleInfo(
            boolean configured,
            boolean ready,
            String architecture,
            String version,
            String root,
            String python,
            String proot,
            String engine,
            String engineVersion,
            String sourceHost,
            String message
    ) {
    }

    private final StateStore stateStore;

    MjtRuntimeBundleService(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    synchronized void initializeDirectories() throws IOException {
        Files.createDirectories(runtimeRoot());
        Files.createDirectories(runtimeRoot().resolve("bundles"));
    }

    synchronized BundleInfo info(String architecture) {
        try {
            initializeDirectories();
            Source source = source(architecture, false);
            Installed installed = installed(architecture);
            if (installed == null) {
                return new BundleInfo(
                        source != null,
                        false,
                        architecture,
                        "",
                        "",
                        "",
                        "",
                        "",
                        "",
                        source == null ? "Runtime bundle source is not configured." : hostOnly(source.url),
                        source == null
                                ? "Configure a verified MJT runtime bundle before preparing the engine."
                                : "Runtime bundle is configured but not installed."
                );
            }
            return new BundleInfo(
                    source != null,
                    installed.ready,
                    architecture,
                    installed.version,
                    installed.root.toString(),
                    installed.python.toString(),
                    installed.proot.toString(),
                    installed.engine.toString(),
                    installed.engineVersion,
                    source == null ? "" : hostOnly(source.url),
                    installed.ready ? "Runtime bundle is ready." : "Runtime bundle files are incomplete or invalid."
            );
        } catch (Exception error) {
            return new BundleInfo(false, false, architecture, "", "", "", "", "", "", "", messageOf(error));
        }
    }

    synchronized void configure(String architecture, String url, String sha256) throws IOException {
        String normalizedUrl = url == null ? "" : url.trim();
        String normalizedSha256 = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.ROOT);
        if (!normalizedUrl.startsWith("https://")) {
            throw new IOException("Runtime bundle URL must use HTTPS.");
        }
        if (!normalizedSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Runtime bundle SHA-256 must be exactly 64 lowercase hexadecimal characters.");
        }
        stateStore.set(KEY_PREFIX + architecture + ".url", normalizedUrl);
        stateStore.set(KEY_PREFIX + architecture + ".sha256", normalizedSha256);
    }

    synchronized BundleInfo requireInstalled(String architecture) throws IOException {
        Installed installed = installed(architecture);
        if (installed == null || !installed.ready) {
            throw new IOException("MJT runtime bundle is not installed for " + architecture + ".");
        }
        return new BundleInfo(true, true, architecture, installed.version, installed.root.toString(),
                installed.python.toString(), installed.proot.toString(), installed.engine.toString(),
                installed.engineVersion, "", "Runtime bundle is ready.");
    }

    synchronized BundleInfo install(String architecture, Consumer<String> logger) throws IOException {
        initializeDirectories();
        Source source = source(architecture, true);
        Installed existing = installed(architecture);
        if (existing != null && existing.ready && source.sha256.equals(existing.archiveSha256)) {
            log(logger, "Verified runtime bundle already installed: " + existing.version + " (" + architecture + ")");
            return requireInstalled(architecture);
        }

        Path staging = runtimeRoot().resolve(".staging-" + UUID.randomUUID());
        Path archive = staging.resolve("runtime.zip");
        try {
            Files.createDirectories(staging);
            log(logger, "Downloading verified MJT runtime bundle for " + architecture + "...");
            download(source, archive, logger);
            log(logger, "Extracting runtime bundle...");
            Path unpacked = staging.resolve("unpacked");
            extractZip(archive, unpacked);
            Installed candidate = inspect(unpacked, architecture, source.sha256);
            if (!candidate.ready) {
                throw new IOException("Runtime bundle manifest validation failed.");
            }

            String safeVersion = candidate.version.replaceAll("[^A-Za-z0-9._-]", "_");
            Path target = runtimeRoot().resolve("bundles").resolve(safeVersion + "-" + architecture + "-" + source.sha256.substring(0, 12));
            if (!Files.exists(target)) {
                try {
                    Files.move(unpacked, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(unpacked, target);
                }
            }
            Installed installed = inspect(target, architecture, source.sha256);
            if (!installed.ready) {
                throw new IOException("Installed runtime bundle failed validation after activation.");
            }
            stateStore.set(activeKey(architecture), target.toString());
            stateStore.set(KEY_PREFIX + architecture + ".installedAt", Instant.now().toString());
            stateStore.set(KEY_PREFIX + architecture + ".installedSha256", source.sha256);
            log(logger, "Runtime bundle ready: " + target);
            return requireInstalled(architecture);
        } finally {
            deleteQuietly(staging);
        }
    }

    private Source source(String architecture, boolean required) throws IOException {
        Properties properties = readSourceProperties();
        String upper = architecture.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
        String url = firstNonBlank(
                System.getenv("MJT_RUNTIME_BUNDLE_" + upper + "_URL"),
                stateStore.get(KEY_PREFIX + architecture + ".url", ""),
                properties.getProperty("bundle." + architecture + ".url", "")
        );
        String sha256 = firstNonBlank(
                System.getenv("MJT_RUNTIME_BUNDLE_" + upper + "_SHA256"),
                stateStore.get(KEY_PREFIX + architecture + ".sha256", ""),
                properties.getProperty("bundle." + architecture + ".sha256", "")
        ).toLowerCase(Locale.ROOT);
        if (url.isBlank() && sha256.isBlank() && !required) return null;
        if (!url.startsWith("https://")) {
            throw new IOException("Runtime bundle URL must use HTTPS.");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Runtime bundle SHA-256 must be exactly 64 lowercase hexadecimal characters.");
        }
        return new Source(url, sha256);
    }

    private Properties readSourceProperties() throws IOException {
        Properties properties = new Properties();
        Path path = runtimeRoot().resolve("source.properties");
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            }
        }
        return properties;
    }

    private Installed installed(String architecture) throws IOException {
        String configured = stateStore.get(activeKey(architecture), "").trim();
        if (configured.isBlank()) return null;
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!root.startsWith(runtimeRoot().resolve("bundles").toAbsolutePath().normalize())) {
            return null;
        }
        if (!Files.isDirectory(root)) return null;
        String sha256 = stateStore.get(KEY_PREFIX + architecture + ".installedSha256", "").trim().toLowerCase(Locale.ROOT);
        return inspect(root, architecture, sha256);
    }

    private Installed inspect(Path root, String architecture, String archiveSha256) throws IOException {
        Path manifest = root.resolve("bundle.properties");
        if (!Files.isRegularFile(manifest)) return Installed.invalid(root);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(manifest)) {
            properties.load(input);
        }
        if (!String.valueOf(BUNDLE_FORMAT).equals(properties.getProperty("bundle.format", "").trim())) {
            return Installed.invalid(root);
        }
        if (!architecture.equals(properties.getProperty("bundle.architecture", "").trim())) {
            return Installed.invalid(root);
        }
        String version = properties.getProperty("bundle.version", "").trim();
        String engineVersion = properties.getProperty("engine.version", "").trim();
        Path proot = safeManifestPath(root, properties.getProperty("proot.path", "bin/proot"));
        Path python = safeManifestPath(root, properties.getProperty("python.path", "python/bin/python3"));
        Path engine = safeManifestPath(root, properties.getProperty("engine.path", "bin/proot-distro"));
        if (version.isBlank() || engineVersion.isBlank()) return Installed.invalid(root);
        if (!verifyRequiredFile(root, properties, "proot", proot)
                || !verifyRequiredFile(root, properties, "python", python)
                || !verifyRequiredFile(root, properties, "engine", engine)) {
            return Installed.invalid(root);
        }
        markExecutable(proot);
        markExecutable(python);
        markExecutable(engine);
        boolean ready = Files.isExecutable(proot) && Files.isExecutable(python) && Files.isExecutable(engine);
        return new Installed(root, version, engineVersion, python, proot, engine, archiveSha256, ready);
    }

    private boolean verifyRequiredFile(Path root, Properties properties, String id, Path path) throws IOException {
        if (path == null || !Files.isRegularFile(path)) return false;
        String expected = properties.getProperty("file." + id + ".sha256", "").trim().toLowerCase(Locale.ROOT);
        return expected.matches("[0-9a-f]{64}") && expected.equals(sha256(path));
    }

    private Path safeManifestPath(Path root, String relative) throws IOException {
        if (relative == null || relative.isBlank()) throw new IOException("Runtime bundle manifest has an empty path.");
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IOException("Runtime bundle manifest contains an unsafe path.");
        return path;
    }

    private void download(Source source, Path destination, Consumer<String> logger) throws IOException {
        URL url = URI.create(source.url).toURL();
        HttpURLConnection connection = null;
        for (int redirects = 0; redirects <= 3; redirects++) {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/zip,application/octet-stream");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isBlank()) throw new IOException("Runtime bundle redirect had no Location header.");
                url = URI.create(url.toString()).resolve(location).toURL();
                if (!"https".equalsIgnoreCase(url.getProtocol())) throw new IOException("Runtime bundle redirect must remain HTTPS.");
                continue;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Runtime bundle download failed with HTTP " + status + ".");
            }
            long length = connection.getContentLengthLong();
            if (length > MAX_BUNDLE_BYTES) throw new IOException("Runtime bundle exceeds the maximum allowed size.");
            MessageDigest digest = sha256Digest();
            long total = 0L;
            long nextLog = 8L * 1024L * 1024L;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_BUNDLE_BYTES) throw new IOException("Runtime bundle exceeds the maximum allowed size.");
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                    if (total >= nextLog) {
                        log(logger, "Downloaded " + (total / 1024 / 1024) + " MiB...");
                        nextLog += 8L * 1024L * 1024L;
                    }
                }
            } finally {
                connection.disconnect();
            }
            String actual = hex(digest.digest());
            if (!source.sha256.equals(actual)) {
                throw new IOException("Runtime bundle SHA-256 mismatch. Download was discarded.");
            }
            return;
        }
        throw new IOException("Runtime bundle exceeded the redirect limit.");
    }

    private void extractZip(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\") || name.contains("\\")) {
                    throw new IOException("Runtime bundle contains an unsafe ZIP entry.");
                }
                Path target = destination.resolve(name).normalize();
                if (!target.startsWith(destination)) throw new IOException("Runtime bundle ZIP traversal was blocked.");
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        byte[] buffer = new byte[64 * 1024];
                        int read;
                        while ((read = zip.read(buffer)) >= 0) output.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private Path runtimeRoot() {
        return stateStore.getConfigDir().resolve("system/proot-distro/runtime").toAbsolutePath().normalize();
    }

    private String activeKey(String architecture) {
        return KEY_PREFIX + architecture + ".activePath";
    }

    private static void markExecutable(Path path) {
        try {
            path.toFile().setExecutable(true, false);
        } catch (SecurityException ignored) {
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable in this JVM.", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) builder.append(String.format("%02x", value));
        return builder.toString();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isBlank()) return value.trim();
        }
        return "";
    }

    private static String hostOnly(String rawUrl) {
        try {
            return URI.create(rawUrl).getHost() == null ? "" : URI.create(rawUrl).getHost();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void log(Consumer<String> logger, String line) {
        if (logger != null) logger.accept(line);
    }

    private static String messageOf(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void deleteQuietly(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(item -> {
                try { Files.deleteIfExists(item); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }

    private record Source(String url, String sha256) {
    }

    private record Installed(
            Path root,
            String version,
            String engineVersion,
            Path python,
            Path proot,
            Path engine,
            String archiveSha256,
            boolean ready
    ) {
        static Installed invalid(Path root) {
            return new Installed(root, "", "", root.resolve("python/bin/python3"), root.resolve("bin/proot"),
                    root.resolve("bin/proot-distro"), "", false);
        }
    }
}
