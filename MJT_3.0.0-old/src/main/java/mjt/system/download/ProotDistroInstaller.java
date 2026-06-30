package main.java.mjt.system.download;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

/**
 * Installs MJT-managed PRoot and the upstream proot-distro wheel.
 *
 * <p>Python installation is delegated to {@link PortablePythonInstaller}; this
 * file independently detects Linux kernel, 64-bit architecture, libc and
 * {@code /etc/os-release} before selecting the matching PRoot release asset.</p>
 */
public final class ProotDistroInstaller {
    public static final String PROOT_VERSION = "5.4.0";
    public static final String DEFAULT_PROOT_DISTRO_VERSION = "5.4.0";

    private static final String PROOT_RELEASE_API =
            "https://api.github.com/repos/proot-me/proot/releases/tags/v" + PROOT_VERSION;
    private static final String PYPI_PROOT_DISTRO_API = "https://pypi.org/pypi/proot-distro/%s/json";

    private static final String KEY_PROOT = "proot.binary";
    private static final String KEY_PROOT_VERSION = "system.download.proot.version";
    private static final String KEY_PROOT_SHA256 = "system.download.proot.sha256";
    private static final String KEY_PROOT_HOST = "system.download.proot.host";
    private static final String KEY_ENGINE = "proot.distro.engineExecutable";
    private static final String KEY_ENGINE_VERSION = "proot.distro.engineVersion";
    private static final String KEY_STATUS = "system.download.proot-distro.status";

    private static final long MAX_PROOT_BYTES = 20L * 1024L * 1024L;
    private static final long MAX_WHEEL_BYTES = 40L * 1024L * 1024L;
    private static final int MAX_OUTPUT_CHARS = 48_000;

    private final StateStore stateStore;
    private final LogService logService;
    private final PortablePythonInstaller pythonInstaller;
    private final HttpClient httpClient;

    public ProotDistroInstaller(
            StateStore stateStore,
            LogService logService,
            PortablePythonInstaller pythonInstaller
    ) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.pythonInstaller = pythonInstaller;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(25))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public record EnginePaths(Path proot, Path python, Path prootDistro, String prootDistroVersion) {
    }

    /** Installs PRoot + proot-distro. Portable Python is requested from its separate installer. */
    public synchronized EnginePaths install(String requestedVersion, Consumer<String> sink) throws IOException {
        Consumer<String> log = sink == null ? ignored -> { } : sink;
        HostProfile host = detectHost();
        logHost(log, host);

        String version = normalizeDistroVersion(requestedVersion);
        Path python = pythonInstaller.install(log);
        if (!isPythonReady(python)) {
            throw new IOException("Portable Python installer returned an unusable executable: " + python);
        }

        Path proot = installProot(host, log);
        Path engine = installProotDistro(version, python, proot, log);
        return new EnginePaths(proot, python, engine, readVersion(engine, managedEnvironment(python)));
    }

    public synchronized Path installProot(Consumer<String> sink) throws IOException {
        Consumer<String> log = sink == null ? ignored -> { } : sink;
        return installProot(detectHost(), log);
    }

    public synchronized boolean check(boolean quiet) {
        try {
            HostProfile host = detectHost();
            Path python = pythonInstaller.currentExecutable();
            Path proot = prootPath();
            Path engine = enginePath();
            Map<String, String> env = managedEnvironment(python);

            boolean ready = isPythonReady(python)
                    && isProotReady(proot, env)
                    && canRun(engine, List.of("--version"), env, 12);
            if (!quiet) {
                System.out.println(ready
                        ? "\u001B[32m[MJT SYSTEM] PRoot-Distro engine is ready.\u001B[0m"
                        : "\u001B[33m[MJT SYSTEM] Engine is not ready. Run: .mjt system install proot-distro\u001B[0m");
                System.out.println("Host: " + host.describe());
            }
            return ready;
        } catch (Exception e) {
            if (!quiet) {
                System.out.println("\u001B[31m[MJT SYSTEM] Check failed: " + messageOf(e) + "\u001B[0m");
            }
            return false;
        }
    }

    public synchronized void printStatus() {
        try {
            HostProfile host = detectHost();
            Path python = pythonInstaller.currentExecutable();
            Path proot = prootPath();
            Path engine = enginePath();
            Map<String, String> env = managedEnvironment(python);

            System.out.println("\u001B[36m[MJT SYSTEM - PROOT-DISTRO]\u001B[0m");
            System.out.println("Kernel             : " + host.kernelName() + " " + host.kernelRelease());
            System.out.println("Architecture       : " + host.architecture() + " (" + host.bits() + "-bit)");
            System.out.println("libc               : " + host.libc());
            System.out.println("os-release         : " + host.osReleaseSummary());
            System.out.println("PRoot asset        : " + host.prootAssetArchitecture());
            System.out.println("PRoot              : " + proot + " | ready=" + isProotReady(proot, env));
            System.out.println("Python             : " + python + " | ready=" + isPythonReady(python));
            System.out.println("proot-distro       : " + engine + " | ready="
                    + canRun(engine, List.of("--version"), env, 12));
            System.out.println("XDG data home      : " + xdgDataHome());
            System.out.println("XDG cache home     : " + xdgCacheHome());
        } catch (Exception e) {
            System.out.println("\u001B[31m[MJT SYSTEM] " + messageOf(e) + "\u001B[0m");
        }
    }

    private Path installProot(HostProfile host, Consumer<String> log) throws IOException {
        Path target = prootPath();
        if (isProotReady(target, managedEnvironment(pythonInstaller.currentExecutable()))) {
            log.accept("Using existing MJT-managed PRoot: " + target);
            return target;
        }

        String assetName = "proot-v" + PROOT_VERSION + "-" + host.prootAssetArchitecture() + "-static";
        Asset asset = resolveProotAsset(host, assetName, log);

        Path downloadDir = downloadsDir().resolve("proot");
        Files.createDirectories(downloadDir);
        Path downloaded = downloadDir.resolve(assetName + ".download");
        String actualHash = downloadAndVerify(asset, downloaded, MAX_PROOT_BYTES, true, log);

        try {
            Files.createDirectories(target.getParent());
            moveAtomically(downloaded, target);
            makeExecutable(target);
        } finally {
            Files.deleteIfExists(downloaded);
        }

        if (!isProotReady(target, managedEnvironment(pythonInstaller.currentExecutable()))) {
            throw new IOException("Downloaded PRoot failed version/ptrace smoke test: " + target);
        }

        stateStore.set(KEY_PROOT, target.toString());
        stateStore.set(KEY_PROOT_VERSION,
                firstLine(readCommand(target, List.of("--version"), managedEnvironment(pythonInstaller.currentExecutable()), 8)));
        stateStore.set(KEY_PROOT_SHA256, actualHash);
        stateStore.set(KEY_PROOT_HOST, host.describe());

        log.accept("PRoot ready: " + target);
        writeLog("[MJT SYSTEM PROOT] installed=" + target + " host=" + host.describe() + "\n");
        return target;
    }

    private Path installProotDistro(String version, Path python, Path proot, Consumer<String> log) throws IOException {
        Map<String, String> env = managedEnvironment(python);
        if (!isPythonReady(python)) throw new IOException("Portable Python is unavailable.");
        if (!isProotReady(proot, env)) throw new IOException("PRoot is unavailable or cannot use ptrace.");

        Path engine = enginePath();
        if (canRun(engine, List.of("--version"), env, 12)) {
            String current = readVersion(engine, env);
            if (current.contains(version)) {
                stateStore.set(KEY_ENGINE, engine.toString());
                stateStore.set(KEY_ENGINE_VERSION, current);
                log.accept("Using existing MJT-managed proot-distro: " + engine);
                return engine;
            }
        }

        Files.createDirectories(sitePackagesDir());
        log.accept("Preparing pip inside MJT portable Python...");
        runChecked(python, List.of("-m", "ensurepip", "--upgrade"), env, 120, log);

        Asset wheelAsset = resolveProotDistroWheel(version, log);
        Path wheelDir = downloadsDir().resolve("proot-distro");
        Files.createDirectories(wheelDir);
        Path downloaded =
                wheelDir.resolve(wheelAsset.name() + ".download");
            
        Path wheel =
                wheelDir.resolve(wheelAsset.name());
            
        downloadAndVerify(
                wheelAsset,
                downloaded,
                MAX_WHEEL_BYTES,
                false,
                log
        );
        
        moveAtomically(
                downloaded,
                wheel
        );
        
        runChecked(
                python,
                List.of(
                        "-m",
                        "pip",
                        "install",
                        "--disable-pip-version-check",
                        "--no-input",
                        "--no-deps",
                        "--no-index",
                        "--upgrade",
                        "--target",
                        sitePackagesDir().toString(),
                        wheel.toString()
                ),
                env,
                180,
                log
        );
        
        Files.deleteIfExists(wheel);
        

        writeEngineLauncher(engine, python);
        if (!canRun(engine, List.of("--version"), env, 12)) {
            throw new IOException("proot-distro installed but its MJT launcher could not run.");
        }

        String detected = readVersion(engine, env);
        stateStore.set(KEY_ENGINE, engine.toString());
        stateStore.set(KEY_ENGINE_VERSION, detected);
        stateStore.set(KEY_STATUS, "installed");
        log.accept("proot-distro ready: " + engine + " | " + detected);
        writeLog("[MJT SYSTEM PROOT-DISTRO] installed=" + engine + " version=" + detected + "\n");
        return engine;
    }

    private Asset resolveProotAsset(HostProfile host, String assetName, Consumer<String> log) throws IOException {
        String overrideUrl = stateStore.get("system.download.proot.url.override", "").trim();
        String overrideHash = stateStore.get("system.download.proot.sha256.override", "").trim();
        if (!overrideUrl.isBlank() || !overrideHash.isBlank()) {
            if (overrideUrl.isBlank() || !isSha256(overrideHash)) {
                throw new IOException("PRoot override requires both URL and SHA-256.");
            }
            return new Asset(assetName, overrideUrl, overrideHash.toLowerCase(Locale.ROOT));
        }

        for (Asset asset : parseAssets(fetchText(PROOT_RELEASE_API))) {
            if (!asset.name().equals(assetName)) continue;
            String expectedUrl = "https://github.com/proot-me/proot/releases/download/v"
                    + PROOT_VERSION + "/" + assetName;
            if (!expectedUrl.equals(asset.url())) {
                throw new IOException("Refusing unexpected PRoot URL for " + assetName + ".");
            }
            log.accept("Selected PRoot asset for " + host.describe() + ": " + asset.name());
            log.accept("Selected link: " + asset.url());
            return asset;
        }
        throw new IOException("No PRoot asset named " + assetName + " for " + host.describe());
    }

    private Asset resolveProotDistroWheel(String version, Consumer<String> log) throws IOException {
        String overrideUrl = stateStore.get("system.download.proot-distro.url.override", "").trim();
        String overrideHash = stateStore.get("system.download.proot-distro.sha256.override", "").trim();
        if (!overrideUrl.isBlank() || !overrideHash.isBlank()) {
            if (overrideUrl.isBlank() || !isSha256(overrideHash)) {
                throw new IOException("proot-distro override requires both URL and SHA-256.");
            }
            return new Asset("proot-distro-" + version + ".whl", overrideUrl, overrideHash.toLowerCase(Locale.ROOT));
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
                requireSha256(asset, "PyPI proot-distro wheel");
                log.accept("Selected verified universal proot-distro wheel: " + asset.name());
                return asset;
            }
        }
        throw new IOException("Verified universal wheel not found for proot-distro==" + version);
    }

    private String downloadAndVerify(Asset asset, Path output, long maxBytes, boolean allowMissingPublisherDigest,
                                     Consumer<String> log) throws IOException {
        boolean hasPublisherDigest = isSha256(asset.sha256());
        if (!hasPublisherDigest && !allowMissingPublisherDigest) {
            throw new IOException("Asset has no SHA-256 digest: " + asset.name());
        }

        try {
            Files.deleteIfExists(output);
            HttpRequest request = HttpRequest.newBuilder(URI.create(asset.url()))
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
                for (int count; (count = input.read(buffer)) >= 0; ) {
                    written += count;
                    if (written > maxBytes) throw new IOException("Download exceeds safe size limit.");
                    digest.update(buffer, 0, count);
                    target.write(buffer, 0, count);
                }
            }
            if (written == 0L) throw new IOException("Downloaded file is empty.");

            String actual = hex(digest.digest());
            if (hasPublisherDigest && !actual.equalsIgnoreCase(asset.sha256())) {
                throw new IOException("SHA-256 mismatch for " + asset.name() + ".");
            }
            if (hasPublisherDigest) {
                log.accept("Downloaded and verified " + asset.name() + " (" + written + " bytes).");
            } else {
                log.accept("Downloaded exact pinned PRoot release asset without publisher SHA-256 ("
                        + written + " bytes). Local SHA-256: " + actual + ".");
            }
            return actual;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while downloading " + asset.name() + ".", e);
        } catch (IOException e) {
            Files.deleteIfExists(output);
            throw e;
        } catch (Exception e) {
            Files.deleteIfExists(output);
            throw new IOException("Download verification failed: " + messageOf(e), e);
        }
    }

    private void writeEngineLauncher(Path launcher, Path python) throws IOException {
        Files.createDirectories(launcher.getParent());
        String script = "#!/bin/sh\n"
                + "export PYTHONPATH=" + shellQuote(sitePackagesDir().toString()) + "${PYTHONPATH:+:$PYTHONPATH}\n"
                + "exec " + shellQuote(python.toString())
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

        try {
            Process process = builder.start();
            String text = readProcessOutput(process.getInputStream(), MAX_OUTPUT_CHARS, log);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Command timed out: " + executable.getFileName());
            }
            if (process.exitValue() != 0) {
                throw new IOException("Command failed (exit " + process.exitValue() + "): " + firstLine(text));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted.", e);
        }
    }

    private boolean isProotReady(Path proot, Map<String, String> environment) {
        return canRun(proot, List.of("--version"), environment, 8)
                && canRun(proot, List.of("-R", "/", "/bin/true"), environment, 10);
    }

    private boolean isPythonReady(Path python) {
        return canRun(python, List.of("-c", "import ssl, sys, venv; print(sys.version_info[:2])"),
                managedEnvironment(python), 12);
    }

    private boolean canRun(Path executable, List<String> args, Map<String, String> environment, long timeoutSeconds) {
    try {

        String output =
                readCommand( executable, args, environment, timeoutSeconds);
        System.out.println(output);
        return true;

    } catch (Exception e) {
        e.printStackTrace();
        return false;
    }
}

    private String readVersion(Path executable, Map<String, String> environment) {
        try {
            return firstLine(readCommand(executable, List.of("--version"), environment, 12));
        } catch (Exception ignored) {
            return "";
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
            String text = readProcessOutput(process.getInputStream(), MAX_OUTPUT_CHARS, null);
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

    private Map<String, String> managedEnvironment(Path python) {
        Map<String, String> env = new LinkedHashMap<>();
        String oldPath = System.getenv("PATH");
        String pythonBin = python == null || python.getParent() == null ? "" : ":" + python.getParent();
        env.put("PATH", managedBinDir() + pythonBin + (oldPath == null || oldPath.isBlank() ? "" : ":" + oldPath));
        env.put("XDG_DATA_HOME", xdgDataHome().toString());
        env.put("XDG_CACHE_HOME", xdgCacheHome().toString());
        env.put("PD_FORCE_NO_COLORS", "1");
        return env;
    }

    private HostProfile detectHost() throws IOException {
        String kernelName = firstNonBlank(runSmallCommand(4, "uname", "-s"), System.getProperty("os.name", ""));
        if (!kernelName.toLowerCase(Locale.ROOT).contains("linux")) {
            throw new IOException("PRoot-Distro installer supports Linux hosts only; detected: " + kernelName);
        }

        String kernelRelease = firstNonBlank(runSmallCommand(4, "uname", "-r"), "unknown");
        String rawArch = firstNonBlank(runSmallCommand(4, "uname", "-m"), System.getProperty("os.arch", ""));
        String bits = firstNonBlank(runSmallCommand(4, "getconf", "LONG_BIT"), "64");
        if (!"64".equals(bits.trim())) {
            throw new IOException("Unsupported " + bits + "-bit Linux host. PRoot requires 64-bit Linux.");
        }

        String architecture = normalizeArchitecture(rawArch);
        String prootArch = architecture.equals("x86_64") ? "x86_64" : "aarch64";
        Map<String, String> osRelease = readOsRelease();
        String libc = isMuslHost(osRelease) ? "musl" : "gnu";

        return new HostProfile(kernelName.trim(), kernelRelease.trim(), rawArch.trim(), architecture,
                prootArch, bits.trim(), libc, osRelease);
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
        log.accept("Selected PRoot asset architecture: " + host.prootAssetArchitecture());
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
                throw new IOException("Upstream metadata request failed: HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while reading upstream metadata.", e);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid upstream metadata URL.", e);
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

    private List<String> jsonObjectsInArray(String json, String field) {
        List<String> result = new ArrayList<>();
        int fieldAt = json.indexOf("\"" + field + "\"");
        if (fieldAt < 0) return result;
        int arrayStart = json.indexOf('[', fieldAt);
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
                    result.add(json.substring(objectStart, index + 1));
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

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String readProcessOutput(InputStream input, int maxChars, Consumer<String> log) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() < maxChars) out.append(line).append('\n');
                if (log != null) log.accept(line);
            }
            return out.toString();
        }
    }

    private String runSmallCommand(long timeoutSeconds, String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = readProcessOutput(process.getInputStream(), 8_000, null);
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return process.exitValue() == 0 ? output.trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeDistroVersion(String requested) throws IOException {
        String version = requested == null || requested.isBlank()
                ? DEFAULT_PROOT_DISTRO_VERSION
                : requested.trim();
        if (!version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:[A-Za-z0-9._-]+)?")) {
            throw new IOException("Invalid proot-distro version: " + version);
        }
        return version;
    }

    private Path systemRoot() {
        return stateStore.getConfigDir().resolve("system").toAbsolutePath().normalize();
    }

    private Path downloadsDir() {
        return systemRoot().resolve("downloads");
    }

    private Path managedBinDir() {
        return systemRoot().resolve("bin");
    }

    private Path prootPath() {
        return managedBinDir().resolve("proot");
    }

    private Path enginePath() {
        return managedBinDir().resolve("proot-distro");
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

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
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

    private record HostProfile(
            String kernelName,
            String kernelRelease,
            String rawArchitecture,
            String architecture,
            String prootAssetArchitecture,
            String bits,
            String libc,
            Map<String, String> osRelease
    ) {
        String osReleaseSummary() {
            return osRelease.getOrDefault("ID", "unknown") + " "
                    + osRelease.getOrDefault("VERSION_ID", "unknown");
        }

        String describe() {
            return kernelName + " " + kernelRelease + " | " + architecture + " | " + libc
                    + " | " + osReleaseSummary();
        }
    }
}
