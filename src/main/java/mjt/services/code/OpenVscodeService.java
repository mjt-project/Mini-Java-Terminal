package main.java.mjt.services.code;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

import main.java.mjt.services.proot.ProotService;
import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

/**
 * Managed OpenVSCode Server process. The server is always launched inside the
 * PRoot guest runtime and binds only to a loopback address. Publishing a public
 * hostname remains a separate Cloudflare Tunnel responsibility.
 */
public final class OpenVscodeService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final int MAX_LOG_LINES = 1000;

    private static final String KEY_ENABLED = "code.enabled";
    private static final String KEY_BINARY = "code.openvscode.binary";
    private static final String KEY_HOST = "code.host";
    private static final String KEY_PORT = "code.port";
    private static final String KEY_WORKSPACE = "code.workspace";
    private static final String KEY_TOKEN = "code.connectionToken";

    private final StateStore stateStore;
    private final LogService logService;
    private final ProotService prootService;
    private final ArrayDeque<String> outputLines = new ArrayDeque<>();
    private Process process;
    private volatile Instant startedAt;
    private volatile int lastExitCode = Integer.MIN_VALUE;

    public OpenVscodeService(StateStore stateStore, LogService logService, ProotService prootService) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.prootService = prootService;
    }

    public synchronized void start() {
        if (isRunning()) {
            System.out.println(YELLOW + "[Code] OpenVSCode Server is already running." + RESET);
            return;
        }
        if (!stateStore.getBoolean(KEY_ENABLED, true)) {
            System.out.println(YELLOW + "[Code] Disabled. Use: .mjt code set enabled true" + RESET);
            return;
        }

        try {
            String host = getHost();
            int port = getPort();
            Path workspace = getHostWorkspace();
            Files.createDirectories(workspace);
            String binary = getGuestBinary();
            String token = ensureToken();
            String guestWorkspace = prootService.toGuestPath(workspace);
            String command = "exec " + shellQuote(binary)
                    + " --host " + shellQuote(host)
                    + " --port " + port
                    + " --connection-token " + shellQuote(token)
                    + " " + shellQuote(guestWorkspace);

            ProcessBuilder builder = new ProcessBuilder(prootService.buildGuestCommand(command, workspace));
            builder.directory(workspace.toFile());
            builder.redirectErrorStream(true);
            process = builder.start();
            startedAt = Instant.now();
            lastExitCode = Integer.MIN_VALUE;
            addLine("[CODE] Started OpenVSCode Server at " + host + ":" + port);

            Thread output = new Thread(this::readOutput, "mjt-openvscode-output");
            output.setDaemon(true);
            output.start();
            Thread watcher = new Thread(this::waitForExit, "mjt-openvscode-watcher");
            watcher.setDaemon(true);
            watcher.start();

            System.out.println(GREEN + "[Code] OpenVSCode Server started." + RESET);
            System.out.println(CYAN + "[Code] Local URL : http://" + host + ":" + port + RESET);
            System.out.println(CYAN + "[Code] Workspace : " + workspace + RESET);
            System.out.println(CYAN + "[Code] Token     : " + stateStore.maskSecret(token) + RESET);
            System.out.println(YELLOW + "[Code] Publish it only through Cloudflare Tunnel; keep this service on 127.0.0.1." + RESET);
            log("[CODE START] workspace=" + workspace + " host=" + host + " port=" + port + "\n");
        } catch (Exception e) {
            process = null;
            System.out.println(RED + "[Code] Start error: " + e.getMessage() + RESET);
            logQuietly("[CODE START ERROR] " + e.getMessage() + "\n");
        }
    }

    public synchronized void stop() {
        if (!isRunning()) {
            System.out.println(YELLOW + "[Code] OpenVSCode Server is not running." + RESET);
            return;
        }
        destroyProcessTree(process);
        System.out.println(YELLOW + "[Code] Stop requested." + RESET);
        logQuietly("[CODE STOP]\n");
    }

    public synchronized void restart() { stopSilently(); start(); }

    public synchronized void status() { showConfig(); }

    public synchronized void showConfig() {
        System.out.println(CYAN + "[OPENVSCODE SERVER]" + RESET);
        System.out.println("Running      : " + isRunning());
        System.out.println("Enabled      : " + stateStore.get(KEY_ENABLED, "true"));
        System.out.println("Runtime      : proot");
        System.out.println("Binary       : " + getGuestBinary());
        System.out.println("Host         : " + getHost());
        System.out.println("Port         : " + getPort());
        System.out.println("Workspace    : " + getHostWorkspace());
        System.out.println("Guest folder : " + safeGuestWorkspace());
        System.out.println("Token        : " + stateStore.maskSecret(stateStore.get(KEY_TOKEN, "")));
        System.out.println("Uptime       : " + formatDuration(startedAt));
        System.out.println("Exit code    : " + (lastExitCode == Integer.MIN_VALUE ? "n/a" : lastExitCode));
        System.out.println("Tunnel       : configure a Cloudflare HTTP route separately");
    }

    public synchronized void resetToken() {
        if (isRunning()) {
            System.out.println(YELLOW + "[Code] Stop OpenVSCode Server before rotating its token." + RESET);
            return;
        }
        try {
            String token = randomToken();
            stateStore.set(KEY_TOKEN, token);
            System.out.println(GREEN + "[Code] Connection token reset: " + stateStore.maskSecret(token) + RESET);
        } catch (IOException e) {
            System.out.println(RED + "[Code] Cannot save token: " + e.getMessage() + RESET);
        }
    }

    public synchronized void setConfig(String rawKey, String rawValue) {
        String key = normalizeKey(rawKey);
        if (key == null) {
            System.out.println(RED + "[Code] Invalid key: " + rawKey + RESET);
            printSetHelp();
            return;
        }
        String value = rawValue == null ? "" : rawValue.trim();
        try {
            if (key.equals(KEY_ENABLED)) value = normalizeBoolean(value);
            if (key.equals(KEY_HOST)) {
                if (!isLoopback(value)) throw new IllegalArgumentException("Code host must be 127.0.0.1, ::1, or localhost.");
                value = value.toLowerCase(Locale.ROOT);
            }
            if (key.equals(KEY_PORT)) {
                int port = parsePort(value);
                value = String.valueOf(port);
            }
            if (key.equals(KEY_WORKSPACE)) {
                if (value.isBlank()) throw new IllegalArgumentException("Workspace path cannot be empty.");
                Path path = Paths.get(value).toAbsolutePath().normalize();
                prootService.toGuestPath(path);
                value = path.toString();
            }
            if (key.equals(KEY_BINARY) && (value.isBlank() || !value.startsWith("/"))) {
                throw new IllegalArgumentException("OpenVSCode binary must be an absolute guest path, for example /opt/openvscode-server/current/bin/openvscode-server.");
            }
            if (key.equals(KEY_TOKEN)) {
                if (value.length() < 16) throw new IllegalArgumentException("Connection token must be at least 16 characters.");
            }
            if (isRunning() && (key.equals(KEY_BINARY) || key.equals(KEY_HOST) || key.equals(KEY_PORT) || key.equals(KEY_WORKSPACE))) {
                throw new IllegalStateException("Stop OpenVSCode Server before changing launch settings.");
            }
            stateStore.set(key, value);
            String visible = key.equals(KEY_TOKEN) ? stateStore.maskSecret(value) : value;
            System.out.println(GREEN + "[Code] Saved " + key + " = " + visible + RESET);
        } catch (Exception e) {
            System.out.println(RED + "[Code] Config error: " + e.getMessage() + RESET);
        }
    }

    public synchronized List<String> getRecentOutputLines(int maxLines) {
        int limit = maxLines <= 0 ? 200 : Math.min(maxLines, MAX_LOG_LINES);
        List<String> all = new ArrayList<>(outputLines);
        return all.size() <= limit ? all : new ArrayList<>(all.subList(all.size() - limit, all.size()));
    }

    private void readOutput() {
        Process local = process;
        if (local == null) return;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(local.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(CYAN + "[Code] " + line + RESET);
                addLine(line);
                log("[CODE OUTPUT] " + line + "\n");
            }
        } catch (IOException e) { logQuietly("[CODE OUTPUT ERROR] " + e.getMessage() + "\n"); }
    }

    private void waitForExit() {
        Process local = process;
        if (local == null) return;
        try {
            int code = local.waitFor();
            lastExitCode = code;
            addLine("[CODE] Exited with code: " + code);
            System.out.println(YELLOW + "[Code] OpenVSCode Server exited with code: " + code + RESET);
            logQuietly("[CODE EXIT] code=" + code + "\n");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isRunning() { return process != null && process.isAlive(); }
    private void stopSilently() { if (isRunning()) destroyProcessTree(process); }
    private String getGuestBinary() { return stateStore.get(KEY_BINARY, "/opt/openvscode-server/current/bin/openvscode-server").trim(); }
    private String getHost() { return stateStore.get(KEY_HOST, "127.0.0.1").trim(); }
    private int getPort() { try { return parsePort(stateStore.get(KEY_PORT, "3000")); } catch (Exception e) { return 3000; } }
    private Path getHostWorkspace() {
        String configured = stateStore.get(KEY_WORKSPACE, "").trim();
        if (configured.isBlank()) {
            configured = stateStore.get("proot.workspace", "/home/container/server").trim();
        }
        if (configured.isBlank()) {
            configured = "/home/container/server";
        }
        return Paths.get(configured).toAbsolutePath().normalize();
    }
    private String safeGuestWorkspace() { try { return prootService.toGuestPath(getHostWorkspace()); } catch (Exception e) { return "(invalid: " + e.getMessage() + ")"; } }
    private String ensureToken() throws IOException { String token = stateStore.get(KEY_TOKEN, "").trim(); if (token.length() >= 16) return token; token = randomToken(); stateStore.set(KEY_TOKEN, token); return token; }
    private String randomToken() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private void addLine(String line) { synchronized (outputLines) { outputLines.addLast(line == null ? "" : line); while (outputLines.size() > MAX_LOG_LINES) outputLines.removeFirst(); } }
    private void destroyProcessTree(Process target) {
        if (target == null || !target.isAlive()) return;
        ProcessHandle handle = target.toHandle();
        handle.descendants().forEach(child -> { try { child.destroy(); } catch (Exception ignored) {} });
        target.destroy();
        try { Thread.sleep(1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (target.isAlive()) { handle.descendants().forEach(child -> { try { child.destroyForcibly(); } catch (Exception ignored) {} }); target.destroyForcibly(); }
    }
    private String normalizeKey(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "enabled": case "enable": case "code.enabled": return KEY_ENABLED;
            case "binary": case "path": case "openvscode": case "code.openvscode.binary": return KEY_BINARY;
            case "host": case "bind": case "code.host": return KEY_HOST;
            case "port": case "code.port": return KEY_PORT;
            case "workspace": case "root": case "code.workspace": return KEY_WORKSPACE;
            case "token": case "connection-token": case "code.connectiontoken": return KEY_TOKEN;
            default: return null;
        }
    }
    private boolean isLoopback(String host) { String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT); return value.equals("127.0.0.1") || value.equals("::1") || value.equals("localhost"); }
    private int parsePort(String raw) { int p = Integer.parseInt(raw.trim()); if (p < 1024 || p > 65535) throw new IllegalArgumentException("Port must be between 1024 and 65535."); return p; }
    private String normalizeBoolean(String raw) { String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT); return value.equals("true") || value.equals("yes") || value.equals("1") || value.equals("on") ? "true" : "false"; }
    private String shellQuote(String value) { return "'" + (value == null ? "" : value.replace("'", "'\\\"'\\\"'")) + "'"; }
    private String formatDuration(Instant at) { if (at == null) return "none"; long seconds = Math.max(0, Duration.between(at, Instant.now()).toSeconds()); long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60; return h > 0 ? h + "h " + m + "m " + s + "s" : (m > 0 ? m + "m " + s + "s" : s + "s"); }
    private void printSetHelp() { System.out.println(YELLOW + "Valid code keys: enabled, binary, host, port, workspace, token" + RESET); System.out.println("Example: .mjt code set binary /opt/openvscode-server/current/bin/openvscode-server"); }
    private void log(String text) throws IOException { logService.write(text); }
    private void logQuietly(String text) { try { log(text); } catch (IOException ignored) {} }
}
