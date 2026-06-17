package main.java.mjt.services.cloudflare.tunnel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

public class CloudflareTunnelService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static final Pattern TRYCLOUDFLARE_URL = Pattern.compile(
            "https://[-A-Za-z0-9.]+\\.trycloudflare\\.com"
    );

    private final StateStore stateStore;
    private final LogService logService;

    private Process process;
    private volatile boolean running = false;

    private final Map<String, Process> quickProcesses = new LinkedHashMap<>();

    public CloudflareTunnelService(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
    }

    public synchronized void start() {
        if (running && process != null && process.isAlive()) {
            System.out.println(YELLOW + "[Tunnel] Cloudflare Tunnel is already running." + RESET);
            return;
        }

        if (!stateStore.getBoolean("tunnel.enabled", false)) {
            System.out.println(YELLOW + "[Tunnel] Disabled. Use: .mjt tunnel set enabled true" + RESET);
            return;
        }

        String provider = stateStore.get("tunnel.provider", "cloudflare").trim();

        if (!provider.equalsIgnoreCase("cloudflare")) {
            System.out.println(RED + "[Tunnel] Unsupported provider: " + provider + RESET);
            return;
        }

        try {
            List<String> command = buildStartCommand();
            verifyCloudflaredBinary(command.get(0));

            System.out.println(CYAN + "[Tunnel] Starting: " + maskCommand(command) + RESET);
            logService.write("[TUNNEL START COMMAND] " + maskCommand(command) + "\n");

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            process = processBuilder.start();
            running = true;

            Thread outputThread = new Thread(
                    () -> readOutput(process, "global", "tunnel.publicUrl"),
                    "mjt-cloudflare-tunnel-output"
            );
            outputThread.setDaemon(true);
            outputThread.start();

            Thread watcher = new Thread(
                    () -> waitForExit(process, "global"),
                    "mjt-cloudflare-tunnel-watcher"
            );
            watcher.setDaemon(true);
            watcher.start();

            System.out.println(GREEN + "[Tunnel] Cloudflare Tunnel process started." + RESET);

        } catch (Exception e) {
            running = false;
            System.out.println(RED + "[Tunnel] Start error: " + e.getMessage() + RESET);

            try {
                logService.write("[TUNNEL START ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized void stop() {
        if (!running && (process == null || !process.isAlive())) {
            System.out.println(YELLOW + "[Tunnel] Cloudflare Tunnel is not running." + RESET);
            return;
        }

        stopProcess(process);
        process = null;
        running = false;

        System.out.println(YELLOW + "[Tunnel] Cloudflare Tunnel stopped." + RESET);

        try {
            logService.write("[TUNNEL STOP]\n");
        } catch (IOException ignored) {
        }
    }

    public synchronized void stopAll() {
        stop();
        for (String name : new ArrayList<>(quickProcesses.keySet())) {
            stopQuickTunnel(name);
        }
    }

    public synchronized void startQuickTunnel(String name, String localUrl) throws IOException {
        String cleanName = normalizeRouteName(name);

        if (cleanName.isBlank()) {
            throw new IOException("Invalid quick tunnel name.");
        }

        Process existing = quickProcesses.get(cleanName);
        if (existing != null && existing.isAlive()) {
            System.out.println(YELLOW + "[Tunnel] Quick tunnel already running: " + cleanName + RESET);
            return;
        }

        String cloudflared = stateStore.get("tunnel.cloudflared.path", "cloudflared").trim();
        if (cloudflared.isBlank()) {
            cloudflared = "cloudflared";
        }

        String safeLocalUrl = localUrl == null || localUrl.isBlank()
                ? "http://127.0.0.1:8081"
                : localUrl.trim();

        List<String> command = new ArrayList<>();
        command.add(cloudflared);
        command.add("tunnel");
        command.add("--url");
        command.add(safeLocalUrl);

        verifyCloudflaredBinary(command.get(0));

        System.out.println(CYAN + "[Tunnel] Starting quick tunnel " + cleanName + ": " + String.join(" ", command) + RESET);
        logService.write("[TUNNEL QUICK START] " + cleanName + " -> " + safeLocalUrl + "\n");

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);
        Process quickProcess = processBuilder.start();
        quickProcesses.put(cleanName, quickProcess);

        stateStore.set("website.guest." + cleanName + ".local", safeLocalUrl);
        stateStore.set("website.guest." + cleanName + ".publicUrl", "pending");
        stateStore.set("website.guest." + cleanName + ".tunnel", "running");

        Thread outputThread = new Thread(
                () -> readOutput(quickProcess, "quick/" + cleanName, "website.guest." + cleanName + ".publicUrl"),
                "mjt-cloudflare-quick-tunnel-" + cleanName
        );
        outputThread.setDaemon(true);
        outputThread.start();

        Thread watcher = new Thread(
                () -> waitForQuickExit(cleanName, quickProcess),
                "mjt-cloudflare-quick-tunnel-watcher-" + cleanName
        );
        watcher.setDaemon(true);
        watcher.start();
    }

    public synchronized void stopQuickTunnel(String name) {
        String cleanName = normalizeRouteName(name);
        Process quickProcess = quickProcesses.remove(cleanName);

        if (quickProcess == null || !quickProcess.isAlive()) {
            System.out.println(YELLOW + "[Tunnel] Quick tunnel is not running: " + cleanName + RESET);
            trySet("website.guest." + cleanName + ".tunnel", "stopped");
            return;
        }

        stopProcess(quickProcess);
        trySet("website.guest." + cleanName + ".tunnel", "stopped");

        System.out.println(YELLOW + "[Tunnel] Quick tunnel stopped: " + cleanName + RESET);
    }

    public synchronized boolean isQuickTunnelRunning(String name) {
        Process quickProcess = quickProcesses.get(normalizeRouteName(name));
        return quickProcess != null && quickProcess.isAlive();
    }

    public void status() {
        System.out.println(CYAN + "[CLOUDFLARE TUNNEL STATUS]" + RESET);
        System.out.println("Running      : " + (running && process != null && process.isAlive()));
        System.out.println("Enabled      : " + stateStore.get("tunnel.enabled", "false"));
        System.out.println("Provider     : " + stateStore.get("tunnel.provider", "cloudflare"));
        System.out.println("Mode         : " + stateStore.get("tunnel.mode", "quick"));
        System.out.println("Auto start   : " + stateStore.get("tunnel.autoStart", "false"));
        System.out.println("cloudflared  : " + stateStore.get("tunnel.cloudflared.path", "cloudflared"));
        System.out.println("Token        : " + stateStore.maskSecret(stateStore.get("tunnel.token", "")));
        System.out.println("Tunnel name  : " + stateStore.get("tunnel.name", ""));
        System.out.println("Tunnel id    : " + stateStore.get("tunnel.id", ""));
        System.out.println("Config file  : " + stateStore.get("tunnel.configFile", ""));
        System.out.println("Public URL   : " + stateStore.get("tunnel.publicUrl", ""));
        System.out.println("Quick tunnels: " + (quickProcesses.isEmpty() ? "none" : String.join(",", quickProcesses.keySet())));
        System.out.println("Routes       : " + stateStore.get("tunnel.routes", ""));
        System.out.println();
        listRoutes();
    }

    public void showConfig() {
        status();
    }

    public void setConfig(String key, String value) throws IOException {
        String realKey = normalizeKey(key);

        if (realKey == null) {
            System.out.println(RED + "[Tunnel] Invalid key: " + key + RESET);
            printSetHelp();
            return;
        }

        value = normalizeValue(realKey, value);
        stateStore.set(realKey, value);

        if (realKey.equals("tunnel.token")) {
            System.out.println(GREEN + "[Tunnel] Saved " + realKey + " = " + stateStore.maskSecret(value) + RESET);
        } else {
            System.out.println(GREEN + "[Tunnel] Saved " + realKey + " = " + value + RESET);
        }

        logService.write("[TUNNEL SET] " + realKey + "\n");
    }

    public void listRoutes() {
        System.out.println(CYAN + "[TUNNEL ROUTES]" + RESET);

        List<String> routes = getRouteNames();

        if (routes.isEmpty()) {
            System.out.println("No tunnel routes configured.");
            return;
        }

        for (String name : routes) {
            printRoute(name);
        }
    }

    public void addRoute(String raw) throws IOException {
        String[] parts = raw.trim().split("\\s+", 3);

        if (parts.length < 3) {
            System.out.println(RED + "Usage: .mjt tunnel route add <name> <hostname> <service-url>" + RESET);
            System.out.println("Example: .mjt tunnel route add docs docs.example.com http://127.0.0.1:8082");
            return;
        }

        addRoute(parts[0], parts[1], parts[2]);
    }

    public void addRoute(String name, String hostname, String serviceUrl) throws IOException {
        String routeName = normalizeRouteName(name);

        if (routeName.isBlank()) {
            System.out.println(RED + "[Tunnel] Invalid route name." + RESET);
            return;
        }

        List<String> routes = getRouteNames();

        if (!routes.contains(routeName)) {
            routes.add(routeName);
            saveRouteNames(routes);
        }

        stateStore.set(routeKey(routeName, "enabled"), "true");
        stateStore.set(routeKey(routeName, "hostname"), hostname.trim());
        stateStore.set(routeKey(routeName, "service"), serviceUrl.trim());

        System.out.println(GREEN + "[Tunnel] Added route: " + routeName + " | " + hostname + " -> " + serviceUrl + RESET);
        logService.write("[TUNNEL ROUTE ADD] " + routeName + " " + hostname + " -> " + serviceUrl + "\n");
    }

    public void removeRoute(String routeName) throws IOException {
        String clean = normalizeRouteName(routeName);

        List<String> routes = getRouteNames();
        routes.remove(clean);
        saveRouteNames(routes);

        stateStore.remove(routeKey(clean, "enabled"));
        stateStore.remove(routeKey(clean, "hostname"));
        stateStore.remove(routeKey(clean, "service"));

        System.out.println(GREEN + "[Tunnel] Removed route: " + clean + RESET);
        logService.write("[TUNNEL ROUTE REMOVE] " + clean + "\n");
    }

    public void setRouteEnabled(String routeName, boolean enabled) throws IOException {
        String clean = normalizeRouteName(routeName);
        stateStore.set(routeKey(clean, "enabled"), String.valueOf(enabled));
        System.out.println(GREEN + "[Tunnel] Route " + clean + " enabled=" + enabled + RESET);
    }

    public void generateConfig() {
        try {
            Path configFile = Paths.get(stateStore.get("tunnel.configFile", "MJT/services/cloudflare/tunnel/config.yml"))
                    .toAbsolutePath()
                    .normalize();

            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, buildConfigYaml(), StandardCharsets.UTF_8);

            System.out.println(GREEN + "[Tunnel] Generated config: " + configFile + RESET);
            logService.write("[TUNNEL CONFIG GENERATED] " + configFile + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[Tunnel] Config generation error: " + e.getMessage() + RESET);
        }
    }

    private List<String> buildStartCommand() throws IOException {
        String cloudflared = stateStore.get("tunnel.cloudflared.path", "cloudflared").trim();
        String mode = stateStore.get("tunnel.mode", "quick").trim().toLowerCase(Locale.ROOT);
        List<String> command = new ArrayList<>();

        command.add(cloudflared.isBlank() ? "cloudflared" : cloudflared);

        switch (mode) {
            case "token": {
                String token = stateStore.get("tunnel.token", "").trim();

                if (token.isBlank()) {
                    String localUrl = stateStore.get("tunnel.local.url", "http://127.0.0.1:8081").trim();
                    System.out.println(YELLOW + "[Tunnel] token mode has no token. Falling back to Quick Tunnel." + RESET);
                    System.out.println(YELLOW + "[Tunnel] Guest/preview tunnel does NOT need a token." + RESET);
                    stateStore.set("tunnel.mode", "quick");
                    command.add("tunnel");
                    command.add("--url");
                    command.add(localUrl.isBlank() ? "http://127.0.0.1:8081" : localUrl);
                    return command;
                }

                command.add("tunnel");
                command.add("run");
                command.add("--token");
                command.add(token);
                return command;
            }

            case "quick": {
                String localUrl = stateStore.get("tunnel.local.url", "http://127.0.0.1:8081").trim();
                command.add("tunnel");
                command.add("--url");
                command.add(localUrl);
                return command;
            }

            case "config": {
                generateConfig();
                String configFile = stateStore.get("tunnel.configFile", "MJT/services/cloudflare/tunnel/config.yml").trim();
                String tunnelName = stateStore.get("tunnel.name", stateStore.get("tunnel.id", "")).trim();

                if (tunnelName.isBlank()) {
                    throw new IOException("Missing tunnel.name or tunnel.id for config mode.");
                }

                command.add("tunnel");
                command.add("--config");
                command.add(configFile);
                command.add("run");
                command.add(tunnelName);
                return command;
            }

            default:
                throw new IOException("Unsupported tunnel.mode: " + mode);
        }
    }

    private String buildConfigYaml() {
        String tunnelId = stateStore.get("tunnel.id", stateStore.get("tunnel.name", "")).trim();
        String credentialsFile = stateStore.get("tunnel.credentialsFile", "").trim();

        StringBuilder builder = new StringBuilder();

        if (!tunnelId.isBlank()) {
            builder.append("tunnel: ").append(escapeYamlValue(tunnelId)).append('\n');
        }

        if (!credentialsFile.isBlank()) {
            builder.append("credentials-file: ").append(escapeYamlValue(credentialsFile)).append('\n');
        }

        builder.append("ingress:\n");

        for (String routeName : getRouteNames()) {
            if (!stateStore.getBoolean(routeKey(routeName, "enabled"), false)) {
                continue;
            }

            String hostname = stateStore.get(routeKey(routeName, "hostname"), "").trim();
            String service = stateStore.get(routeKey(routeName, "service"), "").trim();

            if (hostname.isBlank() || service.isBlank()) {
                continue;
            }

            builder.append("  - hostname: ").append(escapeYamlValue(hostname)).append('\n');
            builder.append("    service: ").append(escapeYamlValue(service)).append('\n');
        }

        builder.append("  - service: http_status:404\n");
        return builder.toString();
    }

    private void readOutput(Process localProcess, String label, String publicUrlStateKey) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(localProcess.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                System.out.println(CYAN + "[Tunnel " + label + "] " + line + RESET);
                logService.write("[TUNNEL OUTPUT " + label + "] " + line + "\n");

                String publicUrl = findTryCloudflareUrl(line);
                if (!publicUrl.isBlank()) {
                    stateStore.set(publicUrlStateKey, publicUrl);
                    System.out.println(GREEN + "[Tunnel " + label + "] Public URL: " + publicUrl + RESET);
                    logService.write("[TUNNEL PUBLIC URL " + label + "] " + publicUrl + "\n");
                }
            }

        } catch (IOException e) {
            try {
                logService.write("[TUNNEL OUTPUT ERROR " + label + "] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private void waitForExit(Process localProcess, String label) {
        try {
            int exitCode = localProcess.waitFor();
            running = false;
            System.out.println(YELLOW + "[Tunnel " + label + "] cloudflared exited with code: " + exitCode + RESET);
            logService.write("[TUNNEL EXIT " + label + "] code=" + exitCode + "\n");
        } catch (Exception e) {
            running = false;
            try {
                logService.write("[TUNNEL WAIT ERROR " + label + "] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private void waitForQuickExit(String name, Process localProcess) {
        try {
            int exitCode = localProcess.waitFor();
            synchronized (this) {
                Process current = quickProcesses.get(name);
                if (current == localProcess) {
                    quickProcesses.remove(name);
                }
            }
            trySet("website.guest." + name + ".tunnel", "stopped");
            System.out.println(YELLOW + "[Tunnel quick/" + name + "] cloudflared exited with code: " + exitCode + RESET);
            logService.write("[TUNNEL QUICK EXIT] " + name + " code=" + exitCode + "\n");
        } catch (Exception e) {
            trySet("website.guest." + name + ".tunnel", "stopped");
            try {
                logService.write("[TUNNEL QUICK WAIT ERROR] " + name + " " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private String findTryCloudflareUrl(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }

        Matcher matcher = TRYCLOUDFLARE_URL.matcher(line);
        if (matcher.find()) {
            return matcher.group();
        }

        return "";
    }

    private void stopProcess(Process targetProcess) {
        if (targetProcess == null || !targetProcess.isAlive()) {
            return;
        }

        targetProcess.destroy();

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (targetProcess.isAlive()) {
            targetProcess.destroyForcibly();
        }
    }

    private void trySet(String key, String value) {
        try {
            stateStore.set(key, value);
        } catch (IOException ignored) {
        }
    }

    private List<String> getRouteNames() {
        String raw = stateStore.get("tunnel.routes", "").trim();
        List<String> routes = new ArrayList<>();

        for (String item : raw.split(",")) {
            String clean = normalizeRouteName(item);

            if (!clean.isBlank() && !routes.contains(clean)) {
                routes.add(clean);
            }
        }

        return routes;
    }

    private void saveRouteNames(List<String> routes) throws IOException {
        stateStore.set("tunnel.routes", String.join(",", routes));
    }

    private void printRoute(String routeName) {
        System.out.println("  " + routeName
                + " | enabled=" + stateStore.get(routeKey(routeName, "enabled"), "false")
                + " | hostname=" + stateStore.get(routeKey(routeName, "hostname"), "")
                + " | service=" + stateStore.get(routeKey(routeName, "service"), ""));
    }

    private String routeKey(String routeName, String key) {
        return "tunnel.route." + normalizeRouteName(routeName) + "." + key;
    }

    private String normalizeKey(String key) {
        if (key == null) {
            return null;
        }

        String lower = key.toLowerCase(Locale.ROOT).trim();

        switch (lower) {
            case "enabled":
            case "enable":
            case "tunnel.enabled":
                return "tunnel.enabled";
            case "provider":
            case "tunnel.provider":
                return "tunnel.provider";
            case "mode":
            case "tunnel.mode":
                return "tunnel.mode";
            case "autostart":
            case "auto-start":
            case "tunnel.autostart":
                return "tunnel.autoStart";
            case "cloudflared":
            case "cloudflared-path":
            case "path":
            case "tunnel.cloudflared.path":
                return "tunnel.cloudflared.path";
            case "token":
            case "tunnel.token":
                return "tunnel.token";
            case "name":
            case "tunnel.name":
                return "tunnel.name";
            case "id":
            case "tunnel.id":
                return "tunnel.id";
            case "credentials":
            case "credentials-file":
            case "tunnel.credentialsfile":
                return "tunnel.credentialsFile";
            case "config":
            case "config-file":
            case "tunnel.configfile":
                return "tunnel.configFile";
            case "local":
            case "local-url":
            case "url":
            case "tunnel.local.url":
                return "tunnel.local.url";
            default:
                return null;
        }
    }

    private String normalizeValue(String realKey, String value) {
        String clean = value == null ? "" : value.trim();

        if (realKey.equals("tunnel.enabled") || realKey.equals("tunnel.autoStart")) {
            return normalizeBoolean(clean);
        }

        if (realKey.equals("tunnel.mode")) {
            String lower = clean.toLowerCase(Locale.ROOT);

            if (lower.equals("token") || lower.equals("config") || lower.equals("quick")) {
                return lower;
            }

            throw new IllegalArgumentException("Invalid tunnel mode. Use token, config, or quick.");
        }

        if (realKey.equals("tunnel.provider") && clean.isBlank()) {
            return "cloudflare";
        }

        if (realKey.equals("tunnel.cloudflared.path") && clean.isBlank()) {
            return "cloudflared";
        }

        return clean;
    }

    private void verifyCloudflaredBinary(String cloudflared) throws IOException {
        String binary = cloudflared == null || cloudflared.isBlank() ? "cloudflared" : cloudflared.trim();

        try {
            Process process = new ProcessBuilder(binary, "--version")
                    .redirectErrorStream(true)
                    .start();

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IOException("cloudflared binary check timed out: " + binary);
            }

            if (process.exitValue() != 0) {
                throw new IOException("cloudflared --version returned exit code " + process.exitValue());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("cloudflared binary check interrupted.");

        } catch (IOException e) {
            throw new IOException("cloudflared binary is not available: " + binary
                    + ". Install it with: .mjt system install cloudflared. Or set it with: .mjt tunnel set cloudflared <path-to-cloudflared>", e);
        }
    }

    private String normalizeBoolean(String value) {
        if (value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("on")
                || value.equalsIgnoreCase("enable")
                || value.equalsIgnoreCase("enabled")) {
            return "true";
        }

        return "false";
    }

    private String normalizeRouteName(String value) {
        if (value == null) {
            return "";
        }

        String clean = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        clean = clean.replaceAll("-+", "-");

        if (clean.equals("-") || clean.equals("_")) {
            return "";
        }

        return clean;
    }

    private String escapeYamlValue(String value) {
        if (value == null) {
            return "\"\"";
        }

        String clean = value.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + clean + "\"";
    }

    private String maskCommand(List<String> command) {
        List<String> safe = new ArrayList<>();
        boolean nextIsSecret = false;

        for (String item : command) {
            if (nextIsSecret) {
                safe.add("********");
                nextIsSecret = false;
                continue;
            }

            safe.add(item);

            if (item.equals("--token")) {
                nextIsSecret = true;
            }
        }

        return String.join(" ", safe);
    }

    private void printSetHelp() {
        System.out.println(YELLOW + "Valid tunnel keys:" + RESET);
        System.out.println(".mjt tunnel set enabled true");
        System.out.println(".mjt tunnel set mode token|config|quick");
        System.out.println(".mjt tunnel set token <cloudflare_tunnel_token>");
        System.out.println(".mjt tunnel set auto-start true");
        System.out.println(".mjt tunnel set cloudflared cloudflared");
        System.out.println(".mjt tunnel set local http://127.0.0.1:8081");
        System.out.println(".mjt tunnel route add main main.example.com http://127.0.0.1:8081");
    }
}
