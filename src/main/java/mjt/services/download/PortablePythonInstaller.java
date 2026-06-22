package main.java.mjt.system.download;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

/**
 * Installs only MJT's portable CPython runtime.
 *
 * <p>This class deliberately owns its own Linux/kernel/architecture/libc and
 * {@code /etc/os-release} inspection so Python asset selection does not depend
 * on the proot-distro installer.</p>
 */
public final class PortablePythonInstaller {
    private static final String PYTHON_RELEASE_API =
            "https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest";

    private static final String KEY_PYTHON = "proot.distro.python";
    private static final String KEY_PYTHON_VERSION = "system.download.python.version";
    private static final String KEY_PYTHON_HOST = "system.download.python.host";
    private static final String KEY_PYTHON_OS = "system.download.python.os-release";

    private static final long MAX_DOWNLOAD_BYTES = 300L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_TAR_ENTRIES = 100_000;
    private static final int MAX_OUTPUT_CHARS = 32_000;

    private final StateStore stateStore;
    private final LogService logService;
    private final HttpClient httpClient;

    public PortablePythonInstaller(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(25))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public synchronized Path install(Consumer<String> sink) throws IOException {
        Consumer<String> log = sink == null ? ignored -> { } : sink;
        HostProfile host = detectHost();
        logHost(log, host);

        Path current = currentExecutable();
        if (isPythonReady(current)) {
            log.accept("Using existing MJT-managed Python: " + current);
            return current;
        }

        Asset asset = resolvePythonAsset(host, log);
        Path downloadDir = downloadsDir();
        Files.createDirectories(downloadDir);
        Path archive = downloadDir.resolve(asset.name() + ".download");

        downloadAndVerify(asset, archive, log);

        Path staging = pythonRoot().getParent().resolve(".python-staging-" + UUID.randomUUID());
        deleteRecursively(staging);
        Files.createDirectories(staging);

        try {
            log.accept("Extracting portable Python safely...");
            extractTarGz(archive, staging, log);

            Path extractedRoot = locatePythonRoot(staging);
            Path executable = findPythonExecutable(extractedRoot);
            ensureExecutable(executable);
            if (!isPythonReady(executable)) {
                throw new IOException("Portable Python extracted but no usable Python executable could start: " + executable);
            }

            replaceDirectory(extractedRoot, pythonRoot());
        } finally {
            deleteRecursively(staging);
            Files.deleteIfExists(archive);
        }

        Path python = currentExecutable();
        ensureExecutable(python);
        if (!isPythonReady(python)) {
            throw new IOException("MJT portable Python validation failed after activation: " + python);
        }

        stateStore.set(KEY_PYTHON, python.toString());
        stateStore.set(KEY_PYTHON_VERSION,
                firstLine(readCommand(python, List.of("--version"), managedEnvironment(), 10)));
        stateStore.set(KEY_PYTHON_HOST, host.describe());
        stateStore.set(KEY_PYTHON_OS, host.osReleaseSummary());

        log.accept("Portable Python ready: " + python);
        writeLog("[MJT SYSTEM PYTHON] installed=" + python + " host=" + host.describe() + "\n");
        return python;
    }

    public synchronized boolean check(boolean quiet) {
        try {
            HostProfile host = detectHost();
            Path python = currentExecutable();
            boolean ready = isPythonReady(python);
            if (!quiet) {
                System.out.println(ready
                        ? "\u001B[32m[MJT SYSTEM] Portable Python is ready: " + python + "\u001B[0m"
                        : "\u001B[33m[MJT SYSTEM] Portable Python is not ready. Run: .mjt system install python\u001B[0m");
                System.out.println("Host: " + host.describe());
            }
            return ready;
        } catch (Exception e) {
            if (!quiet) {
                System.out.println("\u001B[31m[MJT SYSTEM] Python check failed: " + messageOf(e) + "\u001B[0m");
            }
            return false;
        }
    }

    public synchronized void printStatus() {
        try {
            HostProfile host = detectHost();
            Path python = currentExecutable();
            System.out.println("\u001B[36m[MJT SYSTEM - PYTHON]\u001B[0m");
            System.out.println("Kernel             : " + host.kernelName() + " " + host.kernelRelease());
            System.out.println("Architecture       : " + host.architecture() + " (" + host.bits() + "-bit)");
            System.out.println("libc               : " + host.libc());
            System.out.println("os-release         : " + host.osReleaseSummary());
            System.out.println("Python triple      : " + host.pythonTriple());
            System.out.println("Python             : " + python + " | ready=" + isPythonReady(python));
        } catch (Exception e) {
            System.out.println("\u001B[31m[MJT SYSTEM] " + messageOf(e) + "\u001B[0m");
        }
    }

    /** Returns the currently installed executable, including a versioned python3.10 binary. */
    public Path currentExecutable() {
        return findPythonExecutable(pythonRoot());
    }

    private Asset resolvePythonAsset(HostProfile host, Consumer<String> log) throws IOException {
        String overrideUrl = stateStore.get("system.download.python.url.override", "").trim();
        String overrideHash = stateStore.get("system.download.python.sha256.override", "").trim();
        if (!overrideUrl.isBlank() || !overrideHash.isBlank()) {
            if (overrideUrl.isBlank() || !isSha256(overrideHash)) {
                throw new IOException("Portable Python override requires both URL and SHA-256.");
            }
            return new Asset("python-override.tar.gz", overrideUrl, overrideHash.toLowerCase(Locale.ROOT));
        }

        String expectedSuffix = "-" + host.pythonTriple() + "-install_only.tar.gz";
        for (Asset asset : parseAssets(fetchText(PYTHON_RELEASE_API))) {
            String name = asset.name().toLowerCase(Locale.ROOT);
            if (name.startsWith("cpython-")
                    && name.endsWith(expectedSuffix)
                    && !name.contains("debug")
                    && !name.contains("freethreaded")) {
                requireSha256(asset, "Portable Python release asset");
                log.accept("Selected portable Python asset: " + asset.name());
                log.accept("Selected link for " + host.describe() + ": " + asset.url());
                return asset;
            }
        }
        throw new IOException("No portable Python install_only asset matches " + host.pythonTriple()
                + " for " + host.osReleaseSummary() + ".");
    }

    private void downloadAndVerify(Asset asset, Path output, Consumer<String> log) throws IOException {
        try {
            Files.deleteIfExists(output);
            HttpRequest request = HttpRequest.newBuilder(URI.create(asset.url()))
                    .timeout(Duration.ofMinutes(6))
                    .header("User-Agent", "MJT-System-Installer")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Python download failed: HTTP " + response.statusCode());
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0L;
            try (InputStream input = response.body(); var target = Files.newOutputStream(output)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) >= 0; ) {
                    written += count;
                    if (written > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Python download exceeds safe size limit.");
                    }
                    digest.update(buffer, 0, count);
                    target.write(buffer, 0, count);
                }
            }

            if (written == 0L) throw new IOException("Portable Python download is empty.");
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(asset.sha256())) {
                throw new IOException("SHA-256 mismatch for " + asset.name() + ".");
            }
            log.accept("Downloaded and verified " + asset.name() + " (" + written + " bytes).");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading portable Python.", e);
        } catch (IOException e) {
            Files.deleteIfExists(output);
            throw e;
        } catch (Exception e) {
            Files.deleteIfExists(output);
            throw new IOException("Portable Python verification failed: " + messageOf(e), e);
        }
    }

    private void extractTarGz(Path archive, Path destination, Consumer<String> log) throws IOException {
        long expanded = 0L;
        int entries = 0;
        List<PendingLink> pendingLinks = new ArrayList<>();

        try (InputStream raw = Files.newInputStream(archive);
             GZIPInputStream gzip = new GZIPInputStream(new BufferedInputStream(raw));
             BufferedInputStream tar = new BufferedInputStream(gzip)) {

            byte[] header = new byte[512];
            while (true) {
                readFully(tar, header, 0, header.length);
                if (allZero(header)) break;
                if (++entries > MAX_TAR_ENTRIES) throw new IOException("Archive contains too many entries.");

                String name = tarString(header, 0, 100);
                String prefix = tarString(header, 345, 155);
                if (!prefix.isBlank()) name = prefix + "/" + name;

                Path target = safeTarget(destination, name);
                long size = tarOctal(header, 124, 12);
                char type = (char) header[156];
                int mode = (int) tarOctal(header, 100, 8);
                String linkName = tarString(header, 157, 100);

                switch (type) {
                    case 0, '0', '7' -> {
                        expanded += size;
                        if (expanded > MAX_EXPANDED_BYTES) {
                            throw new IOException("Archive expands beyond safe size limit.");
                        }
                        ensureNoSymlinkParents(destination, target.getParent());
                        Files.createDirectories(target.getParent());
                        ensureNoSymlinkParents(destination, target.getParent());

                        /*
                         * A tar archive may legitimately contain a symlink or
                         * hard-link entry followed later by a regular file with
                         * the same name. Replace only the leaf entry; parent
                         * symlinks remain forbidden by ensureNoSymlinkParents().
                         *
                         * Deleting the leaf avoids writing through a symlink and
                         * breaks any previous hard-link before creating this
                         * independent regular file.
                         */
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                                throw new IOException("Archive file entry would replace a directory: " + name);
                            }
                            Files.delete(target);
                        }

                        try (var out = Files.newOutputStream(target)) {
                            copyExactly(tar, out, size);
                        }
                        setMode(target, mode);
                    }
                    case '5' -> {
                        ensureNoSymlinkParents(destination, target.getParent());
                        if (Files.isSymbolicLink(target)) {
                            throw new IOException("Archive directory entry would replace a symbolic link: " + name);
                        }
                        Files.createDirectories(target);
                        setMode(target, mode);
                    }
                    case '2' -> {
                        ensureNoSymlinkParents(destination, target.getParent());
                        Files.createDirectories(target.getParent());
                        ensureNoSymlinkParents(destination, target.getParent());
                        Path source = safeRelativeLink(destination, target, linkName);
                        Files.deleteIfExists(target);
                        try {
                            Files.createSymbolicLink(target, target.getParent().relativize(source));
                        } catch (UnsupportedOperationException | SecurityException | IOException linkFailure) {
                            pendingLinks.add(new PendingLink(target, source, mode, name, "symbolic", linkFailure));
                        }
                    }
                    case '1' -> {
                        Path source = safeTarget(destination, linkName);
                        ensureNoSymlinkParents(destination, target.getParent());
                        ensureNoSymlinkParents(destination, source.getParent());
                        Files.createDirectories(target.getParent());
                        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("Archive hard-link source is not an extracted regular file: " + linkName);
                        }
                        Files.deleteIfExists(target);
                        try {
                            Files.createLink(target, source);
                        } catch (UnsupportedOperationException | SecurityException | IOException linkFailure) {
                            pendingLinks.add(new PendingLink(target, source, mode, name, "hard", linkFailure));
                        }
                    }
                    case 'x', 'g' -> skipExactly(tar, size);
                    default -> skipExactly(tar, size);
                }

                long padding = (512 - (size % 512)) % 512;
                skipExactly(tar, padding);
            }
        }

        materializePendingLinks(destination, pendingLinks, log);
        log.accept("Portable Python archive extracted safely with bounded relative symlinks.");
    }

    private void materializePendingLinks(Path destination, List<PendingLink> pending, Consumer<String> log)
            throws IOException {
        List<PendingLink> remaining = new ArrayList<>(pending);
        int copied = 0;

        while (!remaining.isEmpty()) {
            boolean progress = false;
            for (int index = remaining.size() - 1; index >= 0; index--) {
                PendingLink link = remaining.get(index);
                if (!link.source().normalize().startsWith(destination.toAbsolutePath().normalize())
                        || !Files.isRegularFile(link.source())) {
                    continue;
                }
                Files.createDirectories(link.target().getParent());
                Files.deleteIfExists(link.target());
                Files.copy(link.source(), link.target(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                setMode(link.target(), link.mode());
                remaining.remove(index);
                copied++;
                progress = true;
                log.accept("Host rejected " + link.kind() + " link " + link.name()
                        + "; copied its verified in-tree file target instead.");
            }
            if (!progress) {
                PendingLink first = remaining.get(0);
                throw new IOException("Host rejected " + first.kind() + " link " + first.name()
                        + " and its safe file fallback is unavailable: " + first.source(), first.failure());
            }
        }

        if (copied > 0) {
            log.accept("Portable Python link fallback materialized " + copied + " file(s).");
        }
    }

    /**
     * Finds the extracted Python root without relying on executable permission
     * bits. Some bind-mounted container volumes report extracted files as
     * non-executable until permissions are explicitly restored after untarring.
     */
    private Path locatePythonRoot(Path staging) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.walk(staging, 8)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isPythonExecutableName)
                    .forEach(candidates::add);
        }

        for (Path candidate : candidates) {
            Path bin = candidate.getParent();
            Path root = bin == null ? null : bin.getParent();
            if (root != null && Files.isDirectory(root.resolve("bin"))) {
                return root;
            }
        }

        String found = candidates.stream()
                .map(path -> staging.relativize(path).toString())
                .limit(12)
                .reduce((left, right) -> left + ", " + right)
                .orElse("none");
        throw new IOException("Unable to locate a portable Python installation in the downloaded archive. "
                + "Python-like files found: " + found);
    }

    private Path findPythonExecutable(Path root) {
        if (root == null) return null;
        Path bin = root.resolve("bin");
        if (!Files.isDirectory(bin)) return null;

        for (String name : List.of("python3", "python")) {
            Path candidate = bin.resolve(name);
            if (Files.isRegularFile(candidate)) return candidate;
        }

        try (var stream = Files.list(bin)) {
            return stream
                    .filter(this::isPythonExecutableName)
                    .filter(Files::isRegularFile)
                    .sorted()
                    .findFirst()
                    .orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private boolean isPythonExecutableName(Path path) {
        Path fileName = path == null ? null : path.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString();
        return name.equals("python")
                || name.equals("python3")
                || name.matches("python3\\.[0-9]+(?:\\.[0-9]+)?");
    }

    /** Restores execute permission after archive extraction where supported. */
    private void ensureExecutable(Path executable) throws IOException {
        if (executable == null || !Files.isRegularFile(executable)) {
            throw new IOException("Portable Python executable is missing: " + executable);
        }
        if (Files.isExecutable(executable)) return;

        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(executable);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(executable, permissions);
        } catch (UnsupportedOperationException ignored) {
            if (!executable.toFile().setExecutable(true, false)) {
                throw new IOException("Could not mark portable Python executable: " + executable);
            }
        }

        if (!Files.isExecutable(executable)) {
            throw new IOException("Portable Python exists but the host filesystem refuses execute permission: " + executable);
        }
    }

    private boolean isPythonReady(Path python) {
        if (python == null || !Files.isRegularFile(python) || !Files.isExecutable(python)) return false;
        try {
            String version = readCommand(python,
                    List.of("-c", "import ssl, sys, venv; print(f'{sys.version_info.major}.{sys.version_info.minor}')"),
                    managedEnvironment(), 12).trim();
            return version.matches("3\\.(?:9|[1-9][0-9]+)");
        } catch (Exception ignored) {
            return false;
        }
    }

    private HostProfile detectHost() throws IOException {
        String kernelName = firstNonBlank(runSmallCommand(4, "uname", "-s"), System.getProperty("os.name", ""));
        if (!kernelName.toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IOException("Portable Python installer supports Linux hosts only; detected: " + kernelName);
        }

        String kernelRelease = firstNonBlank(runSmallCommand(4, "uname", "-r"), "unknown");
        String rawArch = firstNonBlank(runSmallCommand(4, "uname", "-m"), System.getProperty("os.arch", ""));
        String bits = firstNonBlank(runSmallCommand(4, "getconf", "LONG_BIT"), "64");
        if (!"64".equals(bits.trim())) {
            throw new IOException("Unsupported " + bits + "-bit Linux host. MJT portable Python requires 64-bit Linux.");
        }

        String architecture = normalizeArchitecture(rawArch);
        Map<String, String> osRelease = readOsRelease();
        boolean musl = isMuslHost(osRelease);
        String libc = musl ? "musl" : "gnu";
        String triple = architecture + "-unknown-linux-" + libc;

        return new HostProfile(kernelName.trim(), kernelRelease.trim(), rawArch.trim(), architecture,
                bits.trim(), libc, triple, osRelease);
    }

    private String normalizeArchitecture(String raw) throws IOException {
        String arch = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "x86_64", "amd64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new IOException("Unsupported Linux architecture: " + raw
                    + ". Supported: x86_64/amd64 and aarch64/arm64.");
        };
    }

    private Map<String, String> readOsRelease() {
        String content = runSmallCommand(4, "cat", "/etc/os-release");
        if (content.isBlank()) {
            try {
                content = Files.readString(Path.of("/etc/os-release"), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                return Map.of();
            }
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (String line : content.split("\\R")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) continue;
            String key = line.substring(0, equals).trim();
            String value = line.substring(equals + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\""))
                    || (value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }

    private boolean isMuslHost(Map<String, String> osRelease) {
        String ldd = runSmallCommand(4, "ldd", "--version").toLowerCase(Locale.ROOT);
        if (ldd.contains("musl")) return true;
        String id = osRelease.getOrDefault("ID", "").toLowerCase(Locale.ROOT);
        String like = osRelease.getOrDefault("ID_LIKE", "").toLowerCase(Locale.ROOT);
        return id.equals("alpine") || like.contains("alpine");
    }

    private void logHost(Consumer<String> log, HostProfile host) {
        log.accept("Linux host: kernel=" + host.kernelName() + " " + host.kernelRelease()
                + ", arch=" + host.architecture() + ", bits=" + host.bits()
                + ", libc=" + host.libc() + ", os=" + host.osReleaseSummary());
        log.accept("Selected Python target triple: " + host.pythonTriple());
    }

    private Map<String, String> managedEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        String oldPath = System.getenv("PATH");
        String managedPath = managedBinDir() + ":" + pythonRoot().resolve("bin");
        env.put("PATH", managedPath + (oldPath == null || oldPath.isBlank() ? "" : ":" + oldPath));
        return env;
    }

    private String fetchText(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(35))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MJT-System-Installer")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Upstream Python metadata request failed: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading Python metadata.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid upstream Python metadata URL.", e);
        }
    }

    private List<Asset> parseAssets(String json) {
        List<Asset> result = new ArrayList<>();
        int assetsAt = json.indexOf("\"assets\"");
        if (assetsAt < 0) return result;
        int arrayStart = json.indexOf('[', assetsAt);
        if (arrayStart < 0) return result;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        int objectStart = -1;
        for (int index = arrayStart + 1; index < json.length(); index++) {
            char ch = json.charAt(index);
            if (inString) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                if (depth++ == 0) objectStart = index;
            } else if (ch == '}') {
                if (--depth == 0 && objectStart >= 0) {
                    String object = json.substring(objectStart, index + 1);
                    String name = jsonString(object, "name");
                    String url = jsonString(object, "browser_download_url");
                    String digest = jsonString(object, "digest");
                    if (!name.isBlank() && !url.isBlank()) {
                        if (digest.startsWith("sha256:")) digest = digest.substring("sha256:".length());
                        result.add(new Asset(name, url, digest.toLowerCase(Locale.ROOT)));
                    }
                    objectStart = -1;
                }
            } else if (ch == ']' && depth == 0) {
                break;
            }
        }
        return result;
    }

    private String jsonString(String object, String field) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(field)
                + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
        Matcher matcher = pattern.matcher(object);
        return matcher.find() ? unescapeJson(matcher.group(1)) : "";
    }

    private String unescapeJson(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char ch = text.charAt(index);
            if (ch != '\\' || index + 1 >= text.length()) {
                out.append(ch);
                continue;
            }
            char next = text.charAt(++index);
            switch (next) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                default -> out.append(next);
            }
        }
        return out.toString();
    }

    private Path safeTarget(Path root, String rawName) throws IOException {
        if (rawName == null || rawName.isBlank()) throw new IOException("Archive entry has no name.");
        String normalized = rawName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf('\0') >= 0) {
            throw new IOException("Unsafe archive path: " + rawName);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(normalized).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("Archive path escapes extraction root: " + rawName);
        }
        return resolved;
    }

    private Path safeRelativeLink(Path root, Path linkPath, String rawTarget) throws IOException {
        if (rawTarget == null || rawTarget.isBlank()) throw new IOException("Unsafe empty symbolic link.");
        String normalized = rawTarget.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.indexOf('\0') >= 0) {
            throw new IOException("Unsafe symbolic link target: " + rawTarget);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path parent = linkPath.toAbsolutePath().normalize().getParent();
        if (parent == null || !parent.startsWith(normalizedRoot)) {
            throw new IOException("Unsafe symbolic link location: " + linkPath);
        }
        Path resolved = parent.resolve(normalized).normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            throw new IOException("Unsafe symbolic link target: " + rawTarget);
        }
        return resolved;
    }

    private void ensureNoSymlinkParents(Path root, Path directory) throws IOException {
        if (directory == null) return;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        if (!normalizedDirectory.startsWith(normalizedRoot)) {
            throw new IOException("Archive path escapes extraction root: " + directory);
        }
        Path current = normalizedRoot;
        for (Path part : normalizedRoot.relativize(normalizedDirectory)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Archive entry traverses an existing symbolic link: "
                        + normalizedRoot.relativize(current));
            }
        }
    }

    private void replaceDirectory(Path source, Path target) throws IOException {
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path previous = parent.resolve(target.getFileName() + ".previous-" + UUID.randomUUID());
        if (Files.exists(target)) moveAtomically(target, previous);
        try {
            moveAtomically(source, target);
        } catch (IOException e) {
            if (Files.exists(previous) && !Files.exists(target)) moveAtomically(previous, target);
            throw e;
        }
        deleteRecursively(previous);
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readCommand(Path executable, List<String> args, Map<String, String> environment,
                               long timeoutSeconds) throws IOException {
        if (executable == null || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            throw new IOException("Not executable: " + executable);
        }
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        try {
            Process process = builder.start();
            String text = readProcessOutput(process.getInputStream(), MAX_OUTPUT_CHARS);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Command timed out: " + executable.getFileName());
            }
            if (process.exitValue() != 0) {
                throw new IOException("Command exited with " + process.exitValue() + ": " + firstLine(text));
            }
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted.", e);
        }
    }

    private String runSmallCommand(long timeoutSeconds, String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String text = readProcessOutput(process.getInputStream(), 8_000);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return process.exitValue() == 0 ? text.trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String readProcessOutput(InputStream input, int maxChars) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null && out.length() < maxChars) {
                out.append(line).append('\n');
            }
            return out.toString();
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path systemRoot() {
        return stateStore.getConfigDir().resolve("system").toAbsolutePath().normalize();
    }

    private Path downloadsDir() {
        return systemRoot().resolve("downloads/python");
    }

    private Path managedBinDir() {
        return systemRoot().resolve("bin");
    }

    private Path pythonRoot() {
        return systemRoot().resolve("python/current");
    }

    private static void readFully(InputStream input, byte[] buffer, int offset, int length) throws IOException {
        int done = 0;
        while (done < length) {
            int count = input.read(buffer, offset + done, length - done);
            if (count < 0) throw new IOException("Unexpected end of archive.");
            done += count;
        }
    }

    private static void copyExactly(InputStream input, java.io.OutputStream output, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("Unexpected end of archive entry.");
            output.write(buffer, 0, count);
            remaining -= count;
        }
    }

    private static void skipExactly(InputStream input, long length) throws IOException {
        long remaining = length;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) throw new IOException("Unexpected end while skipping archive data.");
            remaining -= count;
        }
    }

    private static boolean allZero(byte[] data) {
        for (byte value : data) if (value != 0) return false;
        return true;
    }

    private static String tarString(byte[] data, int offset, int length) {
        int end = offset;
        while (end < offset + length && data[end] != 0) end++;
        return new String(data, offset, end - offset, StandardCharsets.UTF_8).trim();
    }

    private static long tarOctal(byte[] data, int offset, int length) {
        String text = tarString(data, offset, length).trim();
        if (text.isBlank()) return 0L;
        try {
            return Long.parseLong(text.replaceAll("[^0-7]", ""), 8);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static void setMode(Path path, int mode) {
        if (mode <= 0) return;
        try {
            Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
            if ((mode & 0400) != 0) permissions.add(PosixFilePermission.OWNER_READ);
            if ((mode & 0200) != 0) permissions.add(PosixFilePermission.OWNER_WRITE);
            if ((mode & 0100) != 0) permissions.add(PosixFilePermission.OWNER_EXECUTE);
            if ((mode & 0040) != 0) permissions.add(PosixFilePermission.GROUP_READ);
            if ((mode & 0020) != 0) permissions.add(PosixFilePermission.GROUP_WRITE);
            if ((mode & 0010) != 0) permissions.add(PosixFilePermission.GROUP_EXECUTE);
            if ((mode & 0004) != 0) permissions.add(PosixFilePermission.OTHERS_READ);
            if ((mode & 0002) != 0) permissions.add(PosixFilePermission.OTHERS_WRITE);
            if ((mode & 0001) != 0) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
        } catch (Exception ignored) {
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[a-f0-9]{64}");
    }

    private static void requireSha256(Asset asset, String label) throws IOException {
        if (!isSha256(asset.sha256())) {
            throw new IOException(label + " did not expose a SHA-256 digest. Refusing unverified install.");
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) return "";
        int index = text.indexOf('\n');
        return (index < 0 ? text : text.substring(0, index)).trim();
    }

    private static String firstNonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first.trim() : (fallback == null ? "" : fallback.trim());
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value));
        return text.toString();
    }

    private static String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private void writeLog(String line) {
        try {
            logService.write(line);
        } catch (IOException ignored) {
        }
    }

    private record Asset(String name, String url, String sha256) {
    }

    private record PendingLink(Path target, Path source, int mode, String name, String kind, Exception failure) {
    }

    private record HostProfile(
            String kernelName,
            String kernelRelease,
            String rawArchitecture,
            String architecture,
            String bits,
            String libc,
            String pythonTriple,
            Map<String, String> osRelease
    ) {
        String osReleaseSummary() {
            String id = osRelease.getOrDefault("ID", "unknown");
            String version = osRelease.getOrDefault("VERSION_ID", "unknown");
            return id + " " + version;
        }

        String describe() {
            return kernelName + " " + kernelRelease + " | " + architecture + " | " + libc
                    + " | " + osReleaseSummary();
        }
    }
}
