package main.java.mjt.services.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import main.java.mjt.services.cloudflare.tunnel.CloudflareTunnelService;
import main.java.mjt.services.proot.ProotService;
import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

/**
 * Manages user workloads that execute inside the MJT PRoot guest.
 *
 * <p>Host processes are deliberately limited to the control plane. Guest
 * service commands, package runtimes and package state stay inside PRootFS;
 * only folders under {@code proot.workspace} are bind-mounted into the guest.
 * A service is allowed to publish an HTTP origin only when it binds loopback.</p>
 */
public final class GuestServiceManager {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static final int MAX_LOG_LINES = 2_000;
    private static final int PORT_PROBE_TIMEOUT_MILLIS = 250;
    private static final int RESTART_STABLE_SECONDS = 300;
    private static final String SERVICES_KEY = "guest.services";
    private static final String SERVICE_PREFIX = "guest.service.";

    private final StateStore stateStore;
    private final LogService logService;
    private final ProotService prootService;
    private final CloudflareTunnelService cloudflareTunnelService;
    private final Map<String, ManagedGuestService> processes = new LinkedHashMap<>();
    private final ScheduledExecutorService lifecycleExecutor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mjt-guest-service-lifecycle");
        thread.setDaemon(true);
        return thread;
    });

    public GuestServiceManager(
            StateStore stateStore,
            LogService logService,
            ProotService prootService,
            CloudflareTunnelService cloudflareTunnelService
    ) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.prootService = prootService;
        this.cloudflareTunnelService = cloudflareTunnelService;
    }

    /** Usage: .mjt service add <id> <type> <workdir> <command...> */
    public synchronized void addService(String raw) throws IOException {
        String[] parts = raw == null ? new String[0] : raw.trim().split("\\s+", 4);
        if (parts.length < 4) {
            System.out.println(RED + "Usage: .mjt service add <id> <type> <workdir> <command...>" + RESET);
            System.out.println("Example: .mjt service add api node /home/container/server/api npm run start");
            return;
        }

        String id = requireServiceId(parts[0]);
        String type = normalizeType(parts[1]);
        Path workdir = normalizeWorkspacePath(parts[2]);
        String command = parts[3].trim();
        if (command.isBlank()) {
            throw new IOException("Service command cannot be empty.");
        }

        List<String> ids = getServiceIds();
        if (!ids.contains(id)) {
            ids.add(id);
            saveServiceIds(ids);
        }

        stateStore.set(key(id, "enabled"), "true");
        stateStore.set(key(id, "type"), type);
        stateStore.set(key(id, "runtime"), "proot");
        stateStore.set(key(id, "workdir"), workdir.toString());
        stateStore.set(key(id, "command"), command);
        stateStore.set(key(id, "host"), "127.0.0.1");
        stateStore.set(key(id, "port"), "0");
        stateStore.set(key(id, "public.enabled"), "false");
        stateStore.set(key(id, "public.hostname"), "");

        // Conservative lifecycle defaults. Services never restart or start on boot
        // unless an operator explicitly asks for that behaviour.
        stateStore.set(key(id, "autostart"), "false");
        stateStore.set(key(id, "restart.policy"), "never");
        stateStore.set(key(id, "restart.max-attempts"), "3");
        stateStore.set(key(id, "restart.delay-seconds"), "5");
        stateStore.set(key(id, "health.enabled"), "false");
        stateStore.set(key(id, "health.path"), "/");
        stateStore.set(key(id, "health.interval-seconds"), "30");
        stateStore.set(key(id, "health.timeout-millis"), "3000");
        stateStore.set(key(id, "health.failure-threshold"), "3");
        stateStore.set(key(id, "health.action"), "report");
        clearRuntimeState(id);

        System.out.println(GREEN + "[Service] Added " + id + " -> " + type + " (PRoot)" + RESET);
        System.out.println("Workdir: " + workdir);
        System.out.println("Lifecycle: autostart=off, restart=never, health=off");
        log("[SERVICE ADD] " + id + " type=" + type + " workdir=" + workdir + "\n");
    }

    public synchronized void removeService(String rawId) throws IOException {
        String id = requireServiceId(rawId);
        stopServiceInternal(id, false);

        List<String> ids = getServiceIds();
        if (!ids.remove(id)) {
            System.out.println(YELLOW + "[Service] Not found: " + id + RESET);
            return;
        }
        saveServiceIds(ids);

        for (String suffix : List.of(
                "enabled", "type", "runtime", "workdir", "command", "host", "port",
                "public.enabled", "public.hostname", "autostart",
                "restart.policy", "restart.max-attempts", "restart.delay-seconds",
                "health.enabled", "health.path", "health.interval-seconds", "health.timeout-millis",
                "health.failure-threshold", "health.action",
                "runtime.last-started-at", "runtime.last-exited-at", "runtime.last-exit-code",
                "runtime.restart-attempts", "runtime.restart-count", "runtime.last-restart-reason",
                "runtime.health-status", "runtime.health-detail", "runtime.health-failures"
        )) {
            stateStore.remove(key(id, suffix));
        }
        processes.remove(id);
        System.out.println(GREEN + "[Service] Removed registry entry: " + id + RESET);
        System.out.println(YELLOW + "[Service] Workspace files were kept." + RESET);
        log("[SERVICE REMOVE] " + id + "\n");
    }

    public synchronized void startService(String rawId) throws IOException {
        String id = requireServiceId(rawId);
        if (id.equals("all")) {
            for (String serviceId : getServiceIds()) {
                startServiceInternal(serviceId, false, "manual");
            }
            return;
        }
        startServiceInternal(id, false, "manual");
    }

    /** Starts only services that have an explicit autostart=true setting. */
    public synchronized void startAutoStartServices() {
        List<String> ids = getServiceIds();
        int requested = 0;
        for (String id : ids) {
            if (!stateStore.getBoolean(key(id, "autostart"), false)) {
                continue;
            }
            requested++;
            try {
                startServiceInternal(id, true, "boot");
            } catch (Exception e) {
                System.out.println(RED + "[Service] Boot start failed for " + id + ": " + e.getMessage() + RESET);
                logQuietly("[SERVICE BOOT START ERROR] " + id + " " + e.getMessage() + "\n");
            }
        }
        if (requested > 0) {
            System.out.println(CYAN + "[Service] Boot lifecycle processed " + requested + " autostart service(s)." + RESET);
        }
    }

    public synchronized void stopService(String rawId) {
        try {
            stopServiceInternal(requireServiceId(rawId), true);
        } catch (IOException e) {
            System.out.println(RED + "[Service] " + e.getMessage() + RESET);
        }
    }

    public synchronized void restartService(String rawId) throws IOException {
        String id = requireServiceId(rawId);
        if (id.equals("all")) {
            for (String serviceId : getServiceIds()) {
                restartServiceInternal(serviceId, "manual");
            }
            return;
        }
        restartServiceInternal(id, "manual");
    }

    public synchronized void sendLine(String rawId, String line) {
        try {
            String id = requireServiceId(rawId);
            ManagedGuestService service = processes.get(id);
            if (service == null || !service.isRunning()) {
                System.out.println(YELLOW + "[Service] Not running: " + id + RESET);
                return;
            }
            service.input.write(line == null ? "" : line);
            service.input.newLine();
            service.input.flush();
            service.addLine("> " + line);
        } catch (Exception e) {
            System.out.println(RED + "[Service] Cannot write stdin: " + e.getMessage() + RESET);
        }
    }

    /** Usage: .mjt service set <id> <key> <value> */
    public synchronized void setServiceConfig(String raw) throws IOException {
        String[] parts = raw == null ? new String[0] : raw.trim().split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println(RED + "Usage: .mjt service set <id> <key> <value>" + RESET);
            return;
        }
        String id = requireServiceId(parts[0]);
        if (!getServiceIds().contains(id)) {
            throw new IOException("Service not found: " + id);
        }
        String configKey = normalizeConfigKey(parts[1]);
        if (configKey == null) {
            System.out.println(RED + "[Service] Invalid key: " + parts[1] + RESET);
            printSetHelp();
            return;
        }
        if (isRunning(id) && requiresStopBeforeChange(configKey)) {
            throw new IOException("Stop the service before changing " + configKey + ".");
        }

        String value = parts[2].trim();
        switch (configKey) {
            case "enabled":
            case "public.enabled":
            case "autostart":
            case "health.enabled":
                value = normalizeBoolean(value);
                break;
            case "type":
                value = normalizeType(value);
                break;
            case "runtime":
                if (!value.equalsIgnoreCase("proot")) {
                    throw new IOException("Guest services support only runtime=proot.");
                }
                value = "proot";
                break;
            case "workdir":
                value = normalizeWorkspacePath(value).toString();
                break;
            case "command":
                if (value.isBlank()) throw new IOException("Service command cannot be empty.");
                break;
            case "host":
                if (!isLoopback(value)) throw new IOException("Service host must stay on 127.0.0.1, ::1, or localhost.");
                value = value.toLowerCase(Locale.ROOT);
                break;
            case "port":
                value = String.valueOf(parsePort(value, true));
                break;
            case "public.hostname":
                if (!isSafeHostname(value)) throw new IOException("Public hostname must be a valid DNS hostname, without a URL or path.");
                value = value.toLowerCase(Locale.ROOT);
                break;
            case "restart.policy":
                value = normalizeRestartPolicy(value);
                break;
            case "restart.max-attempts":
                value = String.valueOf(parseBoundedInt(value, 0, 100, "restart max attempts"));
                break;
            case "restart.delay-seconds":
                value = String.valueOf(parseBoundedInt(value, 0, 3600, "restart delay seconds"));
                break;
            case "health.path":
                value = normalizeHealthPath(value);
                break;
            case "health.interval-seconds":
                value = String.valueOf(parseBoundedInt(value, 5, 3600, "health interval seconds"));
                break;
            case "health.timeout-millis":
                value = String.valueOf(parseBoundedInt(value, 250, 30_000, "health timeout milliseconds"));
                break;
            case "health.failure-threshold":
                value = String.valueOf(parseBoundedInt(value, 1, 100, "health failure threshold"));
                break;
            case "health.action":
                value = normalizeHealthAction(value);
                break;
            default:
                throw new IOException("Unsupported service key: " + configKey);
        }

        stateStore.set(key(id, configKey), value);
        ManagedGuestService current = processes.get(id);
        if (current != null && (configKey.startsWith("health.") || configKey.equals("port") || configKey.equals("host"))) {
            refreshHealthSchedule(current);
        }
        System.out.println(GREEN + "[Service] Saved " + key(id, configKey) + " = " + value + RESET);
        log("[SERVICE SET] " + id + " " + configKey + "\n");
    }

    /**
     * Adds/updates an HTTP ingress rule in the Cloudflare Tunnel configuration.
     * It never starts a tunnel automatically because token and config modes have
     * different ownership and credential rules.
     */
    public synchronized void publishService(String rawId) throws IOException {
        String id = requireServiceId(rawId);
        ServiceConfig config = readConfig(id);
        if (config.port <= 0) {
            throw new IOException("Set a local service port before publishing: .mjt service set " + id + " port <port>");
        }
        if (!isSafeHostname(config.publicHostname)) {
            throw new IOException("Set a public hostname first: .mjt service set " + id + " public-hostname app.example.com");
        }
        if (!isLoopback(config.host)) {
            throw new IOException("Only loopback service origins may be published.");
        }

        String routeName = tunnelRouteName(id);
        String origin = "http://" + config.host + ":" + config.port;
        cloudflareTunnelService.addRoute(routeName + " " + config.publicHostname + " " + origin);
        stateStore.set(key(id, "public.enabled"), "true");
        System.out.println(GREEN + "[Service] Tunnel route saved: " + routeName + " -> " + config.publicHostname + RESET);
        System.out.println(YELLOW + "[Service] In tunnel config mode, run .mjt tunnel config generate and restart the tunnel." + RESET);
        System.out.println(YELLOW + "[Service] In token mode, create the Public Hostname in Cloudflare Zero Trust using origin " + origin + "." + RESET);
        log("[SERVICE PUBLISH] " + id + " hostname=" + config.publicHostname + " origin=" + origin + "\n");
    }

    public synchronized void unpublishService(String rawId) throws IOException {
        String id = requireServiceId(rawId);
        cloudflareTunnelService.removeRoute(tunnelRouteName(id));
        stateStore.set(key(id, "public.enabled"), "false");
        System.out.println(YELLOW + "[Service] Tunnel route removed: " + tunnelRouteName(id) + RESET);
        log("[SERVICE UNPUBLISH] " + id + "\n");
    }

    public synchronized void listServices() {
        System.out.println(CYAN + "[MJT GUEST SERVICES]" + RESET);
        List<String> ids = getServiceIds();
        if (ids.isEmpty()) {
            System.out.println("No guest services registered.");
            System.out.println("Add one: .mjt service add api node /home/container/server/api npm run start");
            return;
        }
        for (String id : ids) {
            printServiceLine(id);
        }
    }

    public synchronized void showService(String rawId) {
        try {
            String id = requireServiceId(rawId);
            ServiceConfig config = readConfig(id);
            ManagedGuestService managed = processes.get(id);
            System.out.println(CYAN + "[MJT GUEST SERVICE] " + id + RESET);
            System.out.println("Running         : " + (managed != null && managed.isRunning()));
            System.out.println("Enabled         : " + config.enabled);
            System.out.println("Runtime         : proot");
            System.out.println("Type            : " + config.type);
            System.out.println("Workdir         : " + config.workdir);
            System.out.println("Command         : " + config.command);
            System.out.println("Local bind      : " + config.host + ":" + displayPort(config.port));
            System.out.println("Public enabled  : " + config.publicEnabled);
            System.out.println("Public hostname : " + (config.publicHostname.isBlank() ? "(not configured)" : config.publicHostname));
            System.out.println("Tunnel route    : " + tunnelRouteName(id));
            System.out.println("Autostart       : " + config.autostart);
            System.out.println("Restart policy  : " + config.restartPolicy + " (max=" + config.restartMaxAttempts + ", delay=" + config.restartDelaySeconds + "s)");
            System.out.println("Health check    : " + (config.healthEnabled
                    ? "ON " + config.healthPath + " every " + config.healthIntervalSeconds + "s; action=" + config.healthAction
                    : "OFF"));
            System.out.println("Health status   : " + runtimeValue(id, "health-status", "unknown"));
            System.out.println("Health detail   : " + runtimeValue(id, "health-detail", "n/a"));
            System.out.println("Health failures : " + runtimeValue(id, "health-failures", "0"));
            System.out.println("Uptime          : " + (managed == null ? "none" : formatDuration(managed.startedAt)));
            System.out.println("Exit code       : " + runtimeValue(id, "last-exit-code", managed == null || managed.lastExitCode == Integer.MIN_VALUE ? "n/a" : String.valueOf(managed.lastExitCode)));
            System.out.println("Restarts        : " + runtimeValue(id, "restart-count", "0") + " (current streak=" + runtimeValue(id, "restart-attempts", "0") + ")");
            System.out.println("Last reason     : " + runtimeValue(id, "last-restart-reason", "n/a"));
        } catch (Exception e) {
            System.out.println(RED + "[Service] " + e.getMessage() + RESET);
        }
    }

    /** Returns an immutable snapshot of in-memory output for the panel API. */
    public synchronized List<String> getRecentLogs(String rawId, int maxLines) throws IOException {
        String id = requireServiceId(rawId);
        ManagedGuestService service = processes.get(id);
        if (service == null) {
            return List.of();
        }
        return List.copyOf(service.getRecentLines(maxLines));
    }

    public synchronized void printLogs(String rawId, int maxLines) {
        try {
            String id = requireServiceId(rawId);
            ManagedGuestService service = processes.get(id);
            System.out.println(CYAN + "[SERVICE LOGS] " + id + RESET);
            if (service == null) {
                System.out.println("No in-memory logs. Start the service first.");
                return;
            }
            for (String line : service.getRecentLines(maxLines)) {
                System.out.println(line);
            }
        } catch (Exception e) {
            System.out.println(RED + "[Service] " + e.getMessage() + RESET);
        }
    }

    /** Runs one HTTP health check now, even when periodic checks are disabled. */
    public synchronized void runHealthCheck(String rawId) {
        try {
            String id = requireServiceId(rawId);
            ServiceConfig config = readConfig(id);
            if (config.port <= 0) {
                throw new IOException("Set a local port before running a health check.");
            }
            HealthResult result = probeHealth(config);
            applyHealthResult(id, processes.get(id), config, result, false);
            String color = result.ok ? GREEN : RED;
            System.out.println(color + "[Service] Health " + id + ": " + result.detail + RESET);
        } catch (Exception e) {
            System.out.println(RED + "[Service] Health check failed: " + e.getMessage() + RESET);
        }
    }

    public synchronized boolean isRunning(String rawId) {
        String id = normalizeServiceId(rawId);
        ManagedGuestService service = processes.get(id);
        return service != null && service.isRunning();
    }

    public synchronized void stopAll() {
        for (String id : new ArrayList<>(processes.keySet())) {
            stopServiceInternal(id, false);
        }
    }

    private void startServiceInternal(String id, boolean automatic, String reason) throws IOException {
        if (!getServiceIds().contains(id)) {
            throw new IOException("Service not found: " + id);
        }
        ManagedGuestService existing = processes.get(id);
        if (existing != null && existing.isRunning()) {
            System.out.println(YELLOW + "[Service] Already running: " + id + RESET);
            return;
        }
        if (!stateStore.getBoolean(key(id, "enabled"), true)) {
            System.out.println(YELLOW + "[Service] Disabled. Enable first: .mjt service set " + id + " enabled true" + RESET);
            return;
        }
        if (!prootService.isEnabled()) {
            System.out.println(YELLOW + "[Service] PRoot is disabled. Use: .mjt proot set enabled true" + RESET);
            return;
        }

        ServiceConfig config = readConfig(id);
        validatePortBeforeStart(id, config);
        Files.createDirectories(config.workdir);
        List<String> command = prootService.buildGuestCommand(buildLaunchCommand(config), config.workdir);

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(config.workdir.toFile());
        processBuilder.redirectErrorStream(true);
        Process process = processBuilder.start();
        BufferedWriter input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        int streak = automatic ? readRuntimeInt(id, "restart-attempts", 0) : 0;
        ManagedGuestService managed = new ManagedGuestService(config, process, input, streak);
        processes.put(id, managed);
        if (!automatic) {
            setRuntimeValue(id, "restart-attempts", "0");
        }
        setRuntimeValue(id, "last-started-at", Instant.now().toString());
        setRuntimeValue(id, "last-restart-reason", reason);
        setRuntimeValue(id, "health-status", config.healthEnabled && config.port > 0 ? "starting" : "disabled");
        setRuntimeValue(id, "health-detail", config.healthEnabled && config.port > 0 ? "Waiting for health check" : "Health checks disabled");
        setRuntimeValue(id, "health-failures", "0");

        Thread output = new Thread(() -> readOutput(managed), "mjt-service-output-" + safeThreadName(id));
        output.setDaemon(true);
        output.start();

        Thread watcher = new Thread(() -> waitForExit(managed), "mjt-service-watcher-" + safeThreadName(id));
        watcher.setDaemon(true);
        watcher.start();

        managed.addLine("[SERVICE] Started id=" + id + " runtime=proot reason=" + reason);
        managed.addLine("[SERVICE] Command: " + config.command);
        managed.addLine("[SERVICE] Workdir: " + config.workdir);
        scheduleHealthChecks(managed);

        String automaticText = automatic ? " automatically" : "";
        System.out.println(GREEN + "[Service] Started " + id + " in PRootFS" + automaticText + "." + RESET);
        System.out.println(CYAN + "[Service] " + config.type + " | " + config.host + ":" + displayPort(config.port) + RESET);
        log("[SERVICE START] " + id + " type=" + config.type + " reason=" + reason + " workdir=" + config.workdir + "\n");
    }

    private void stopServiceInternal(String id, boolean printMissing) {
        if (id.equals("all")) {
            for (String serviceId : new ArrayList<>(processes.keySet())) {
                stopServiceInternal(serviceId, false);
            }
            System.out.println(YELLOW + "[Service] Stop requested for all managed guest services." + RESET);
            return;
        }
        ManagedGuestService service = processes.get(id);
        if (service == null) {
            if (printMissing) System.out.println(YELLOW + "[Service] Not running: " + id + RESET);
            return;
        }
        service.stopRequested = true;
        cancelFuture(service.healthFuture);
        cancelFuture(service.restartFuture);
        if (service.isRunning()) {
            destroyProcessTree(service.process);
            service.addLine("[SERVICE] Stop requested.");
            System.out.println(YELLOW + "[Service] Stop requested: " + id + RESET);
            logQuietly("[SERVICE STOP] " + id + "\n");
        } else if (printMissing) {
            System.out.println(YELLOW + "[Service] Not running: " + id + RESET);
        }
    }

    private void restartServiceInternal(String id, String reason) throws IOException {
        ManagedGuestService current = processes.get(id);
        if (current != null) {
            current.stopRequested = true;
            cancelFuture(current.healthFuture);
            cancelFuture(current.restartFuture);
            if (current.isRunning()) {
                destroyProcessTree(current.process);
                waitForProcessStop(current.process, 2);
            }
        }
        startServiceInternal(id, false, reason);
    }

    private ServiceConfig readConfig(String id) throws IOException {
        if (!getServiceIds().contains(id)) {
            throw new IOException("Service not found: " + id);
        }
        String type = normalizeType(stateStore.get(key(id, "type"), "custom"));
        String runtime = stateStore.get(key(id, "runtime"), "proot").trim();
        if (!runtime.equalsIgnoreCase("proot")) {
            throw new IOException("Service " + id + " is not configured for PRoot.");
        }
        String command = stateStore.get(key(id, "command"), "").trim();
        if (command.isBlank()) throw new IOException("Service command is empty: " + id);
        Path workdir = normalizeWorkspacePath(stateStore.get(key(id, "workdir"), ""));
        String host = stateStore.get(key(id, "host"), "127.0.0.1").trim();
        if (!isLoopback(host)) throw new IOException("Service host must stay loopback: " + host);
        int port = parsePort(stateStore.get(key(id, "port"), "0"), true);
        String hostname = stateStore.get(key(id, "public.hostname"), "").trim();
        boolean publicEnabled = stateStore.getBoolean(key(id, "public.enabled"), false);
        boolean enabled = stateStore.getBoolean(key(id, "enabled"), true);
        boolean autostart = stateStore.getBoolean(key(id, "autostart"), false);
        String restartPolicy = normalizeRestartPolicy(stateStore.get(key(id, "restart.policy"), "never"));
        int restartMax = parseBoundedInt(stateStore.get(key(id, "restart.max-attempts"), "3"), 0, 100, "restart max attempts");
        int restartDelay = parseBoundedInt(stateStore.get(key(id, "restart.delay-seconds"), "5"), 0, 3600, "restart delay seconds");
        boolean healthEnabled = stateStore.getBoolean(key(id, "health.enabled"), false);
        String healthPath = normalizeHealthPath(stateStore.get(key(id, "health.path"), "/"));
        int healthInterval = parseBoundedInt(stateStore.get(key(id, "health.interval-seconds"), "30"), 5, 3600, "health interval seconds");
        int healthTimeout = parseBoundedInt(stateStore.get(key(id, "health.timeout-millis"), "3000"), 250, 30_000, "health timeout milliseconds");
        int healthThreshold = parseBoundedInt(stateStore.get(key(id, "health.failure-threshold"), "3"), 1, 100, "health failure threshold");
        String healthAction = normalizeHealthAction(stateStore.get(key(id, "health.action"), "report"));
        return new ServiceConfig(
                id, enabled, type, command, workdir, host, port, publicEnabled, hostname,
                autostart, restartPolicy, restartMax, restartDelay,
                healthEnabled, healthPath, healthInterval, healthTimeout, healthThreshold, healthAction
        );
    }

    private List<String> getServiceIds() {
        List<String> ids = new ArrayList<>();
        String raw = stateStore.get(SERVICES_KEY, "").trim();
        if (raw.isBlank()) return ids;
        for (String item : raw.split(",")) {
            String id = normalizeServiceId(item);
            if (!id.isBlank() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private void saveServiceIds(List<String> ids) throws IOException {
        stateStore.set(SERVICES_KEY, String.join(",", ids));
    }

    private String key(String id, String suffix) {
        return SERVICE_PREFIX + id + "." + suffix;
    }

    private String buildLaunchCommand(ServiceConfig config) throws IOException {
        String hostWorkspace = stateStore.get(
                "proot.workspace",
                stateStore.get("workspace.root", "/home/container/server")
        ).trim();
        if (hostWorkspace.isBlank()) hostWorkspace = "/home/container/server";
        String guestWorkspace = prootService.toGuestPath(Paths.get(hostWorkspace).toAbsolutePath().normalize());
        String guestWorkdir = prootService.toGuestPath(config.workdir);
        return "export MJT_SERVICE_ID=" + shellQuote(config.id)
                + " MJT_SERVICE_TYPE=" + shellQuote(config.type)
                + " MJT_SERVICE_HOST=" + shellQuote(config.host)
                + " MJT_SERVICE_PORT=" + shellQuote(String.valueOf(config.port))
                + " MJT_SERVICE_WORKSPACE=" + shellQuote(guestWorkspace)
                + " MJT_SERVICE_WORKDIR=" + shellQuote(guestWorkdir)
                + "; exec " + config.command;
    }

    private Path normalizeWorkspacePath(String rawPath) throws IOException {
        if (rawPath == null || rawPath.trim().isBlank()) {
            throw new IOException("Service workdir cannot be empty.");
        }
        Path path = Paths.get(rawPath.trim()).toAbsolutePath().normalize();
        prootService.toGuestPath(path);
        return path;
    }

    private void validatePortBeforeStart(String id, ServiceConfig config) throws IOException {
        if (config.port <= 0) return;
        for (Map.Entry<String, ManagedGuestService> entry : processes.entrySet()) {
            ManagedGuestService other = entry.getValue();
            if (!entry.getKey().equals(id) && other != null && other.isRunning()
                    && other.config.port == config.port && hostsOverlap(other.config.host, config.host)) {
                throw new IOException("Port " + config.port + " is already managed by service " + entry.getKey() + ".");
            }
        }
        if (isPortAcceptingConnections(config.host, config.port)) {
            throw new IOException("Port " + config.host + ":" + config.port + " is already in use. Stop its owner or choose another port.");
        }
    }

    private boolean isPortAcceptingConnections(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), PORT_PROBE_TIMEOUT_MILLIS);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean hostsOverlap(String a, String b) {
        String left = a == null ? "" : a.trim().toLowerCase(Locale.ROOT);
        String right = b == null ? "" : b.trim().toLowerCase(Locale.ROOT);
        return left.equals(right) || (isLoopback(left) && isLoopback(right));
    }

    private void readOutput(ManagedGuestService service) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(service.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(CYAN + "[Service:" + service.config.id + "] " + line + RESET);
                service.addLine(line);
                log("[SERVICE OUTPUT " + service.config.id + "] " + line + "\n");
            }
        } catch (IOException e) {
            logQuietly("[SERVICE OUTPUT ERROR " + service.config.id + "] " + e.getMessage() + "\n");
        }
    }

    private void waitForExit(ManagedGuestService service) {
        int exit = Integer.MIN_VALUE;
        try {
            exit = service.process.waitFor();
            service.lastExitCode = exit;
            service.addLine("[SERVICE] Exited with code: " + exit);
            System.out.println(YELLOW + "[Service] " + service.config.id + " exited with code: " + exit + RESET);
            logQuietly("[SERVICE EXIT] " + service.config.id + " code=" + exit + "\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try { service.input.close(); } catch (IOException ignored) { }
            synchronized (this) {
                cancelFuture(service.healthFuture);
                if (processes.get(service.config.id) == service) {
                    setRuntimeValue(service.config.id, "last-exited-at", Instant.now().toString());
                    setRuntimeValue(service.config.id, "last-exit-code", exit == Integer.MIN_VALUE ? "unknown" : String.valueOf(exit));
                    considerAutomaticRestart(service, exit);
                }
            }
        }
    }

    private void considerAutomaticRestart(ManagedGuestService service, int exit) {
        if (service.stopRequested || service.lifecycleRestartRequested) {
            return;
        }
        ServiceConfig config = service.config;
        boolean shouldRestart = config.restartPolicy.equals("always")
                || (config.restartPolicy.equals("on-failure") && exit != 0);
        if (!shouldRestart) {
            service.addLine("[SERVICE] Restart policy did not request a restart.");
            return;
        }

        int streak = service.restartAttempts;
        if (Duration.between(service.startedAt, Instant.now()).toSeconds() >= RESTART_STABLE_SECONDS) {
            streak = 0;
        }
        if (streak >= config.restartMaxAttempts) {
            service.addLine("[SERVICE] Restart limit reached (" + config.restartMaxAttempts + ").");
            setRuntimeValue(config.id, "last-restart-reason", "restart-limit-reached");
            logQuietly("[SERVICE RESTART LIMIT] " + config.id + "\n");
            return;
        }
        scheduleRestart(service, "exit-" + exit, config.restartDelaySeconds, streak + 1);
    }

    private void scheduleRestart(ManagedGuestService service, String reason, int delaySeconds, int nextStreak) {
        if (service.stopRequested || isPending(service.restartFuture)) return;
        service.lifecycleRestartRequested = true;
        service.restartAttempts = nextStreak;
        setRuntimeValue(service.config.id, "restart-attempts", String.valueOf(nextStreak));
        setRuntimeValue(service.config.id, "last-restart-reason", reason);
        incrementRuntimeCounter(service.config.id, "restart-count");
        service.addLine("[SERVICE] Restart scheduled in " + delaySeconds + "s; reason=" + reason + ".");
        logQuietly("[SERVICE RESTART SCHEDULED] " + service.config.id + " reason=" + reason + " delay=" + delaySeconds + "\n");
        service.restartFuture = lifecycleExecutor.schedule(() -> restartAfterLifecycleEvent(service), delaySeconds, TimeUnit.SECONDS);
    }

    private void restartAfterLifecycleEvent(ManagedGuestService oldService) {
        synchronized (this) {
            String id = oldService.config.id;
            if (processes.get(id) != oldService || oldService.stopRequested) return;
            if (oldService.isRunning()) {
                // A health-triggered graceful stop may still be draining. Try once
                // more later rather than creating a second process on the same port.
                oldService.restartFuture = lifecycleExecutor.schedule(
                        () -> restartAfterLifecycleEvent(oldService), 1, TimeUnit.SECONDS
                );
                return;
            }
            try {
                startServiceInternal(id, true, runtimeValue(id, "last-restart-reason", "lifecycle"));
            } catch (Exception e) {
                oldService.lifecycleRestartRequested = false;
                oldService.addLine("[SERVICE] Automatic restart failed: " + e.getMessage());
                System.out.println(RED + "[Service] Automatic restart failed for " + id + ": " + e.getMessage() + RESET);
                logQuietly("[SERVICE RESTART ERROR] " + id + " " + e.getMessage() + "\n");
            }
        }
    }

    private void scheduleHealthChecks(ManagedGuestService service) {
        cancelFuture(service.healthFuture);
        ServiceConfig config = service.config;
        if (!config.healthEnabled || config.port <= 0) return;
        service.healthFuture = lifecycleExecutor.scheduleWithFixedDelay(
                () -> runScheduledHealthCheck(service),
                1,
                config.healthIntervalSeconds,
                TimeUnit.SECONDS
        );
    }

    private void refreshHealthSchedule(ManagedGuestService service) {
        if (service == null || !service.isRunning()) return;
        try {
            ServiceConfig refreshed = readConfig(service.config.id);
            service.config = refreshed;
            scheduleHealthChecks(service);
        } catch (Exception e) {
            service.addLine("[SERVICE] Cannot refresh health settings: " + e.getMessage());
        }
    }

    private void runScheduledHealthCheck(ManagedGuestService service) {
        synchronized (this) {
            if (processes.get(service.config.id) != service || !service.isRunning() || service.stopRequested) return;
        }
        ServiceConfig config = service.config;
        HealthResult result = probeHealth(config);
        synchronized (this) {
            if (processes.get(config.id) != service || service.stopRequested) return;
            applyHealthResult(config.id, service, config, result, true);
        }
    }

    private HealthResult probeHealth(ServiceConfig config) {
        if (config.port <= 0) return HealthResult.failure("No service port configured");
        HttpURLConnection connection = null;
        try {
            String host = config.host.equals("::1") ? "[::1]" : config.host;
            URL url = URI.create("http://" + host + ":" + config.port + config.healthPath).toURL();
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(config.healthTimeoutMillis);
            connection.setReadTimeout(config.healthTimeoutMillis);
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            if (status >= 200 && status < 400) {
                return HealthResult.success("HTTP " + status + " " + config.healthPath);
            }
            return HealthResult.failure("HTTP " + status + " " + config.healthPath);
        } catch (Exception e) {
            return HealthResult.failure(e.getClass().getSimpleName() + ": " + concise(e.getMessage()));
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private void applyHealthResult(String id, ManagedGuestService service, ServiceConfig config, HealthResult result, boolean allowAction) {
        if (result.ok) {
            if (service != null) service.consecutiveHealthFailures = 0;
            setRuntimeValue(id, "health-status", "healthy");
            setRuntimeValue(id, "health-detail", result.detail);
            setRuntimeValue(id, "health-failures", "0");
            return;
        }

        int failures = service == null ? readRuntimeInt(id, "health-failures", 0) + 1 : ++service.consecutiveHealthFailures;
        String status = failures >= config.healthFailureThreshold ? "unhealthy" : "degraded";
        setRuntimeValue(id, "health-status", status);
        setRuntimeValue(id, "health-detail", result.detail);
        setRuntimeValue(id, "health-failures", String.valueOf(failures));
        if (service != null) service.addLine("[SERVICE] Health " + status + ": " + result.detail + " (failures=" + failures + ")");

        if (allowAction && failures >= config.healthFailureThreshold && config.healthAction.equals("restart")
                && service != null && !service.lifecycleRestartRequested && service.isRunning()) {
            requestHealthRestart(service, result.detail);
        }
    }

    private void requestHealthRestart(ManagedGuestService service, String detail) {
        ServiceConfig config = service.config;
        int streak = service.restartAttempts;
        if (streak >= config.restartMaxAttempts) {
            service.addLine("[SERVICE] Health restart skipped: restart limit reached.");
            return;
        }
        service.lifecycleRestartRequested = true;
        cancelFuture(service.healthFuture);
        service.addLine("[SERVICE] Health restart requested: " + detail);
        destroyProcessTree(service.process);
        scheduleRestart(service, "health-" + concise(detail), config.restartDelaySeconds, streak + 1);
    }

    private void printServiceLine(String id) {
        try {
            ServiceConfig config = readConfig(id);
            System.out.println("  " + id
                    + " | running=" + isRunning(id)
                    + " | enabled=" + config.enabled
                    + " | " + config.type
                    + " | " + config.host + ":" + displayPort(config.port)
                    + " | restart=" + config.restartPolicy
                    + " | health=" + runtimeValue(id, "health-status", config.healthEnabled ? "pending" : "off")
                    + " | public=" + (config.publicEnabled ? config.publicHostname : "off")
                    + " | workdir=" + config.workdir);
        } catch (IOException e) {
            System.out.println("  " + id + " | invalid config: " + e.getMessage());
        }
    }

    private String requireServiceId(String raw) throws IOException {
        String id = normalizeServiceId(raw);
        if (id.isBlank()) {
            throw new IOException("Invalid service id. Use lowercase letters, digits, '-' or '_'.");
        }
        return id;
    }

    private String normalizeServiceId(String raw) {
        if (raw == null) return "";
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9][a-z0-9_-]{0,47}")) return "";
        return id;
    }

    private String normalizeType(String raw) {
        String type = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!type.matches("[a-z0-9][a-z0-9_-]{0,31}")) return "custom";
        return type;
    }

    private String normalizeConfigKey(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "enabled": case "enable": return "enabled";
            case "type": return "type";
            case "runtime": return "runtime";
            case "workdir": case "workspace": case "path": return "workdir";
            case "command": case "start": return "command";
            case "host": case "bind": return "host";
            case "port": return "port";
            case "public-enabled": case "public.enabled": case "publish": return "public.enabled";
            case "public-hostname": case "hostname": case "public.hostname": return "public.hostname";
            case "autostart": case "auto-start": return "autostart";
            case "restart": case "restart-policy": case "restart.policy": return "restart.policy";
            case "restart-max": case "restart.max-attempts": case "restart-max-attempts": return "restart.max-attempts";
            case "restart-delay": case "restart.delay-seconds": case "restart-delay-seconds": return "restart.delay-seconds";
            case "health": case "health-enabled": case "health.enabled": return "health.enabled";
            case "health-path": case "health.path": return "health.path";
            case "health-interval": case "health.interval-seconds": case "health-interval-seconds": return "health.interval-seconds";
            case "health-timeout": case "health.timeout-millis": case "health-timeout-millis": return "health.timeout-millis";
            case "health-failures": case "health.failure-threshold": case "health-failure-threshold": return "health.failure-threshold";
            case "health-action": case "health.action": return "health.action";
            default: return null;
        }
    }

    private boolean requiresStopBeforeChange(String configKey) {
        return configKey.equals("command") || configKey.equals("workdir")
                || configKey.equals("host") || configKey.equals("port") || configKey.equals("runtime");
    }

    private int parsePort(String raw, boolean allowZero) throws IOException {
        try {
            int port = Integer.parseInt(raw == null ? "" : raw.trim());
            if ((allowZero && port == 0) || (port >= 1024 && port <= 65535)) return port;
        } catch (Exception ignored) { }
        throw new IOException(allowZero ? "Port must be 0 or between 1024 and 65535." : "Port must be between 1024 and 65535.");
    }

    private int parseBoundedInt(String raw, int minimum, int maximum, String label) throws IOException {
        try {
            int value = Integer.parseInt(raw == null ? "" : raw.trim());
            if (value >= minimum && value <= maximum) return value;
        } catch (Exception ignored) { }
        throw new IOException(label + " must be between " + minimum + " and " + maximum + ".");
    }

    private String normalizeRestartPolicy(String raw) throws IOException {
        String policy = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (policy.equals("never") || policy.equals("on-failure") || policy.equals("always")) return policy;
        throw new IOException("Restart policy must be never, on-failure, or always.");
    }

    private String normalizeHealthAction(String raw) throws IOException {
        String action = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (action.equals("report") || action.equals("restart")) return action;
        throw new IOException("Health action must be report or restart.");
    }

    private String normalizeHealthPath(String raw) throws IOException {
        String path = raw == null ? "" : raw.trim();
        if (!path.startsWith("/") || path.contains("\\") || path.contains("..") || path.contains("\r") || path.contains("\n") || path.contains(" ")) {
            throw new IOException("Health path must be an absolute HTTP path without '..' or whitespace.");
        }
        return path;
    }

    private boolean isLoopback(String raw) {
        String host = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return host.equals("127.0.0.1") || host.equals("::1") || host.equals("localhost");
    }

    private boolean isSafeHostname(String raw) {
        if (raw == null) return false;
        String hostname = raw.trim().toLowerCase(Locale.ROOT);
        if (hostname.length() < 3 || hostname.length() > 253 || hostname.contains(":")) return false;
        return hostname.matches("(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");
    }

    private String tunnelRouteName(String id) { return "svc-" + id; }

    private String normalizeBoolean(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return (value.equals("true") || value.equals("yes") || value.equals("1") || value.equals("on") || value.equals("enabled")) ? "true" : "false";
    }

    private String displayPort(int port) { return port <= 0 ? "(not configured)" : String.valueOf(port); }

    private String shellQuote(String value) { return "'" + (value == null ? "" : value.replace("'", "'\"'\"'")) + "'"; }

    private String safeThreadName(String value) { return value.replaceAll("[^a-zA-Z0-9._-]", "_"); }

    private String formatDuration(Instant at) {
        if (at == null) return "none";
        long seconds = Math.max(0, Duration.between(at, Instant.now()).toSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return hours > 0 ? hours + "h " + minutes + "m " + rest + "s" : (minutes > 0 ? minutes + "m " + rest + "s" : rest + "s");
    }

    private void printSetHelp() {
        System.out.println(YELLOW + "Valid service keys: enabled, type, runtime, workdir, command, host, port, public-hostname, public-enabled" + RESET);
        System.out.println("Lifecycle: autostart, restart-policy, restart-max, restart-delay");
        System.out.println("Health: health-enabled, health-path, health-interval, health-timeout, health-failures, health-action");
        System.out.println("Example: .mjt service set api restart-policy on-failure");
        System.out.println("Example: .mjt service set api health-enabled true");
    }

    private void clearRuntimeState(String id) {
        for (String suffix : List.of(
                "runtime.last-started-at", "runtime.last-exited-at", "runtime.last-exit-code",
                "runtime.restart-attempts", "runtime.restart-count", "runtime.last-restart-reason",
                "runtime.health-status", "runtime.health-detail", "runtime.health-failures"
        )) {
            try { stateStore.remove(key(id, suffix)); } catch (IOException ignored) { }
        }
    }

    private String runtimeValue(String id, String name, String fallback) {
        return stateStore.get(key(id, "runtime." + name), fallback);
    }

    private int readRuntimeInt(String id, String name, int fallback) {
        try { return Integer.parseInt(runtimeValue(id, name, String.valueOf(fallback))); } catch (Exception ignored) { return fallback; }
    }

    private void setRuntimeValue(String id, String name, String value) {
        try { stateStore.set(key(id, "runtime." + name), value); } catch (IOException ignored) { }
    }

    private void incrementRuntimeCounter(String id, String name) {
        setRuntimeValue(id, name, String.valueOf(readRuntimeInt(id, name, 0) + 1));
    }

    private void waitForProcessStop(Process process, int seconds) {
        if (process == null || !process.isAlive()) return;
        try {
            process.waitFor(Math.max(1, seconds), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void destroyProcessTree(Process process) {
        if (process == null || !process.isAlive()) return;
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(child -> { try { child.destroy(); } catch (Exception ignored) { } });
        process.destroy();
        try { Thread.sleep(1_000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (process.isAlive()) {
            handle.descendants().forEach(child -> { try { child.destroyForcibly(); } catch (Exception ignored) { } });
            process.destroyForcibly();
        }
    }

    private boolean isPending(ScheduledFuture<?> future) { return future != null && !future.isDone() && !future.isCancelled(); }
    private void cancelFuture(ScheduledFuture<?> future) { if (future != null) future.cancel(false); }
    private String concise(String text) { if (text == null || text.isBlank()) return "no detail"; String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim(); return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160); }

    private void log(String text) throws IOException { logService.write(text); }
    private void logQuietly(String text) { try { log(text); } catch (IOException ignored) { } }

    private static final class ServiceConfig {
        final String id;
        final boolean enabled;
        final String type;
        final String command;
        final Path workdir;
        final String host;
        final int port;
        final boolean publicEnabled;
        final String publicHostname;
        final boolean autostart;
        final String restartPolicy;
        final int restartMaxAttempts;
        final int restartDelaySeconds;
        final boolean healthEnabled;
        final String healthPath;
        final int healthIntervalSeconds;
        final int healthTimeoutMillis;
        final int healthFailureThreshold;
        final String healthAction;

        ServiceConfig(
                String id, boolean enabled, String type, String command, Path workdir, String host, int port,
                boolean publicEnabled, String publicHostname, boolean autostart,
                String restartPolicy, int restartMaxAttempts, int restartDelaySeconds,
                boolean healthEnabled, String healthPath, int healthIntervalSeconds, int healthTimeoutMillis,
                int healthFailureThreshold, String healthAction
        ) {
            this.id = id;
            this.enabled = enabled;
            this.type = type;
            this.command = command;
            this.workdir = workdir;
            this.host = host;
            this.port = port;
            this.publicEnabled = publicEnabled;
            this.publicHostname = publicHostname;
            this.autostart = autostart;
            this.restartPolicy = restartPolicy;
            this.restartMaxAttempts = restartMaxAttempts;
            this.restartDelaySeconds = restartDelaySeconds;
            this.healthEnabled = healthEnabled;
            this.healthPath = healthPath;
            this.healthIntervalSeconds = healthIntervalSeconds;
            this.healthTimeoutMillis = healthTimeoutMillis;
            this.healthFailureThreshold = healthFailureThreshold;
            this.healthAction = healthAction;
        }
    }

    private static final class ManagedGuestService {
        volatile ServiceConfig config;
        final Process process;
        final BufferedWriter input;
        final Instant startedAt = Instant.now();
        final ArrayDeque<String> lines = new ArrayDeque<>();
        volatile int lastExitCode = Integer.MIN_VALUE;
        volatile boolean stopRequested;
        volatile boolean lifecycleRestartRequested;
        volatile int restartAttempts;
        volatile int consecutiveHealthFailures;
        volatile ScheduledFuture<?> healthFuture;
        volatile ScheduledFuture<?> restartFuture;

        ManagedGuestService(ServiceConfig config, Process process, BufferedWriter input, int restartAttempts) {
            this.config = config;
            this.process = process;
            this.input = input;
            this.restartAttempts = restartAttempts;
        }

        boolean isRunning() { return process != null && process.isAlive(); }

        synchronized void addLine(String line) {
            lines.addLast(line == null ? "" : line);
            while (lines.size() > MAX_LOG_LINES) lines.removeFirst();
        }

        synchronized List<String> getRecentLines(int maxLines) {
            int limit = maxLines <= 0 ? 200 : Math.min(maxLines, MAX_LOG_LINES);
            List<String> all = new ArrayList<>(lines);
            return all.size() <= limit ? all : new ArrayList<>(all.subList(all.size() - limit, all.size()));
        }
    }

    private static final class HealthResult {
        final boolean ok;
        final String detail;
        private HealthResult(boolean ok, String detail) { this.ok = ok; this.detail = detail; }
        static HealthResult success(String detail) { return new HealthResult(true, detail); }
        static HealthResult failure(String detail) { return new HealthResult(false, detail); }
    }
}
