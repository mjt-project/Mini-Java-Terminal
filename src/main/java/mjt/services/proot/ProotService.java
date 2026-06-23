package main.java.mjt.services.proot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

/**
 * Executes user workloads inside the MJT PRoot guest filesystem.
 *
 * <p>Important boundary: this service never downloads a distro image and never
 * installs packages on the host. The operator supplies a PRoot binary and a
 * bootstrapped Debian/Ubuntu rootfs. Every command launched through this class
 * sees the rootfs as / and the configured host workspace as /workspace.</p>
 */
public final class ProotService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static final String KEY_ENABLED = "proot.enabled";
    private static final String KEY_BINARY = "proot.binary";
    private static final String KEY_ROOTFS = "proot.rootfs";
    private static final String KEY_WORKSPACE = "proot.workspace";
    private static final String KEY_GUEST_WORKSPACE = "proot.guestWorkspace";
    private static final String KEY_SHELL_ROUTING = "proot.shellRouting";
    private static final String KEY_EXEC_TIMEOUT = "proot.execTimeoutSeconds";

    private final StateStore stateStore;
    private final LogService logService;
    private final ProotDistroService distroService;

    /** Legacy-compatible constructor. New Core wiring should share one adapter instance. */
    public ProotService(StateStore stateStore, LogService logService) {
        this(stateStore, logService, new ProotDistroService(stateStore, logService));
    }

    public ProotService(StateStore stateStore, LogService logService, ProotDistroService distroService) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.distroService = distroService;
    }

    /** Creates only MJT-owned directories and the service policy file. */
    public synchronized void initialize() {
        try {
            Path mjtHome = stateStore.getConfigDir().toAbsolutePath().normalize();
            Path binaryParent = getBinaryPath().getParent();
            Path rootfs = getRootfsPath();

            if (binaryParent != null) {
                Files.createDirectories(binaryParent);
            }
            Files.createDirectories(mjtHome.resolve("system"));
            Files.createDirectories(mjtHome.resolve("runtime/proot"));
            Files.createDirectories(rootfs);
            distroService.initializeDirectories();

            if (distroService.isActiveAndReady()) {
                System.out.println(GREEN + "[PRoot] Active upstream environment is ready: " + distroService.getActiveEnvironment() + RESET);
            } else if (isBootstrappedRootfs(rootfs)) {
                createServiceStartPolicy(rootfs);
                System.out.println(GREEN + "[PRoot] Runtime directories are ready." + RESET);
                System.out.println(GREEN + "[PRoot] Rootfs validation passed: " + rootfs + RESET);
            } else {
                System.out.println(YELLOW + "[PRoot] Runtime directories are ready, but rootfs is not bootstrapped yet." + RESET);
                System.out.println(YELLOW + "[PRoot] Expected at least /bin/sh, /etc and /usr inside: " + rootfs + RESET);
            }

            log("[PROOT INIT] rootfs=" + rootfs + "\n");
        } catch (Exception e) {
            System.out.println(RED + "[PRoot] Init error: " + e.getMessage() + RESET);
            logQuietly("[PROOT INIT ERROR] " + e.getMessage() + "\n");
        }
    }

    public synchronized void showConfig() {
        Path binary = getBinaryPath();
        Path rootfs = getRootfsPath();
        Path workspace = getWorkspacePath();

        System.out.println(CYAN + "[MJT PROOT]" + RESET);
        System.out.println("Enabled        : " + isEnabled());
        System.out.println("Shell routing  : " + isShellRoutingEnabled());
        System.out.println("Binary         : " + binary);
        System.out.println("Binary ready   : " + isExecutable(binary));
        System.out.println("Rootfs         : " + rootfs);
        System.out.println("Rootfs ready   : " + isBootstrappedRootfs(rootfs));
        System.out.println("Host workspace : " + workspace);
        System.out.println("Guest workspace: " + getGuestWorkspace());
        System.out.println("Workspace ready: " + Files.isDirectory(workspace));
        System.out.println("Exec timeout   : " + getExecTimeoutSeconds() + " seconds (0 = unlimited)");
        System.out.println("Policy rc.d    : " + rootfs.resolve("usr/sbin/policy-rc.d"));
        System.out.println();
        if (distroService.isActiveAndReady()) {
            System.out.println("Runtime mode   : upstream proot-distro");
            System.out.println("Environment    : " + distroService.getActiveEnvironment());
        } else {
            System.out.println("Runtime mode   : legacy manual rootfs");
        }
        System.out.println("Guest packages live below: " + rootfs);
        System.out.println("Example: .mjt proot exec apt-get update");
        System.out.println("Example: .mjt proot exec apk update");
        System.out.println("Distro engine : .mjt proot distro engine install");
    }

    public synchronized void test() {
        if (!ensureReadyForExec()) {
            return;
        }
        execute("id && if command -v apt-get >/dev/null 2>&1; then apt-get --version; elif command -v apk >/dev/null 2>&1; then apk --version; else echo 'No supported package manager found'; exit 127; fi", true);
    }

    public synchronized void execute(String shellCommand) {
        execute(shellCommand, false);
    }

    private void execute(String shellCommand, boolean testMode) {
        String command = shellCommand == null ? "" : shellCommand.trim();
        if (command.isBlank()) {
            System.out.println(RED + "Usage: .mjt proot exec <command>" + RESET);
            return;
        }
        if (!ensureReadyForExec()) {
            return;
        }

        List<String> processCommand;
        try {
            processCommand = buildGuestCommand(command);
        } catch (IOException e) {
            System.out.println(RED + "[PRoot] Cannot build command: " + e.getMessage() + RESET);
            return;
        }

        Instant startedAt = Instant.now();
        try {
            log("[PROOT EXEC] " + command + "\n");
            ProcessBuilder builder = new ProcessBuilder(processCommand);
            builder.redirectErrorStream(true);
            Process process = builder.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    log("[PROOT OUTPUT] " + line + "\n");
                }
            }

            boolean finished = waitFor(process, getExecTimeoutSeconds());
            if (!finished) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                System.out.println(RED + "[PRoot] Command timed out and was terminated." + RESET);
                log("[PROOT TIMEOUT] " + command + "\n");
                return;
            }

            int code = process.exitValue();
            long seconds = Duration.between(startedAt, Instant.now()).toSeconds();
            String color = code == 0 ? GREEN : YELLOW;
            System.out.println(color + "[PRoot] Exit code: " + code + " (" + seconds + "s)" + RESET);
            log("[PROOT EXIT] code=" + code + " command=" + command + "\n");

            if (testMode && code == 0) {
                System.out.println(GREEN + "[PRoot] Test passed. APT is available inside the guest rootfs." + RESET);
            }
        } catch (Exception e) {
            System.out.println(RED + "[PRoot] Exec error: " + e.getMessage() + RESET);
            logQuietly("[PROOT EXEC ERROR] " + e.getMessage() + "\n");
        }
    }

    /**
     * Builds a guest command rooted at the configured workspace root.
     */
    public synchronized List<String> buildGuestCommand(String shellCommand) throws IOException {
        return buildGuestCommand(shellCommand, getWorkspacePath());
    }

    /**
     * Builds a guest command that starts in a host directory below the managed
     * workspace. This is used by managed services such as Minecraft and
     * OpenVSCode so their files stay on the host workspace while their runtime
     * binaries and packages remain inside the PRootFS.
     */
    public synchronized List<String> buildGuestCommand(String shellCommand, Path hostWorkingDirectory) throws IOException {
        validateGuestPaths();
        if (distroService.isActiveAndReady()) {
            return distroService.buildGuestCommand(
                    shellCommand,
                    hostWorkingDirectory,
                    getWorkspacePath(),
                    getGuestWorkspace()
            );
        }
        String guestWorkingDirectory = toGuestPath(hostWorkingDirectory);

        List<String> command = new ArrayList<>();
        command.add(getBinaryPath().toString());
        command.add("-S");
        command.add(getRootfsPath().toString());
        command.add("-b");
        command.add(getWorkspacePath() + ":" + getGuestWorkspace());
        command.add("-w");
        command.add(guestWorkingDirectory);
        command.add("/usr/bin/env");
        command.add("-i");
        command.add("HOME=/root");
        command.add("USER=root");
        command.add("TERM=xterm-256color");
        command.add("MJT_WORKSPACE=" + getGuestWorkspace());
        command.add("MJT_GUEST_CWD=" + guestWorkingDirectory);
        command.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        command.add("/bin/bash");
        command.add("-lc");
        command.add(shellCommand == null ? "" : shellCommand);
        return command;
    }

    /**
     * Converts a host path below {@code proot.workspace} to its guest-visible
     * path below {@code proot.guestWorkspace}. Paths outside the workspace are
     * rejected so a service cannot accidentally bind unrelated host folders.
     */
    public synchronized String toGuestPath(Path hostPath) throws IOException {
        Path workspace = getWorkspacePath();
        Path resolved = (hostPath == null ? workspace : hostPath)
                .toAbsolutePath()
                .normalize();
        if (!resolved.startsWith(workspace)) {
            throw new IOException("Host working directory must stay inside proot.workspace: " + resolved);
        }
        Path relative = workspace.relativize(resolved);
        String guestBase = getGuestWorkspace();
        if (relative.getNameCount() == 0) {
            return guestBase;
        }
        return guestBase + "/" + relative.toString().replace('\\', '/');
    }

    public synchronized boolean isShellRoutingEnabled() {
        return stateStore.getBoolean(KEY_SHELL_ROUTING, false);
    }

    public synchronized boolean isEnabled() {
        return stateStore.getBoolean(KEY_ENABLED, true);
    }

    public synchronized void setConfig(String rawKey, String rawValue) throws IOException {
        String key = normalizeKey(rawKey);
        if (key == null) {
            System.out.println(RED + "[PRoot] Invalid key: " + rawKey + RESET);
            printSetHelp();
            return;
        }

        String value = rawValue == null ? "" : rawValue.trim();
        switch (key) {
            case KEY_ENABLED:
            case KEY_SHELL_ROUTING:
                value = normalizeBoolean(value);
                break;
            case KEY_EXEC_TIMEOUT:
                int seconds = parseNonNegativeInteger(value, -1);
                if (seconds < 0) {
                    System.out.println(RED + "[PRoot] exec-timeout must be a non-negative integer." + RESET);
                    return;
                }
                value = String.valueOf(seconds);
                break;
            case KEY_BINARY:
            case KEY_ROOTFS:
            case KEY_WORKSPACE:
                if (value.isBlank()) {
                    System.out.println(RED + "[PRoot] Path value cannot be empty." + RESET);
                    return;
                }
                value = Paths.get(value).toAbsolutePath().normalize().toString();
                break;
            case KEY_GUEST_WORKSPACE:
                if (!isSafeGuestPath(value)) {
                    System.out.println(RED + "[PRoot] guest-workspace must be an absolute guest path without '..'." + RESET);
                    return;
                }
                break;
            default:
                break;
        }

        stateStore.set(key, value);
        System.out.println(GREEN + "[PRoot] Saved " + key + " = " + value + RESET);
        log("[PROOT SET] " + key + "\n");
    }

    public synchronized void enterGuestRouting() {
        if (!ensureReadyForExec()) {
            return;
        }
        try {
            stateStore.set(KEY_SHELL_ROUTING, "true");
            System.out.println(GREEN + "[PRoot] Guest shell routing enabled." + RESET);
            System.out.println("Use .command <command> to run commands inside the PRootFS.");
            System.out.println("Use .mjt proot leave to return .command to the host shell.");
        } catch (IOException e) {
            System.out.println(RED + "[PRoot] Cannot enable guest routing: " + e.getMessage() + RESET);
        }
    }

    public synchronized void leaveGuestRouting() {
        try {
            stateStore.set(KEY_SHELL_ROUTING, "false");
            System.out.println(YELLOW + "[PRoot] Guest shell routing disabled. .command now uses the host shell." + RESET);
        } catch (IOException e) {
            System.out.println(RED + "[PRoot] Cannot disable guest routing: " + e.getMessage() + RESET);
        }
    }

    private boolean ensureReadyForExec() {
        if (!isEnabled()) {
            System.out.println(YELLOW + "[PRoot] Disabled. Use: .mjt proot set enabled true" + RESET);
            return false;
        }
        try {
            validateGuestPaths();
            return true;
        } catch (IOException e) {
            System.out.println(RED + "[PRoot] Not ready: " + e.getMessage() + RESET);
            System.out.println(YELLOW + "[PRoot] Run: .mjt proot init, then configure a real PRoot binary and bootstrapped rootfs." + RESET);
            return false;
        }
    }

    private void validateGuestPaths() throws IOException {
        Path workspace = getWorkspacePath();
        if (distroService.isActiveAndReady()) {
            distroService.validateActiveRuntime();
            if (!Files.isDirectory(workspace)) {
                throw new IOException("Workspace directory does not exist: " + workspace);
            }
            if (!isSafeGuestPath(getGuestWorkspace())) {
                throw new IOException("Invalid guest workspace: " + getGuestWorkspace());
            }
            return;
        }

        Path binary = getBinaryPath();
        Path rootfs = getRootfsPath();

        if (!isExecutable(binary)) {
            throw new IOException("PRoot binary is missing or not executable: " + binary);
        }
        if (!isBootstrappedRootfs(rootfs)) {
            throw new IOException("Rootfs is not bootstrapped: " + rootfs);
        }
        if (!Files.isDirectory(workspace)) {
            throw new IOException("Workspace directory does not exist: " + workspace);
        }
        if (!isSafeGuestPath(getGuestWorkspace())) {
            throw new IOException("Invalid guest workspace: " + getGuestWorkspace());
        }
    }

    private boolean isBootstrappedRootfs(Path rootfs) {
        return Files.isDirectory(rootfs)
                && Files.isRegularFile(rootfs.resolve("bin/sh"))
                && Files.isDirectory(rootfs.resolve("etc"))
                && Files.isDirectory(rootfs.resolve("usr"));
    }

    private boolean isExecutable(Path path) {
        return Files.isRegularFile(path) && Files.isExecutable(path);
    }

    private void createServiceStartPolicy(Path rootfs) throws IOException {
        if (!Files.isRegularFile(rootfs.resolve("usr/bin/apt-get"))) {
            return;
        }
        Path policy = rootfs.resolve("usr/sbin/policy-rc.d");
        Files.createDirectories(policy.getParent());
        Files.writeString(policy, "#!/bin/sh\n# MJT guest policy: packages must not auto-start host-visible daemons.\nexit 101\n", StandardCharsets.UTF_8);
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
            // Non-POSIX hosts are only for local development; the target host is Linux.
        }
    }

    private boolean waitFor(Process process, int timeoutSeconds) throws InterruptedException {
        if (timeoutSeconds == 0) {
            process.waitFor();
            return true;
        }
        return process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    }

    private Path getBinaryPath() {
        String configured = stateStore.get(KEY_BINARY, "").trim();
        if (!configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return stateStore.getConfigDir().resolve("system/bin/proot").toAbsolutePath().normalize();
    }

    private Path getRootfsPath() {
        Path activeRootfs = distroService.activeRootfs();
        if (activeRootfs != null) {
            return activeRootfs.toAbsolutePath().normalize();
        }
        String configured = stateStore.get(KEY_ROOTFS, "").trim();
        if (!configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        return stateStore.getConfigDir().resolve("system/rootfs").toAbsolutePath().normalize();
    }

    private Path getWorkspacePath() {
        String configured = stateStore.get(KEY_WORKSPACE, "").trim();
        if (!configured.isBlank()) {
            return Paths.get(configured).toAbsolutePath().normalize();
        }
        String registryRoot = stateStore.get("workspace.root", "/home/container/server").trim();
        return Paths.get(registryRoot.isBlank() ? "/home/container/server" : registryRoot)
                .toAbsolutePath()
                .normalize();
    }

    private String getGuestWorkspace() {
        String configured = stateStore.get(KEY_GUEST_WORKSPACE, "/workspace").trim();
        return configured.isBlank() ? "/workspace" : configured;
    }

    private int getExecTimeoutSeconds() {
        return Math.max(0, stateStore.getInt(KEY_EXEC_TIMEOUT, 0));
    }

    private String normalizeKey(String rawKey) {
        if (rawKey == null) {
            return null;
        }
        String key = rawKey.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "enabled":
            case "enable":
            case "proot.enabled":
                return KEY_ENABLED;
            case "binary":
            case "bin":
            case "proot.binary":
                return KEY_BINARY;
            case "rootfs":
            case "root":
            case "proot.rootfs":
                return KEY_ROOTFS;
            case "workspace":
            case "host-workspace":
            case "proot.workspace":
                return KEY_WORKSPACE;
            case "guest-workspace":
            case "guestworkspace":
            case "proot.guestworkspace":
                return KEY_GUEST_WORKSPACE;
            case "shell-routing":
            case "shellrouting":
            case "route-shell":
            case "proot.shellrouting":
                return KEY_SHELL_ROUTING;
            case "exec-timeout":
            case "timeout":
            case "proot.exectimeoutseconds":
                return KEY_EXEC_TIMEOUT;
            default:
                return null;
        }
    }

    private boolean isSafeGuestPath(String path) {
        if (path == null || path.isBlank() || !path.startsWith("/")) {
            return false;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return false;
            }
        }
        return true;
    }

    private String normalizeBoolean(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return value.equals("true") || value.equals("yes") || value.equals("1")
                || value.equals("on") || value.equals("enable") || value.equals("enabled")
                ? "true" : "false";
    }

    private int parseNonNegativeInteger(String raw, int defaultValue) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value >= 0 ? value : defaultValue;
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void printSetHelp() {
        System.out.println(YELLOW + "Valid PRoot keys:" + RESET);
        System.out.println(".mjt proot set enabled true");
        System.out.println(".mjt proot set binary /home/container/MJT/system/bin/proot");
        System.out.println(".mjt proot set rootfs /home/container/MJT/system/rootfs");
        System.out.println(".mjt proot set workspace /home/container/server");
        System.out.println(".mjt proot set guest-workspace /workspace");
        System.out.println(".mjt proot set shell-routing true");
        System.out.println(".mjt proot set exec-timeout 0");
    }

    private void log(String value) throws IOException {
        logService.write(value);
    }

    private void logQuietly(String value) {
        try {
            log(value);
        } catch (IOException ignored) {
        }
    }
}
