package main.java.mjt.services.proot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;
import main.java.mjt.system.download.ProotDistroInstaller;
import main.java.mjt.system.download.PortablePythonInstaller;

/**
 * MJT adapter around the upstream proot-distro executable.
 *
 * <p>MJT deliberately owns only its configuration, jobs and panel-facing
 * catalog. Image downloading, OCI layer verification, storage and rootfs
 * assembly remain upstream responsibilities of proot-distro.</p>
 *
 * <p>Only native x86_64 and aarch64 environments are exposed by MJT. No QEMU,
 * no cross-architecture image pulls and no host-global installation are used.</p>
 */
public final class ProotDistroService {
    public static final String PINNED_ENGINE_VERSION = "5.3.0";

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static final String KEY_ENABLED = "proot.distro.enabled";
    private static final String KEY_ACTIVE = "proot.distro.active";
    private static final String KEY_PYTHON = "proot.distro.python";
    /** Optional absolute path to an upstream proot-distro executable. */
    private static final String KEY_ENGINE_EXECUTABLE = "proot.distro.engineExecutable";
    private static final String KEY_ENGINE_VERSION = "proot.distro.engineVersion";
    private static final String KEY_SOURCE_PREFIX = "proot.distro.environment.";
    private static final int MAX_JOB_LOG_LINES = 700;

    private final StateStore stateStore;
    private final LogService logService;
    private final ExecutorService jobExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "mjt-proot-distro-job");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, DistroJob> jobs = new ConcurrentHashMap<>();
    private final AtomicBoolean mutationInProgress = new AtomicBoolean(false);

    public ProotDistroService(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
    }

    public record CatalogEntry(
            String id,
            String label,
            String image,
            String hostArchitecture,
            String displayArchitecture,
            String packageManager
    ) {
    }

    public record EnvironmentInfo(
            String name,
            String source,
            String rootfs,
            String architecture,
            boolean active,
            boolean ready
    ) {
    }

    public record EngineInfo(
            boolean enabled,
            boolean linuxHost,
            String hostArchitecture,
            String displayArchitecture,
            boolean architectureSupported,
            String python,
            boolean pythonReady,
            String proot,
            boolean prootReady,
            String enginePath,
            boolean engineReady,
            String engineVersion,
            String activeEnvironment,
            String runtimeDirectory,
            String cacheDirectory
    ) {
    }

    public record JobInfo(
            String id,
            String type,
            String target,
            String state,
            String message,
            String createdAt,
            String startedAt,
            String finishedAt,
            List<String> logs
    ) {
    }

    /** Creates only MJT-owned folders. It never downloads or installs anything. */
    public synchronized void initializeDirectories() throws IOException {
        Files.createDirectories(engineRoot());
        Files.createDirectories(venvRoot().getParent());
        Files.createDirectories(xdgDataHome());
        Files.createDirectories(xdgCacheHome());
        Files.createDirectories(containersRoot());
    }

    public boolean isEnabled() {
        return stateStore.getBoolean(KEY_ENABLED, true);
    }

    public boolean isActiveAndReady() {
        try {
            String active = getActiveEnvironment();
            return !active.isBlank() && isEnvironmentReady(active) && isEngineReady();
        } catch (Exception ignored) {
            return false;
        }
    }

    public synchronized void validateActiveRuntime() throws IOException {
        if (!isEnabled()) {
            throw new IOException("PRoot-Distro integration is disabled.");
        }
        requireSupportedHost();
        String active = getActiveEnvironment();
        if (active.isBlank()) {
            throw new IOException("No active environment. Install Debian, Ubuntu, or Alpine first.");
        }
        if (!isEngineReady()) {
            throw new IOException("PRoot-Distro engine is not ready. Run: .mjt proot distro engine install");
        }
        if (!isEnvironmentReady(active)) {
            throw new IOException("Active environment is missing or invalid: " + active);
        }
    }

    public synchronized List<String> buildGuestCommand(
            String shellCommand,
            Path hostWorkingDirectory,
            Path hostWorkspace,
            String guestWorkspace
    ) throws IOException {
        validateActiveRuntime();
        Path workspace = hostWorkspace.toAbsolutePath().normalize();
        Path workingDirectory = (hostWorkingDirectory == null ? workspace : hostWorkingDirectory)
                .toAbsolutePath()
                .normalize();
        if (!workingDirectory.startsWith(workspace)) {
            throw new IOException("Host working directory must stay inside proot.workspace: " + workingDirectory);
        }

        Path relative = workspace.relativize(workingDirectory);
        String guestWorkingDirectory = guestWorkspace;
        if (relative.getNameCount() > 0) {
            guestWorkingDirectory += "/" + relative.toString().replace('\\', '/');
        }

        List<String> command = new ArrayList<>();
        // ProotService and GuestServiceManager receive only a command list, so
        // preserve MJT's isolated upstream storage through /usr/bin/env.
        command.add("/usr/bin/env");
        command.add("XDG_DATA_HOME=" + xdgDataHome());
        command.add("XDG_CACHE_HOME=" + xdgCacheHome());
        command.add("PD_FORCE_NO_COLORS=1");
        command.add("PATH=" + runtimePath());
        command.add(engineExecutable().toString());
        command.add("login");
        command.add(getActiveEnvironment());
        command.add("--bind");
        command.add(workspace + ":" + guestWorkspace);
        command.add("--work-dir");
        command.add(guestWorkingDirectory);
        command.add("--env");
        command.add("MJT_WORKSPACE=" + guestWorkspace);
        command.add("--env");
        command.add("MJT_GUEST_CWD=" + guestWorkingDirectory);
        command.add("--env");
        command.add("TERM=xterm-256color");
        command.add("--");
        command.add("/bin/sh");
        command.add("-lc");
        command.add(shellCommand == null ? "" : shellCommand);
        return command;
    }

    public List<CatalogEntry> catalog() {
        HostArchitecture architecture = detectArchitecture();
        if (!architecture.supported()) {
            return Collections.emptyList();
        }
        return List.of(
                new CatalogEntry("debian-12", "Debian 12", "debian:12", architecture.prootArchitecture(), architecture.displayArchitecture(), "apt"),
                new CatalogEntry("ubuntu-24.04", "Ubuntu 24.04", "ubuntu:24.04", architecture.prootArchitecture(), architecture.displayArchitecture(), "apt"),
                new CatalogEntry("alpine-3.21", "Alpine 3.21", "alpine:3.21", architecture.prootArchitecture(), architecture.displayArchitecture(), "apk")
        );
    }

    public synchronized EngineInfo engineInfo() {
        HostArchitecture architecture = detectArchitecture();
        Path python;
        try {
            python = resolvePython(false);
        } catch (IOException ignored) {
            python = null;
        }
        Path proot = resolveProotBinary();
        Path engine = engineExecutable();
        return new EngineInfo(
                isEnabled(),
                isLinuxHost(),
                architecture.prootArchitecture(),
                architecture.displayArchitecture(),
                architecture.supported(),
                python == null ? "" : python.toString(),
                python != null && isPythonAtLeast39(python),
                proot.toString(),
                isExecutable(proot),
                engine.toString(),
                isEngineReady(),
                readEngineVersion(),
                getActiveEnvironment(),
                runtimeRoot().toString(),
                cacheRoot().toString()
        );
    }

    public synchronized List<EnvironmentInfo> environments() throws IOException {
        initializeDirectories();
        List<EnvironmentInfo> result = new ArrayList<>();
        if (!Files.isDirectory(containersRoot())) {
            return result;
        }
        String active = getActiveEnvironment();
        try (var stream = Files.list(containersRoot())) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        String name = path.getFileName().toString();
                        Path rootfs = path.resolve("rootfs");
                        String source = stateStore.get(KEY_SOURCE_PREFIX + name + ".source", "upstream-managed");
                        String arch = stateStore.get(KEY_SOURCE_PREFIX + name + ".architecture", "native");
                        result.add(new EnvironmentInfo(
                                name,
                                source,
                                rootfs.toString(),
                                arch,
                                name.equals(active),
                                isRootfsReady(rootfs)
                        ));
                    });
        }
        return result;
    }

    public synchronized EnvironmentInfo environment(String rawName) throws IOException {
        String name = validateEnvironmentName(rawName);
        for (EnvironmentInfo environment : environments()) {
            if (environment.name().equals(name)) {
                return environment;
            }
        }
        throw new IOException("Environment not found: " + name);
    }

    public synchronized String installEngineAsync() throws IOException {
        return submit("engine-install", PINNED_ENGINE_VERSION, job -> installEngine(job, PINNED_ENGINE_VERSION, false));
    }

    public synchronized String updateEngineAsync(String requestedVersion) throws IOException {
        String version = requestedVersion == null || requestedVersion.isBlank()
                ? PINNED_ENGINE_VERSION
                : requestedVersion.trim();
        if (!version.matches("[0-9]+(?:\\.[0-9]+){1,3}(?:[A-Za-z0-9._-]+)?")) {
            throw new IOException("Invalid proot-distro version.");
        }
        return submit("engine-update", version, job -> installEngine(job, version, true));
    }

    public synchronized String installEnvironmentAsync(String catalogId, String requestedName, boolean activate) throws IOException {
        CatalogEntry entry = findCatalog(catalogId);
        String name = requestedName == null || requestedName.isBlank()
                ? entry.id()
                : validateEnvironmentName(requestedName);
        return submit("environment-install", name, job -> installEnvironment(job, entry, name, activate));
    }

    public synchronized String activateEnvironmentAsync(String rawName) throws IOException {
        String name = validateEnvironmentName(rawName);
        return submit("environment-activate", name, job -> activateEnvironment(job, name));
    }

    public synchronized String removeEnvironmentAsync(String rawName) throws IOException {
        String name = validateEnvironmentName(rawName);
        return submit("environment-remove", name, job -> removeEnvironment(job, name));
    }

    public JobInfo getJob(String rawId) throws IOException {
        DistroJob job = jobs.get(rawId == null ? "" : rawId.trim());
        if (job == null) {
            throw new IOException("Distro job not found.");
        }
        return job.snapshot();
    }

    public void showStatus() {
        EngineInfo info = engineInfo();
        System.out.println(CYAN + "[MJT PROOT-DISTRO]" + RESET);
        System.out.println("Enabled           : " + info.enabled());
        System.out.println("Host OS           : " + (info.linuxHost() ? "Linux" : System.getProperty("os.name")));
        System.out.println("Host architecture : " + info.hostArchitecture());
        System.out.println("Recommended arch  : " + info.displayArchitecture());
        System.out.println("Architecture ready: " + info.architectureSupported());
        System.out.println("Python            : " + blankAsDash(info.python()) + " (>=3.9: " + info.pythonReady() + ")");
        System.out.println("PRoot             : " + info.proot() + " (ready: " + info.prootReady() + ")");
        System.out.println("Engine            : " + info.enginePath() + " (ready: " + info.engineReady() + ")");
        System.out.println("Engine version    : " + blankAsDash(info.engineVersion()));
        System.out.println("Active environment: " + blankAsDash(info.activeEnvironment()));
        System.out.println("Runtime directory : " + info.runtimeDirectory());
        System.out.println("Cache directory   : " + info.cacheDirectory());
        System.out.println();
        if (!info.engineReady()) {
            System.out.println(YELLOW + "Install/adopt upstream engine with: .mjt proot distro engine install" + RESET);
        }
    }

    public void printCatalog() {
        HostArchitecture architecture = detectArchitecture();
        System.out.println(CYAN + "[MJT ENVIRONMENT CATALOG]" + RESET);
        System.out.println("Host architecture: " + architecture.prootArchitecture());
        System.out.println();
        for (CatalogEntry entry : catalog()) {
            System.out.println("- " + entry.id() + " | " + entry.label() + " | " + entry.displayArchitecture() + " | " + entry.packageManager());
        }
    }

    public void printEnvironments() {
        try {
            List<EnvironmentInfo> entries = environments();
            System.out.println(CYAN + "[MJT ENVIRONMENTS]" + RESET);
            if (entries.isEmpty()) {
                System.out.println("No environment installed.");
                return;
            }
            for (EnvironmentInfo entry : entries) {
                System.out.println("- " + entry.name()
                        + (entry.active() ? " [ACTIVE]" : "")
                        + " | ready=" + entry.ready()
                        + " | arch=" + entry.architecture()
                        + " | " + entry.source());
            }
        } catch (IOException e) {
            System.out.println(RED + "[PRoot-Distro] " + e.getMessage() + RESET);
        }
    }

    public void printJob(String id) {
        try {
            JobInfo job = getJob(id);
            System.out.println(CYAN + "[MJT DISTRO JOB]" + RESET);
            System.out.println("ID      : " + job.id());
            System.out.println("Type    : " + job.type());
            System.out.println("Target  : " + job.target());
            System.out.println("State   : " + job.state());
            System.out.println("Message : " + job.message());
            for (String line : job.logs()) {
                System.out.println(line);
            }
        } catch (IOException e) {
            System.out.println(RED + "[PRoot-Distro] " + e.getMessage() + RESET);
        }
    }

    public String getActiveEnvironment() {
        return stateStore.get(KEY_ACTIVE, "").trim();
    }

    public Path activeRootfs() {
        String active = getActiveEnvironment();
        return active.isBlank() ? null : rootfsPath(active);
    }

    private String submit(String type, String target, JobAction action) throws IOException {
        if (!isEnabled()) {
            throw new IOException("PRoot-Distro integration is disabled.");
        }
        if (!mutationInProgress.compareAndSet(false, true)) {
            throw new IOException("Another distro operation is already running. Wait for it to finish.");
        }
        DistroJob job = new DistroJob(type, target);
        jobs.put(job.id, job);
        jobExecutor.execute(() -> {
            job.start();
            try {
                action.run(job);
                job.succeed();
            } catch (Exception e) {
                job.fail(messageOf(e));
                logQuietly("[PROOT DISTRO JOB ERROR] " + job.id + " " + messageOf(e) + "\n");
            } finally {
                mutationInProgress.set(false);
            }
        });
        return job.id;
    }

    /**
     * Uses an upstream proot-distro executable when the host image already
     * provides one. This is the normal production path: MJT is only an
     * adapter and does not fork, vendor or reimplement the upstream tool.
     *
     * <p>For development-only hosts, a local MJT venv remains a fallback when
     * Python 3.9+ and PRoot are already available. This fallback does not try
     * to install operating-system packages; a locked-down host must provide
     * those two prerequisites in its base image.</p>
     */
    private void installEngine(DistroJob job, String version, boolean upgrade) throws IOException {
        requireSupportedHost();
        initializeDirectories();
        job.log("Preparing MJT-managed PRoot, portable Python and upstream proot-distro...");

        PortablePythonInstaller pythonInstaller = new PortablePythonInstaller(stateStore, logService);
        ProotDistroInstaller installer = new ProotDistroInstaller(
                stateStore,
                logService,
                pythonInstaller
        );
        ProotDistroInstaller.EnginePaths paths = installer.install(version, job::log);

        stateStore.set("proot.binary", paths.proot().toString());
        stateStore.set(KEY_PYTHON, paths.python().toString());
        stateStore.set(KEY_ENGINE_EXECUTABLE, paths.prootDistro().toString());
        stateStore.set(KEY_ENGINE_VERSION, paths.prootDistroVersion());

        if (!isEngineReady()) {
            throw new IOException("MJT environment engine was installed but did not pass readiness checks.");
        }
        job.log("Engine ready: " + paths.prootDistro());
        job.log("Upstream rootfs data: " + runtimeRoot());
        log("[PROOT DISTRO ENGINE] installed=" + paths.prootDistroVersion() + "\n");
    }

    private void installEnvironment(DistroJob job, CatalogEntry entry, String name, boolean activate) throws IOException {
        requireSupportedHost();
        ensureEngineReady(job);
        if (Files.exists(rootfsPath(name))) {
            throw new IOException("Environment already exists: " + name);
        }
        // Do not pass --architecture. Upstream resolves the OCI manifest to the native host CPU by default.
        job.log("Pulling " + entry.image() + " for native " + entry.hostArchitecture() + "...");
        run(job, List.of(
                engineExecutable().toString(),
                "install",
                entry.image(),
                "--name", name
        ));
        Path rootfs = rootfsPath(name);
        if (!isRootfsReady(rootfs)) {
            throw new IOException("Installed environment is incomplete: " + rootfs);
        }
        createServiceStartPolicy(rootfs);
        stateStore.set(KEY_SOURCE_PREFIX + name + ".source", entry.image());
        stateStore.set(KEY_SOURCE_PREFIX + name + ".architecture", entry.displayArchitecture());
        if (activate) {
            activateEnvironment(job, name);
        }
        job.log("Environment installed: " + name);
        log("[PROOT DISTRO INSTALL] name=" + name + " image=" + entry.image() + "\n");
    }

    private void activateEnvironment(DistroJob job, String name) throws IOException {
        requireSupportedHost();
        ensureEngineReady(job);
        Path rootfs = rootfsPath(name);
        if (!isRootfsReady(rootfs)) {
            throw new IOException("Environment is missing or invalid: " + name);
        }
        createServiceStartPolicy(rootfs);
        stateStore.set(KEY_ACTIVE, name);
        // Compatibility bridge: legacy components still show proot.rootfs.
        stateStore.set("proot.rootfs", rootfs.toString());
        stateStore.set("proot.enabled", "true");
        job.log("Activated environment: " + name);
        log("[PROOT DISTRO ACTIVATE] name=" + name + "\n");
    }

    private void removeEnvironment(DistroJob job, String name) throws IOException {
        if (name.equals(getActiveEnvironment())) {
            throw new IOException("Cannot remove the active environment. Activate another environment first.");
        }
        ensureEngineReady(job);
        if (!Files.exists(rootfsPath(name))) {
            throw new IOException("Environment not found: " + name);
        }
        run(job, List.of(engineExecutable().toString(), "remove", name));
        stateStore.set(KEY_SOURCE_PREFIX + name + ".source", "");
        stateStore.set(KEY_SOURCE_PREFIX + name + ".architecture", "");
        job.log("Environment removed: " + name);
        log("[PROOT DISTRO REMOVE] name=" + name + "\n");
    }

    private void ensureEngineReady(DistroJob job) throws IOException {
        if (!isEngineReady()) {
            job.log("Engine is not installed; bootstrapping pinned version " + PINNED_ENGINE_VERSION + "...");
            installEngine(job, PINNED_ENGINE_VERSION, false);
        }
        if (!isEngineReady()) {
            throw new IOException("PRoot-Distro engine is unavailable after bootstrap.");
        }
    }

    private CatalogEntry findCatalog(String rawId) throws IOException {
        String id = rawId == null ? "" : rawId.trim().toLowerCase(Locale.ROOT);
        for (CatalogEntry entry : catalog()) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        throw new IOException("Unsupported environment. Use one of: debian-12, ubuntu-24.04, alpine-3.21.");
    }

    private void run(DistroJob job, List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        configureEnvironment(builder.environment());
        job.log("$ " + redact(command));
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    job.log(line);
                }
            }
            if (!process.waitFor(30, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Distro command timed out after 30 minutes.");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Distro command failed with exit code " + process.exitValue() + ".");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Distro operation interrupted.", interrupted);
        }
    }

    private void configureEnvironment(Map<String, String> environment) {
        environment.put("XDG_DATA_HOME", xdgDataHome().toString());
        environment.put("XDG_CACHE_HOME", xdgCacheHome().toString());
        environment.put("PD_FORCE_NO_COLORS", "1");
        environment.put("PATH", runtimePath());
    }

    private boolean isEngineReady() {
        Path engine = resolveUpstreamEngine();
        return engine != null
                && canRun(engine, "--version", 5)
                && canRun(resolveProotBinary(), "--version", 5);
    }

    private String readEngineVersion() {
        Path engine = resolveUpstreamEngine();
        if (engine == null) {
            return stateStore.get(KEY_ENGINE_VERSION, "");
        }
        String value = readVersion(engine);
        return value.isBlank() ? stateStore.get(KEY_ENGINE_VERSION, "") : value;
    }

    private String runtimePath() {
        List<String> parts = new ArrayList<>();
        Path engine = resolveUpstreamEngine();
        if (engine != null && engine.getParent() != null) {
            parts.add(engine.getParent().toString());
        }
        Path proot = resolveProotBinary();
        if (proot.getParent() != null) {
            parts.add(proot.getParent().toString());
        }
        String existing = System.getenv("PATH");
        if (existing != null && !existing.isBlank()) {
            parts.add(existing);
        }
        return String.join(":", parts);
    }

    private Path resolvePython(boolean required) throws IOException {
        String configured = stateStore.get(KEY_PYTHON, "").trim();
        if (!configured.isBlank()) {
            Path path = Paths.get(configured).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
            if (required) {
                throw new IOException("Configured Python executable does not exist: " + path);
            }
            return null;
        }
        for (String candidate : List.of("python3", "python")) {
            Path found = findExecutableOnPath(candidate);
            if (found != null) {
                return found;
            }
        }
        if (required) {
            throw new IOException("Python 3.9+ is required but python3/python was not found on the host PATH.");
        }
        return null;
    }

    private boolean isPythonAtLeast39(Path python) {
        if (python == null || !Files.isRegularFile(python)) {
            return false;
        }
        try {
            Process process = new ProcessBuilder(
                    python.toString(),
                    "-c",
                    "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
            ).redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0 || output == null) {
                return false;
            }
            String[] parts = output.trim().split("\\.", 2);
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts.length > 1 ? parts[1] : "0");
            return major > 3 || (major == 3 && minor >= 9);
        } catch (Exception ignored) {
            return false;
        }
    }

    private Path resolveProotBinary() {
        String configured = stateStore.get("proot.binary", "").trim();
        if (!configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        Path managed = stateStore.getConfigDir().resolve("system/bin/proot").toAbsolutePath().normalize();
        if (isExecutable(managed)) {
            return managed;
        }
        Path host = findExecutableOnPath("proot");
        return host == null ? managed : host;
    }

    private Path findExecutableOnPath(String name) {
        String rawPath = System.getenv("PATH");
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        for (String part : rawPath.split(":", -1)) {
            if (part.isBlank()) continue;
            Path candidate = Paths.get(part).resolve(name);
            if (isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private HostArchitecture detectArchitecture() {
        String raw = System.getProperty("os.arch", "").trim().toLowerCase(Locale.ROOT);
        switch (raw) {
            case "amd64":
            case "x86_64":
                return new HostArchitecture("x86_64", "amd64", true);
            case "aarch64":
            case "arm64":
                return new HostArchitecture("aarch64", "arm64", true);
            default:
                return new HostArchitecture(raw.isBlank() ? "unknown" : raw, "unsupported", false);
        }
    }

    private void requireSupportedHost() throws IOException {
        if (!isLinuxHost()) {
            throw new IOException("MJT PRoot-Distro is supported only on Linux hosts.");
        }
        HostArchitecture architecture = detectArchitecture();
        if (!architecture.supported()) {
            throw new IOException("Unsupported host architecture: " + architecture.prootArchitecture() + ". MJT supports native x86_64 and aarch64 only.");
        }
    }

    private boolean isLinuxHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
    }

    private boolean isEnvironmentReady(String name) {
        try {
            return isRootfsReady(rootfsPath(validateEnvironmentName(name)));
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean isRootfsReady(Path rootfs) {
        return Files.isDirectory(rootfs)
                && Files.isRegularFile(rootfs.resolve("bin/sh"))
                && Files.isDirectory(rootfs.resolve("etc"))
                && Files.isDirectory(rootfs.resolve("usr"));
    }

    private void createServiceStartPolicy(Path rootfs) throws IOException {
        // Debian/Ubuntu images should never start host-visible daemons during apt install.
        if (!Files.isRegularFile(rootfs.resolve("usr/bin/apt-get"))) {
            return;
        }
        Path policy = rootfs.resolve("usr/sbin/policy-rc.d");
        Files.createDirectories(policy.getParent());
        Files.writeString(policy,
                "#!/bin/sh\n# MJT guest policy: package installs must not auto-start daemons.\nexit 101\n",
                StandardCharsets.UTF_8);
        try {
            Set<PosixFilePermission> permissions = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            );
            Files.setPosixFilePermissions(policy, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Target deployments are Linux; this keeps local source review portable.
        }
    }

    private String validateEnvironmentName(String raw) throws IOException {
        String name = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!name.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
            throw new IOException("Environment name must use lowercase letters, digits, dot, underscore or hyphen.");
        }
        return name;
    }

    private Path engineRoot() {
        return stateStore.getConfigDir().resolve("system/proot-distro").toAbsolutePath().normalize();
    }

    private Path venvRoot() {
        return engineRoot().resolve("venv");
    }

    private Path venvPython() {
        return venvRoot().resolve("bin/python");
    }

    /** Returns the selected engine path for API/status output. */
    private Path engineExecutable() {
        Path engine = resolveUpstreamEngine();
        return engine == null ? localVenvEngine() : engine;
    }

    private Path localVenvEngine() {
        return venvRoot().resolve("bin/proot-distro");
    }

    /**
     * Resolution order: explicit MJT configuration, host-image upstream CLI,
     * then the isolated venv fallback. This lets a small MJT host image own
     * Python/PRoot/proot-distro while keeping all containers and cache inside
     * MJT-controlled XDG locations.
     */
    private Path resolveUpstreamEngine() {
        String configured = stateStore.get(KEY_ENGINE_EXECUTABLE, "").trim();
        if (!configured.isBlank()) {
            Path path = Paths.get(configured).toAbsolutePath().normalize();
            if (isExecutable(path)) {
                return path;
            }
        }
        Path host = findExecutableOnPath("proot-distro");
        if (host != null) {
            return host;
        }
        Path local = localVenvEngine();
        return isExecutable(local) ? local : null;
    }

    private boolean canRun(Path executable, String argument, long timeoutSeconds) {
        if (!isExecutable(executable)) {
            return false;
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), argument);
            builder.redirectErrorStream(true);
            configureEnvironment(builder.environment());
            Process process = builder.start();
            boolean complete = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!complete) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String readVersion(Path executable) {
        if (!isExecutable(executable)) {
            return "";
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "--version");
            builder.redirectErrorStream(true);
            configureEnvironment(builder.environment());
            Process process = builder.start();
            String line;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                line = reader.readLine();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return "";
            }
            return line == null ? "" : line.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private Path xdgDataHome() {
        return engineRoot().resolve("data");
    }

    private Path xdgCacheHome() {
        return engineRoot().resolve("cache");
    }

    private Path runtimeRoot() {
        return xdgDataHome().resolve("proot-distro");
    }

    private Path cacheRoot() {
        return xdgCacheHome().resolve("proot-distro");
    }

    private Path containersRoot() {
        return runtimeRoot().resolve("containers");
    }

    private Path rootfsPath(String name) {
        return containersRoot().resolve(name).resolve("rootfs");
    }

    private static boolean isExecutable(Path path) {
        return path != null && Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private static String blankAsDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private static String redact(List<String> command) {
        return String.join(" ", command).replaceAll("(?i)(token|password|secret)=\\S+", "$1=***");
    }

    private static String messageOf(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private void log(String line) throws IOException {
        logService.write(line);
    }

    private void logQuietly(String line) {
        try {
            log(line);
        } catch (IOException ignored) {
        }
    }

    private record HostArchitecture(String prootArchitecture, String displayArchitecture, boolean supported) {
    }

    @FunctionalInterface
    private interface JobAction {
        void run(DistroJob job) throws Exception;
    }

    private static final class DistroJob {
        private final String id = UUID.randomUUID().toString().replace("-", "");
        private final String type;
        private final String target;
        private final String createdAt = Instant.now().toString();
        private final List<String> logs = new ArrayList<>();
        private volatile String state = "queued";
        private volatile String message = "Queued";
        private volatile String startedAt = "";
        private volatile String finishedAt = "";

        private DistroJob(String type, String target) {
            this.type = type;
            this.target = target;
        }

        private synchronized void log(String line) {
            if (line == null) return;
            logs.add(line);
            while (logs.size() > MAX_JOB_LOG_LINES) {
                logs.remove(0);
            }
        }

        private void start() {
            state = "running";
            message = "Running";
            startedAt = Instant.now().toString();
        }

        private void succeed() {
            state = "succeeded";
            message = "Completed";
            finishedAt = Instant.now().toString();
        }

        private void fail(String reason) {
            state = "failed";
            message = reason;
            finishedAt = Instant.now().toString();
        }

        private synchronized JobInfo snapshot() {
            return new JobInfo(id, type, target, state, message, createdAt, startedAt, finishedAt, List.copyOf(logs));
        }
    }
}
