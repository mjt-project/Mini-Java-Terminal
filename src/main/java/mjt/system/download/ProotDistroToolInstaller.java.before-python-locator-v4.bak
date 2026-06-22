package main.java.mjt.system.download;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
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
 * Installs only MJT-managed prerequisites needed by the upstream proot-distro
 * command. It does not implement OCI pulls, rootfs extraction, or distro
 * lifecycle logic; those remain the responsibility of upstream proot-distro.
 *
 * <p>All files remain beneath {@code MJT/system}. Python and PyPI downloads
 * require an upstream SHA-256 digest. The legacy PRoot v5.3.0 GitHub release
 * does not publish an asset digest in its metadata, so MJT accepts only its
 * exact pinned official release URL, records the downloaded SHA-256 locally,
 * and still performs an executable/ptrace smoke test. This is intentionally
 * narrower than a generic unverified-download fallback.</p>
 */
public final class ProotDistroToolInstaller {
    public static final String PROOT_VERSION = "5.3.0";
    public static final String DEFAULT_PROOT_DISTRO_VERSION = "5.3.0";

    private static final String KEY_PROOT = "proot.binary";
    private static final String KEY_PYTHON = "proot.distro.python";
    private static final String KEY_ENGINE = "proot.distro.engineExecutable";
    private static final String KEY_ENGINE_VERSION = "proot.distro.engineVersion";
    private static final String KEY_PROOT_VERSION = "system.download.proot.version";
    private static final String KEY_PYTHON_VERSION = "system.download.python.version";
    private static final String KEY_STATUS = "system.download.proot-distro.status";

    private static final String PROOT_RELEASE_API =
            "https://api.github.com/repos/proot-me/proot/releases/tags/v" + PROOT_VERSION;
    private static final String PYTHON_RELEASE_API =
            "https://api.github.com/repos/astral-sh/python-build-standalone/releases/latest";
    private static final String PYPI_PROOT_DISTRO_API =
            "https://pypi.org/pypi/proot-distro/%s/json";

    private static final long MAX_PROOT_DOWNLOAD = 20L * 1024L * 1024L;
    private static final long MAX_PYTHON_DOWNLOAD = 300L * 1024L * 1024L;
    private static final long MAX_TAR_EXPANDED = 2L * 1024L * 1024L * 1024L;
    private static final int MAX_TAR_ENTRIES = 100_000;

    private final StateStore stateStore;
    private final LogService logService;
    private final HttpClient httpClient;

    public ProotDistroToolInstaller(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(25))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record EnginePaths(Path proot, Path python, Path prootDistro, String prootDistroVersion) {
    }

    public synchronized EnginePaths installAll(String requestedDistroVersion, Consumer<String> sink) throws IOException {
        requireSupportedHost();
        Consumer<String> log = sink == null ? ignored -> { } : sink;
        String version = requestedDistroVersion == null || requestedDistroVersion.isBlank()
                ? DEFAULT_PROOT_DISTRO_VERSION
                : requestedDistroVersion.trim();
        if (!version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:[A-Za-z0-9._-]+)?")) {
            throw new IOException("Invalid proot-distro version: " + version);
        }

        Path proot = installProot(log);
        Path python = installPython(log);
        Path engine = installProotDistro(version, python, proot, log);
        return new EnginePaths(proot, python, engine, readVersion(engine, managedEnvironment()));
    }

    public synchronized Path installProot(Consumer<String> sink) throws IOException {
        requireSupportedHost();
        Consumer<String> log = sink == null ? ignored -> { } : sink;
        Path target = managedBinDir().resolve("proot");
        if (isProotReady(target)) {
            log.accept("Using existing MJT-managed PRoot: " + target);
            return target;
        }

        HostTarget host = detectHost();
        String assetName = "proot-v" + PROOT_VERSION + "-" + host.prootArchitecture + "-static";
        Asset asset = resolveAsset(PROOT_RELEASE_API, assetName, "system.download.proot", log);
        if (!isExpectedProotAsset(asset, assetName)) {
            throw new IOException("Refusing unexpected PRoot download URL for " + assetName + ".");
        }
        Path downloadDir = downloadsDir().resolve("proot");
        Files.createDirectories(downloadDir);
        Path downloaded = downloadDir.resolve(assetName + ".download");
        String downloadedSha256 = downloadAndVerify(asset, downloaded, MAX_PROOT_DOWNLOAD, log, true);

        Files.createDirectories(managedBinDir());
        moveAtomically(downloaded, target);
        makeExecutable(target);
        if (!isProotReady(target)) {
            throw new IOException("Downloaded PRoot failed its version/ptrace smoke test: " + target);
        }

        stateStore.set(KEY_PROOT, target.toString());
        stateStore.set(KEY_PROOT_VERSION, firstLine(readCommand(target, List.of("--version"), managedEnvironment(), 8)));
        stateStore.set("system.download.proot.sha256", downloadedSha256);
        stateStore.set("system.download.proot.integrity", isSha256(asset.sha256)
                ? "publisher-sha256"
                : "pinned-official-url-local-sha256");
        log.accept("PRoot ready: " + target);
        writeLog("[MJT SYSTEM PROOT] installed=" + target + "\n");
        return target;
    }

    public synchronized Path installPython(Consumer<String> sink) throws IOException {
        requireSupportedHost();
        Consumer<String> log = sink == null ? ignored -> { } : sink;
        Path current = pythonRoot().resolve("bin/python3");
        if (isPythonReady(current)) {
            log.accept("Using existing MJT-managed Python: " + current);
            return current;
        }

        HostTarget host = detectHost();
        String triple = host.pythonTriple;
        Asset asset = resolvePythonAsset(triple, log);
        Path downloadDir = downloadsDir().resolve("python");
        Files.createDirectories(downloadDir);
        Path archive = downloadDir.resolve(asset.name + ".download");
        downloadAndVerify(asset, archive, MAX_PYTHON_DOWNLOAD, log);

        Path staging = pythonRoot().getParent().resolve(".python-staging-" + UUID.randomUUID());
        deleteRecursively(staging);
        Files.createDirectories(staging);
        try {
            log.accept("Extracting portable Python safely...");
            extractTarGz(archive, staging, log);
            Path extractedRoot = locatePythonRoot(staging);
            Path executable = extractedRoot.resolve("bin/python3");
            if (!isPythonReady(executable)) {
                executable = extractedRoot.resolve("bin/python");
            }
            if (!isPythonReady(executable)) {
                throw new IOException("Portable Python extracted but python3 could not start.");
            }
            replaceDirectory(extractedRoot, pythonRoot());
        } finally {
            deleteRecursively(staging);
            Files.deleteIfExists(archive);
        }

        Path python = pythonRoot().resolve("bin/python3");
        if (!isPythonReady(python)) {
            python = pythonRoot().resolve("bin/python");
        }
        if (!isPythonReady(python)) {
            throw new IOException("MJT portable Python validation failed after activation.");
        }
        stateStore.set(KEY_PYTHON, python.toString());
        stateStore.set(KEY_PYTHON_VERSION, firstLine(readCommand(python, List.of("--version"), managedEnvironment(), 8)));
        log.accept("Portable Python ready: " + python);
        writeLog("[MJT SYSTEM PYTHON] installed=" + python + "\n");
        return python;
    }

    public synchronized Path installProotDistro(String version, Path python, Path proot, Consumer<String> sink) throws IOException {
        requireSupportedHost();
        Consumer<String> log = sink == null ? ignored -> { } : sink;
        if (python == null || !isPythonReady(python)) {
            throw new IOException("MJT portable Python is unavailable.");
        }
        if (proot == null || !isProotReady(proot)) {
            throw new IOException("MJT-managed PRoot is unavailable or cannot use ptrace.");
        }

        Path engine = managedBinDir().resolve("proot-distro");
        Map<String, String> environment = managedEnvironment();
        if (canRun(engine, List.of("--version"), environment, 12)) {
            String current = readVersion(engine, environment);
            if (current.contains(version)) {
                stateStore.set(KEY_ENGINE, engine.toString());
                stateStore.set(KEY_ENGINE_VERSION, current);
                log.accept("Using existing MJT-managed proot-distro: " + engine);
                return engine;
            }
        }

        Files.createDirectories(sitePackagesDir());
        log.accept("Preparing pip inside MJT portable Python...");
        runChecked(python, List.of("-m", "ensurepip", "--upgrade"), environment, 120, log);

        Asset packageAsset = resolveProotDistroWheel(version, log);
        Path packageDir = downloadsDir().resolve("proot-distro");
        Files.createDirectories(packageDir);
        Path wheel = packageDir.resolve(packageAsset.name + ".download");
        try {
            downloadAndVerify(packageAsset, wheel, 40L * 1024L * 1024L, log);
            log.accept("Installing verified upstream proot-distro==" + version + " (no dependencies)...");
            runChecked(python, List.of(
                    "-m", "pip", "install",
                    "--disable-pip-version-check", "--no-input", "--no-deps", "--no-index", "--upgrade",
                    "--target", sitePackagesDir().toString(),
                    wheel.toString()
            ), environment, 180, log);
        } finally {
            Files.deleteIfExists(wheel);
        }

        writeEngineLauncher(engine, python);
        if (!canRun(engine, List.of("--version"), managedEnvironment(), 12)) {
            throw new IOException("proot-distro installed but its MJT launcher could not run.");
        }
        String detected = readVersion(engine, managedEnvironment());
        stateStore.set(KEY_ENGINE, engine.toString());
        stateStore.set(KEY_ENGINE_VERSION, detected);
        stateStore.set(KEY_STATUS, "installed");
        log.accept("proot-distro ready: " + engine + " | " + detected);
        writeLog("[MJT SYSTEM PROOT-DISTRO] installed=" + engine + " version=" + detected + "\n");
        return engine;
    }

    public synchronized void printStatus() {
        try {
            HostTarget host = detectHost();
            Path proot = managedBinDir().resolve("proot");
            Path python = pythonRoot().resolve("bin/python3");
            if (!Files.exists(python)) python = pythonRoot().resolve("bin/python");
            Path engine = managedBinDir().resolve("proot-distro");
            System.out.println("\u001B[36m[MJT SYSTEM - PROOT-DISTRO]\u001B[0m");
            System.out.println("Host architecture : " + host.displayArchitecture);
            System.out.println("PRoot             : " + proot + " | ready=" + isProotReady(proot));
            System.out.println("PRoot integrity   : "
                    + stateStore.get("system.download.proot.integrity", "not-installed")
                    + " | sha256=" + stateStore.get("system.download.proot.sha256", ""));
            System.out.println("Python            : " + python + " | ready=" + isPythonReady(python));
            System.out.println("proot-distro      : " + engine + " | ready=" + canRun(engine, List.of("--version"), managedEnvironment(), 12));
            System.out.println("XDG data home     : " + xdgDataHome());
            System.out.println("XDG cache home    : " + xdgCacheHome());
        } catch (Exception e) {
            System.out.println("\u001B[31m[MJT SYSTEM] " + messageOf(e) + "\u001B[0m");
        }
    }

    public synchronized boolean check(boolean quiet) {
        try {
            Path proot = managedBinDir().resolve("proot");
            Path python = pythonRoot().resolve("bin/python3");
            if (!Files.exists(python)) python = pythonRoot().resolve("bin/python");
            Path engine = managedBinDir().resolve("proot-distro");
            boolean ready = isProotReady(proot)
                    && isPythonReady(python)
                    && canRun(engine, List.of("--version"), managedEnvironment(), 12);
            if (!quiet) {
                System.out.println(ready
                        ? "\u001B[32m[MJT SYSTEM] PRoot-Distro engine is ready.\u001B[0m"
                        : "\u001B[33m[MJT SYSTEM] Engine is not ready. Run: .mjt system install proot-distro\u001B[0m");
            }
            return ready;
        } catch (Exception e) {
            if (!quiet) {
                System.out.println("\u001B[31m[MJT SYSTEM] Check failed: " + messageOf(e) + "\u001B[0m");
            }
            return false;
        }
    }


    private Asset resolveProotDistroWheel(String version, Consumer<String> log) throws IOException {
        String urlOverride = stateStore.get("system.download.proot-distro.url.override", "").trim();
        String hashOverride = stateStore.get("system.download.proot-distro.sha256.override", "").trim();
        if (!urlOverride.isBlank() || !hashOverride.isBlank()) {
            if (urlOverride.isBlank() || !isSha256(hashOverride)) {
                throw new IOException("Override for proot-distro requires both URL and SHA-256.");
            }
            return new Asset("proot-distro-" + version + ".whl", urlOverride, hashOverride.toLowerCase(Locale.ROOT));
        }
        String json = fetchText(String.format(Locale.ROOT, PYPI_PROOT_DISTRO_API, version));
        Pattern sha256Pattern = Pattern.compile("\\\"sha256\\\"\\s*:\\s*\\\"([a-fA-F0-9]{64})\\\"");
        for (String object : jsonObjectsInArray(json, "urls")) {
            String fileName = jsonString(object, "filename");
            String url = jsonString(object, "url");
            Matcher digest = sha256Pattern.matcher(object);
            if (fileName.startsWith("proot_distro-")
                    && fileName.endsWith("-py3-none-any.whl")
                    && !url.isBlank()
                    && digest.find()) {
                Asset asset = new Asset(fileName, url, digest.group(1).toLowerCase(Locale.ROOT));
                requireDigest(asset, "PyPI proot-distro wheel");
                log.accept("Selected verified PyPI wheel: " + asset.name);
                return asset;
            }
        }
        throw new IOException("Verified universal wheel not found on PyPI for proot-distro==" + version);
    }

    private Asset resolvePythonAsset(String triple, Consumer<String> log) throws IOException {
        String urlOverride = stateStore.get("system.download.python.url.override", "").trim();
        String hashOverride = stateStore.get("system.download.python.sha256.override", "").trim();
        if (!urlOverride.isBlank() || !hashOverride.isBlank()) {
            if (urlOverride.isBlank() || !isSha256(hashOverride)) {
                throw new IOException("Portable Python override requires both URL and SHA-256.");
            }
            return new Asset("python-override.tar.gz", urlOverride, hashOverride.toLowerCase(Locale.ROOT));
        }
        String json = fetchText(PYTHON_RELEASE_API);
        List<Asset> assets = parseAssets(json);
        String suffix = "-" + triple + "-install_only.tar.gz";
        for (Asset asset : assets) {
            String name = asset.name.toLowerCase(Locale.ROOT);
            if (name.startsWith("cpython-")
                    && name.endsWith(suffix)
                    && !name.contains("debug")
                    && !name.contains("freethreaded")) {
                requireDigest(asset, "Portable Python release asset");
                log.accept("Selected portable Python asset: " + asset.name);
                return asset;
            }
        }
        throw new IOException("No portable Python asset for " + triple + " was found in the upstream latest release.");
    }

    private Asset resolveAsset(String releaseApi, String assetName, String overridePrefix, Consumer<String> log) throws IOException {
        String urlOverride = stateStore.get(overridePrefix + ".url.override", "").trim();
        String hashOverride = stateStore.get(overridePrefix + ".sha256.override", "").trim();
        if (!urlOverride.isBlank() || !hashOverride.isBlank()) {
            if (urlOverride.isBlank() || !isSha256(hashOverride)) {
                throw new IOException("Override for " + overridePrefix + " requires both URL and SHA-256.");
            }
            return new Asset(assetName, urlOverride, hashOverride.toLowerCase(Locale.ROOT));
        }
        for (Asset asset : parseAssets(fetchText(releaseApi))) {
            if (asset.name.equals(assetName)) {
                if (isSha256(asset.sha256)) {
                    log.accept("Selected verified upstream asset: " + asset.name);
                } else {
                    if (!isExpectedProotAsset(asset, assetName)) {
                        throw new IOException("PRoot metadata did not provide a digest and the download URL is not the expected official release URL.");
                    }
                    log.accept("Upstream PRoot asset has no published SHA-256; using the exact pinned official URL and recording the local SHA-256.");
                }
                return asset;
            }
        }
        throw new IOException("Upstream release asset not found: " + assetName);
    }

    private boolean isExpectedProotAsset(Asset asset, String assetName) {
        if (asset == null || assetName == null || !assetName.equals(asset.name)) return false;
        String expected = "https://github.com/proot-me/proot/releases/download/v"
                + PROOT_VERSION + "/" + assetName;
        return expected.equals(asset.url);
    }

    private String fetchText(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(35))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MJT-System-Installer")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Upstream metadata request failed: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading upstream metadata.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid upstream URL.", e);
        }
    }

    private List<Asset> parseAssets(String json) {
        List<Asset> result = new ArrayList<>();
        int assetsAt = json.indexOf("\"assets\"");
        if (assetsAt < 0) return result;
        int arrayStart = json.indexOf('[', assetsAt);
        if (arrayStart < 0) return result;
        int depth = 0;
        boolean string = false;
        boolean escape = false;
        int objectStart = -1;
        for (int i = arrayStart + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (string) {
                if (escape) escape = false;
                else if (ch == '\\') escape = true;
                else if (ch == '"') string = false;
                continue;
            }
            if (ch == '"') { string = true; continue; }
            if (ch == '{') {
                if (depth++ == 0) objectStart = i;
            } else if (ch == '}') {
                if (--depth == 0 && objectStart >= 0) {
                    String object = json.substring(objectStart, i + 1);
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

    private List<String> jsonObjectsInArray(String json, String arrayField) {
        List<String> result = new ArrayList<>();
        int fieldAt = json.indexOf("\"" + arrayField + "\"");
        if (fieldAt < 0) return result;
        int arrayStart = json.indexOf('[', fieldAt);
        if (arrayStart < 0) return result;
        int depth = 0;
        boolean string = false;
        boolean escape = false;
        int objectStart = -1;
        for (int i = arrayStart + 1; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (string) {
                if (escape) escape = false;
                else if (ch == '\\') escape = true;
                else if (ch == '"') string = false;
                continue;
            }
            if (ch == '"') { string = true; continue; }
            if (ch == '{') {
                if (depth++ == 0) objectStart = i;
            } else if (ch == '}') {
                if (--depth == 0 && objectStart >= 0) {
                    result.add(json.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            } else if (ch == ']' && depth == 0) {
                break;
            }
        }
        return result;
    }

    private String jsonString(String object, String field) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(field) + "\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
        Matcher matcher = pattern.matcher(object);
        if (!matcher.find()) return "";
        return unescapeJson(matcher.group(1));
    }

    private String unescapeJson(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '\\' || i + 1 >= text.length()) {
                out.append(ch);
                continue;
            }
            char next = text.charAt(++i);
            switch (next) {
                case '"': out.append('"'); break;
                case '\\': out.append('\\'); break;
                case '/': out.append('/'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                default: out.append(next); break;
            }
        }
        return out.toString();
    }

    private String downloadAndVerify(Asset asset, Path output, long maxBytes, Consumer<String> log) throws IOException {
        return downloadAndVerify(asset, output, maxBytes, log, false);
    }

    private String downloadAndVerify(Asset asset, Path output, long maxBytes, Consumer<String> log,
                                     boolean allowMissingPublisherDigest) throws IOException {
        boolean hasPublisherDigest = isSha256(asset.sha256);
        if (!hasPublisherDigest && !allowMissingPublisherDigest) {
            throw new IOException("Upstream asset " + asset.name + " did not expose a SHA-256 digest.");
        }
        try {
            Files.deleteIfExists(output);
            HttpRequest request = HttpRequest.newBuilder(URI.create(asset.url))
                    .timeout(Duration.ofMinutes(6))
                    .header("User-Agent", "MJT-System-Installer")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("Download failed: HTTP " + response.statusCode());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0L;
            try (InputStream input = response.body(); var target = Files.newOutputStream(output)) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    written += count;
                    if (written > maxBytes) {
                        throw new IOException("Download exceeds the safe size limit.");
                    }
                    digest.update(buffer, 0, count);
                    target.write(buffer, 0, count);
                }
            }
            if (written == 0L) throw new IOException("Downloaded file is empty.");
            String actual = hex(digest.digest());
            if (hasPublisherDigest && !actual.equalsIgnoreCase(asset.sha256)) {
                throw new IOException("SHA-256 mismatch for " + asset.name + ". Expected " + asset.sha256 + " but received " + actual + ".");
            }
            if (hasPublisherDigest) {
                log.accept("Downloaded and verified " + asset.name + " (" + written + " bytes).");
            } else {
                log.accept("Downloaded pinned official asset without a publisher SHA-256 (" + written
                        + " bytes). Recorded local SHA-256: " + actual + ".");
            }
            return actual;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + asset.name + ".", e);
        } catch (IOException e) {
            Files.deleteIfExists(output);
            throw e;
        } catch (Exception e) {
            Files.deleteIfExists(output);
            throw new IOException("Download verification failed: " + messageOf(e), e);
        }
    }

    private void extractTarGz(Path archive, Path destination, Consumer<String> log) throws IOException {
        long expanded = 0L;
        int entries = 0;
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
                    case 0, '7' -> {
                        expanded += size;
                        if (expanded > MAX_TAR_EXPANDED) throw new IOException("Archive expands beyond the safe size limit.");
                        ensureNoSymlinkParents(destination, target.getParent());
                        if (Files.isSymbolicLink(target)) {
                            throw new IOException("Archive file entry would replace a symbolic link: " + name);
                        }
                        Files.createDirectories(target.getParent());
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
                        ensureNoSymlinkParents(destination, target.getParent()); Files.createDirectories(target.getParent()); ensureNoSymlinkParents(destination, target.getParent());
                        Path linkTarget = safeRelativeLink(destination, target, linkName);
                        Files.deleteIfExists(target);
                        Files.createSymbolicLink(target, linkTarget);
                    }
                    case '1' -> {
                        Path source = safeTarget(destination, linkName);
                        ensureNoSymlinkParents(destination, target.getParent());
                        ensureNoSymlinkParents(destination, source.getParent());
                        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("Archive hard link source is not a regular extracted file: " + linkName);
                        }
                        Files.deleteIfExists(target);
                        Files.createLink(target, source);
                    }
                    case 'x', 'g' -> skipExactly(tar, size);
                    default -> skipExactly(tar, size);
                }
                long padding = (512 - (size % 512)) % 512;
                skipExactly(tar, padding);
            }
        }
        log.accept("Portable Python archive extracted safely with bounded relative symlinks.");
    }

    private Path locatePythonRoot(Path staging) throws IOException {
        List<Path> candidates = new ArrayList<>();
        try (var stream = Files.walk(staging, 6)) {
            stream.filter(path -> path.getFileName() != null && path.getFileName().toString().equals("python3"))
                    .filter(Files::isRegularFile)
                    .forEach(candidates::add);
        }
        for (Path candidate : candidates) {
            Path bin = candidate.getParent();
            Path root = bin == null ? null : bin.getParent();
            if (root != null && Files.isDirectory(root.resolve("lib"))) return root;
        }
        throw new IOException("Unable to locate a portable Python installation in the downloaded archive.");
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

    private void writeEngineLauncher(Path launcher, Path python) throws IOException {
        Files.createDirectories(launcher.getParent());
        String pythonPath = shellQuote(python.toString());
        String sitePath = shellQuote(sitePackagesDir().toString());
        String script = "#!/bin/sh\n"
                + "export PYTHONPATH=" + sitePath + "${PYTHONPATH:+:$PYTHONPATH}\n"
                + "exec " + pythonPath
                + " -c 'from proot_distro.cli import main; raise SystemExit(main())' \"$@\"\n";
        Files.writeString(launcher, script, StandardCharsets.UTF_8);
        makeExecutable(launcher);
    }

    private void runChecked(Path executable, List<String> args, Map<String, String> environment,
                            long timeoutSeconds, Consumer<String> log) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        StringBuilder output = new StringBuilder();
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < 48_000) output.append(line).append('\n');
                    log.accept(line);
                }
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Command timed out: " + executable.getFileName());
            }
            if (process.exitValue() != 0) {
                throw new IOException("Command failed (exit " + process.exitValue() + "): " + firstLine(output.toString()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted.", e);
        }
    }

    private boolean isProotReady(Path proot) {
        return canRun(proot, List.of("--version"), managedEnvironment(), 8)
                && canRun(proot, List.of("-R", "/", "/bin/true"), managedEnvironment(), 10);
    }

    private boolean canRun(Path executable, List<String> args, Map<String, String> environment, long timeoutSeconds) {
        try {
            readCommand(executable, args, environment, timeoutSeconds);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String readVersion(Path executable, Map<String, String> environment) {
        try {
            return firstLine(readCommand(executable, List.of("--version"), environment, 12));
        } catch (Exception e) {
            return "";
        }
    }

    private String readCommand(Path executable, List<String> args, Map<String, String> environment, long timeoutSeconds) throws IOException {
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
            String text;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null && out.length() < 24_000) out.append(line).append('\n');
                text = out.toString();
            }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Version check timed out.");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Command exited with " + process.exitValue() + ": " + firstLine(text));
            }
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Version check interrupted.", e);
        }
    }

    private Map<String, String> managedEnvironment() {
        Map<String, String> env = new LinkedHashMap<>();
        String oldPath = System.getenv("PATH");
        env.put("PATH", managedBinDir() + (oldPath == null || oldPath.isBlank() ? "" : ":" + oldPath));
        env.put("XDG_DATA_HOME", xdgDataHome().toString());
        env.put("XDG_CACHE_HOME", xdgCacheHome().toString());
        env.put("PD_FORCE_NO_COLORS", "1");
        return env;
    }

    private boolean isPythonReady(Path python) {
        if (python == null || !Files.isRegularFile(python) || !Files.isExecutable(python)) return false;
        try {
            String version = readCommand(python, List.of("-c", "import ssl, sys, venv; print(f'{sys.version_info.major}.{sys.version_info.minor}')"), managedEnvironment(), 10).trim();
            String[] pieces = version.split("\\.", 2);
            int major = Integer.parseInt(pieces[0]);
            int minor = Integer.parseInt(pieces.length == 2 ? pieces[1] : "0");
            return major > 3 || major == 3 && minor >= 9;
        } catch (Exception ignored) {
            return false;
        }
    }

    private HostTarget detectHost() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux")) throw new IOException("MJT PRoot-Distro supports Linux hosts only.");
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean musl = isMuslHost();
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return new HostTarget("x86_64", "amd64", "x86_64-unknown-linux-" + (musl ? "musl" : "gnu"));
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return new HostTarget("aarch64", "arm64", "aarch64-unknown-linux-" + (musl ? "musl" : "gnu"));
        }
        throw new IOException("Unsupported host architecture: " + arch + ". MJT supports x86_64 and aarch64 only.");
    }

    private boolean isMuslHost() {
        try {
            Process process = new ProcessBuilder("/usr/bin/env", "ldd", "--version").redirectErrorStream(true).start();
            String out;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                out = reader.readLine();
            }
            process.waitFor(3, TimeUnit.SECONDS);
            return out != null && out.toLowerCase(Locale.ROOT).contains("musl");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void requireSupportedHost() throws IOException {
        detectHost();
    }

    private Path systemRoot() {
        return stateStore.getConfigDir().resolve("system").toAbsolutePath().normalize();
    }

    private Path managedBinDir() {
        return systemRoot().resolve("bin");
    }

    private Path downloadsDir() {
        return systemRoot().resolve("downloads");
    }

    private Path pythonRoot() {
        return systemRoot().resolve("python/current");
    }

    private Path sitePackagesDir() {
        return systemRoot().resolve("proot-distro/site-packages");
    }

    private Path xdgDataHome() {
        return systemRoot().resolve("proot-distro/data");
    }

    private Path xdgCacheHome() {
        return systemRoot().resolve("proot-distro/cache");
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void makeExecutable(Path path) throws IOException {
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE
            );
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException ignored) {
            if (!path.toFile().setExecutable(true, false)) {
                throw new IOException("Could not mark executable: " + path);
            }
        }
    }

    private void setMode(Path path, int mode) {
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

    private Path safeTarget(Path root, String rawName) throws IOException {
        if (rawName == null || rawName.isBlank()) throw new IOException("Archive entry has no name.");
        String normalized = rawName.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains("\u0000")) throw new IOException("Unsafe archive path: " + rawName);
        Path resolved = root.resolve(normalized).normalize();
        if (!resolved.startsWith(root)) throw new IOException("Archive path escapes destination: " + rawName);
        return resolved;
    }

    /**
     * Validates a relative symlink exactly as a tar extractor must: the target
     * is resolved relative to the link's parent, and the resolved destination
     * must remain inside the extraction root. Relative links such as
     * {@code ../a/adm1178} are valid in CPython's portable archives when the
     * link itself lives below the same installation root.
     */
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
        return parent.relativize(resolved);
    }

    /**
     * Prevents an archive entry from writing through an earlier symlink. This
     * avoids symlink traversal even though valid in-tree relative symlinks are
     * accepted for portable Python archives.
     */
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
                throw new IOException("Archive entry traverses an existing symbolic link: " + normalizedRoot.relativize(current));
            }
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) return;
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
        try { return Long.parseLong(text.replaceAll("[^0-7]", ""), 8); }
        catch (NumberFormatException e) { return 0L; }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("(?i)[a-f0-9]{64}");
    }

    private static void requireDigest(Asset asset, String label) throws IOException {
        if (!isSha256(asset.sha256)) throw new IOException(label + " did not expose a SHA-256 digest. Refusing an unverified install.");
    }

    private static String firstLine(String value) {
        if (value == null || value.isBlank()) return "";
        int index = value.indexOf('\n');
        return (index < 0 ? value : value.substring(0, index)).trim();
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value));
        return text.toString();
    }

    private void writeLog(String line) {
        try { logService.write(line); } catch (IOException ignored) { }
    }

    private static String messageOf(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private record Asset(String name, String url, String sha256) {
    }

    private record HostTarget(String prootArchitecture, String displayArchitecture, String pythonTriple) {
    }
}
