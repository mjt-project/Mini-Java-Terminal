package main.java.mjt.services.panel;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import main.java.mjt.services.minecraft.MinecraftInstallerService;
import main.java.mjt.services.minecraft.MinecraftProcessManagerService;
import main.java.mjt.system.BuildInfo;
import main.java.mjt.services.workspace.WorkspaceDefinition;
import main.java.mjt.services.workspace.WorkspaceFileService;
import main.java.mjt.services.workspace.WorkspaceRegistryService;
import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

public class PanelService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final StateStore stateStore;
    private final LogService logService;
    private final MinecraftProcessManagerService minecraftProcessManagerService;
    private final MinecraftInstallerService minecraftInstallerService;
    private final WorkspaceRegistryService workspaceRegistryService;
    private final WorkspaceFileService workspaceFileService;
    private final ExecutorService executor = Executors.newFixedThreadPool(12);
    private final SecureRandom random = new SecureRandom();

    private HttpServer server;
    private volatile boolean running = false;

    public PanelService(
            StateStore stateStore,
            LogService logService,
            MinecraftProcessManagerService minecraftProcessManagerService,
            MinecraftInstallerService minecraftInstallerService,
            WorkspaceRegistryService workspaceRegistryService,
            WorkspaceFileService workspaceFileService
    ) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.minecraftProcessManagerService = minecraftProcessManagerService;
        this.minecraftInstallerService = minecraftInstallerService;
        this.workspaceRegistryService = workspaceRegistryService;
        this.workspaceFileService = workspaceFileService;
    }

    public synchronized void start() {
        if (running && server != null) {
            System.out.println(YELLOW + "[Panel] Already running." + RESET);
            return;
        }

        if (!stateStore.getBoolean("panel.enabled", false)) {
            System.out.println(YELLOW + "[Panel] Disabled. Use: .mjt panel set enabled true" + RESET);
            return;
        }

        String host = stateStore.get("panel.host", "127.0.0.1").trim();
        int port = stateStore.getInt("panel.port", 9090);
        if (host.isBlank()) host = "127.0.0.1";
        if (port <= 0 || port > 65535) port = 9090;

        try {
            ensurePanelToken();
            Files.createDirectories(getPanelStaticRoot());

            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", this::handleRequest);
            server.setExecutor(executor);
            server.start();
            running = true;

            System.out.println(GREEN + "==================================================" + RESET);
            System.out.println(GREEN + " MJT Control Panel" + RESET);
            System.out.println(GREEN + "==================================================" + RESET);
            System.out.println(CYAN + " Local Panel : http://" + host + ":" + port + RESET);
            System.out.println(" Auth       : " + stateStore.get("panel.auth.enabled", "true"));
            System.out.println(" Token      : " + stateStore.maskSecret(stateStore.get("panel.auth.token", "")));
            System.out.println(" Static root: " + getPanelStaticRoot());
            if (!isFrontendInstalled()) {
                System.out.println(YELLOW + " Frontend   : not installed. Run: .mjt panel install" + RESET);
            } else {
                System.out.println(" Frontend   : installed");
            }
            System.out.println(YELLOW + " Note       : Keep panel on 127.0.0.1 unless intentionally published." + RESET);
            System.out.println();

            logService.write("[PANEL START] " + host + ":" + port + "\n");
        } catch (Exception e) {
            running = false;
            System.out.println(RED + "[Panel] Start error: " + e.getMessage() + RESET);
            tryLog("[PANEL START ERROR] " + e.getMessage() + "\n");
        }
    }

    public synchronized void stop() {
        if (!running || server == null) {
            System.out.println(YELLOW + "[Panel] Not running." + RESET);
            return;
        }
        server.stop(0);
        server = null;
        running = false;
        System.out.println(YELLOW + "[Panel] Stopped." + RESET);
        tryLog("[PANEL STOP]\n");
    }

    public void showConfig() {
        System.out.println(CYAN + "[PANEL CONFIG]" + RESET);
        System.out.println("Running      : " + running);
        System.out.println("Enabled      : " + stateStore.get("panel.enabled", "false"));
        System.out.println("Host         : " + stateStore.get("panel.host", "127.0.0.1"));
        System.out.println("Port         : " + stateStore.get("panel.port", "9090"));
        System.out.println("Auth enabled : " + stateStore.get("panel.auth.enabled", "true"));
        System.out.println("Token        : " + stateStore.maskSecret(stateStore.get("panel.auth.token", "")));
        System.out.println("Static root  : " + getPanelStaticRoot());
        System.out.println("Frontend URL : " + stateStore.get("app.panel.frontend.url", ""));
        System.out.println("Frontend ver : " + stateStore.get("app.panel.frontend.installed.version", ""));
        System.out.println("Installed    : " + isFrontendInstalled());
        System.out.println("Theme        : " + stateStore.get("panel.theme", "dark"));
        System.out.println("Public mode  : " + stateStore.get("panel.public.mode", "local-only"));
    }

    public void setConfig(String key, String value) throws IOException {
        String realKey = normalizeKey(key);
        if (realKey == null) {
            System.out.println(RED + "[Panel] Invalid key: " + key + RESET);
            System.out.println("Valid keys: enabled, host, port, auth, token, theme, public-mode, static-root");
            return;
        }

        String clean = value == null ? "" : value.trim();
        if (realKey.equals("panel.enabled") || realKey.equals("panel.auth.enabled")) {
            clean = normalizeBoolean(clean);
        }
        if (realKey.equals("panel.port")) {
            int port = parseNumber(clean, 9090);
            if (port <= 0 || port > 65535) {
                System.out.println(RED + "[Panel] Invalid port." + RESET);
                return;
            }
            clean = String.valueOf(port);
        }

        stateStore.set(realKey, clean);
        if (realKey.equals("panel.auth.token")) {
            System.out.println(GREEN + "[Panel] Saved token = " + stateStore.maskSecret(clean) + RESET);
        } else {
            System.out.println(GREEN + "[Panel] Saved " + realKey + " = " + clean + RESET);
        }
    }

    public void resetToken() {
        try {
            String token = generateToken();
            stateStore.set("panel.auth.token", token);
            System.out.println(GREEN + "[Panel] New token generated." + RESET);
            System.out.println("Token: " + token);
            System.out.println(YELLOW + "Save this token now. Later config output will be masked." + RESET);
        } catch (IOException e) {
            System.out.println(RED + "[Panel] Token reset error: " + e.getMessage() + RESET);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isBlank()) path = "/";

            if (path.startsWith("/api/")) {
                if (!isAuthorized(exchange)) {
                    sendJson(exchange, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                    return;
                }
                handleApi(exchange, path);
                return;
            }

            handleStatic(exchange, path);
        } catch (Exception e) {
            sendJson(exchange, 500, "{\"ok\":false,\"error\":" + json(e.getMessage()) + "}");
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if (method.equals("GET") && path.equals("/api/auth/check")) {
            sendJson(exchange, 200, "{\"ok\":true}");
            return;
        }

        if (method.equals("GET") && path.equals("/api/capabilities")) {
            sendJson(exchange, 200, buildCapabilitiesJson());
            return;
        }

        if (method.equals("GET") && path.equals("/api/workspaces")) {
            sendJson(exchange, 200, buildWorkspacesJson());
            return;
        }

        if (method.equals("POST") && path.equals("/api/workspaces/register")) {
            String body = readBody(exchange);
            WorkspaceDefinition workspace = workspaceRegistryService.registerExisting(
                    getParam(exchange, "id", body),
                    getParam(exchange, "type", body),
                    getParam(exchange, "name", body),
                    getParam(exchange, "path", body),
                    getParam(exchange, "start", body),
                    getParam(exchange, "stop", body),
                    getParam(exchange, "port", body)
            );
            sendJson(exchange, 200, "{\"ok\":true,\"workspace\":" + workspaceJson(workspace) + "}");
            return;
        }

        if (path.startsWith("/api/workspaces/")) {
            if (handleWorkspaceApi(exchange, method, path)) return;
        }

        if (method.equals("GET") && path.equals("/api/status")) {
            sendJson(exchange, 200, buildStatusJson());
            return;
        }

        if (method.equals("GET") && (path.equals("/api/profiles")
                || path.equals("/api/minecraft/profiles")
                || path.equals("/api/minecraft/status"))) {
            sendJson(exchange, 200, buildProfilesJson());
            return;
        }

        if (method.equals("GET") && (path.equals("/api/server/logs") || path.equals("/api/minecraft/logs"))) {
            String profile = getQueryParams(exchange.getRequestURI()).getOrDefault("profile", "");
            sendJson(exchange, 200, buildLogsJson(profile));
            return;
        }

        if (method.equals("POST") && path.equals("/api/profile/use")) {
            String body = readBody(exchange);
            String name = getParam(exchange, "name", body).trim();
            useProfile(name);
            sendJson(exchange, 200, "{\"ok\":true,\"active\":" + json(stateStore.get("minecraft.active", "smp")) + "}");
            return;
        }

        if (method.equals("POST") && (path.equals("/api/server/start") || path.equals("/api/minecraft/start"))) {
            String body = readBody(exchange);
            String profile = getParam(exchange, "profile", body).trim();
            startProfile(profile.isBlank() ? stateStore.get("minecraft.active", "smp") : profile);
            sendJson(exchange, 200, "{\"ok\":true}");
            return;
        }

        if (method.equals("POST") && (path.equals("/api/server/stop") || path.equals("/api/minecraft/stop"))) {
            String body = readBody(exchange);
            String profile = getParam(exchange, "profile", body).trim();
            stopTargetGracefully(profile);
            sendJson(exchange, 200, "{\"ok\":true}");
            return;
        }

        if (method.equals("POST") && (path.equals("/api/server/kill") || path.equals("/api/minecraft/kill"))) {
            String body = readBody(exchange);
            minecraftProcessManagerService.killProfile(getParam(exchange, "profile", body).trim());
            sendJson(exchange, 200, "{\"ok\":true}");
            return;
        }


        if (method.equals("GET") && path.equals("/api/minecraft/install/providers")) {
            sendJson(exchange, 200, minecraftInstallerService.providersJson());
            return;
        }

        if (method.equals("POST") && path.equals("/api/minecraft/install")) {
            String body = readBody(exchange);
            String software = getParam(exchange, "software", body).trim();
            String profile = getParam(exchange, "profile", body).trim();
            String version = getParam(exchange, "version", body).trim();
            String build = getParam(exchange, "build", body).trim();
            boolean acceptEula = parseBoolean(getParam(exchange, "acceptEula", body));
            boolean force = parseBoolean(getParam(exchange, "force", body));
            MinecraftInstallerService.InstallResult result = minecraftInstallerService.install(software, profile, version, build, acceptEula, force);
            sendJson(exchange, 200, buildInstallResultJson(result));
            return;
        }

        if (method.equals("POST") && (path.equals("/api/server/command") || path.equals("/api/minecraft/send"))) {
            String body = readBody(exchange);
            String command = getParam(exchange, "command", body).trim();
            if (command.isBlank()) {
                sendJson(exchange, 400, "{\"ok\":false,\"error\":\"missing command\"}");
                return;
            }
            minecraftProcessManagerService.sendLine(getParam(exchange, "profile", body).trim(), command);
            sendJson(exchange, 200, "{\"ok\":true}");
            return;
        }

        sendJson(exchange, 404, "{\"ok\":false,\"error\":\"not found\"}");
    }

    public void startProfile(String rawProfile) throws IOException {
        String profile = normalizeName(rawProfile);
        if (profile.isBlank()) profile = normalizeName(stateStore.get("minecraft.active", "smp"));
        if (profile.isBlank()) profile = "smp";
        stateStore.set("minecraft.active", profile);
        minecraftProcessManagerService.startProfile(profile);
    }

    public void useProfile(String rawProfile) throws IOException {
        String profile = normalizeName(rawProfile);
        if (profile.isBlank()) throw new IOException("Invalid profile name.");
        if (!minecraftProcessManagerService.getProfileNames().contains(profile)) {
            throw new IOException("Profile not found: " + profile);
        }
        minecraftProcessManagerService.attach(profile);
        stateStore.set("minecraft.active", profile);
    }

    public void stopTargetGracefully(String rawProfile) {
        String profile = rawProfile == null || rawProfile.trim().isBlank()
                ? stateStore.get("minecraft.active", "smp").trim()
                : rawProfile.trim();
        minecraftProcessManagerService.stopProfile(profile);
    }

    private String buildStatusJson() {
        String active = stateStore.get("minecraft.active", "smp");
        return "{"
                + "\"ok\":true,"
                + "\"version\":" + json(BuildInfo.VERSION) + ","
                + "\"release\":" + json(BuildInfo.RELEASE) + ","
                + "\"panelRunning\":" + running + ","
                + "\"targetRunning\":" + minecraftProcessManagerService.isRunning(active) + ","
                + "\"targetName\":" + json("minecraft:" + active) + ","
                + "\"activeProfile\":" + json(active) + ","
                + "\"attachedProfile\":" + json(minecraftProcessManagerService.getAttachedProfile()) + ","
                + "\"runningProfiles\":" + jsonArray(minecraftProcessManagerService.getRunningProfileNames()) + ","
                + "\"workdir\":" + json(minecraftProcessManagerService.getProfileWorkdir(active)) + ","
                + "\"command\":" + json(minecraftProcessManagerService.getProfileCommand(active)) + ","
                + "\"remote\":" + json(stateStore.get("panel.public.mode", "local-only"))
                + "}";
    }

    private String buildProfilesJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("{\"ok\":true,\"active\":")
                .append(json(stateStore.get("minecraft.active", "smp")))
                .append(",\"profiles\":[");
        boolean first = true;
        for (String profile : minecraftProcessManagerService.getProfileNames()) {
            if (!first) builder.append(',');
            first = false;
            String base = "minecraft.profile." + profile + ".";
            builder.append('{')
                    .append("\"name\":").append(json(profile)).append(',')
                    .append("\"type\":").append(json(stateStore.get(base + "type", "minecraft"))).append(',')
                    .append("\"workdir\":").append(json(minecraftProcessManagerService.getProfileWorkdir(profile))).append(',')
                    .append("\"command\":").append(json(minecraftProcessManagerService.getProfileCommand(profile))).append(',')
                    .append("\"stop\":").append(json(minecraftProcessManagerService.getProfileStopCommand(profile))).append(',')
                    .append("\"port\":").append(json(stateStore.get(base + "port", ""))).append(',')
                    .append("\"software\":").append(json(stateStore.get(base + "software", stateStore.get(base + "type", "minecraft")))).append(',')
                    .append("\"version\":").append(json(stateStore.get(base + "version", ""))).append(',')
                    .append("\"build\":").append(json(stateStore.get(base + "build", ""))).append(',')
                    .append("\"jar\":").append(json(stateStore.get(base + "jar", ""))).append(',')
                    .append("\"running\":").append(minecraftProcessManagerService.isRunning(profile))
                    .append('}');
        }
        builder.append("]}");
        return builder.toString();
    }

    private String buildLogsJson(String rawProfile) {
        String profile = normalizeName(rawProfile);
        if (profile.isBlank()) profile = minecraftProcessManagerService.getAttachedProfile();
        List<String> lines = minecraftProcessManagerService.getRecentOutputLines(profile, 300);
        StringBuilder builder = new StringBuilder();
        builder.append("{\"ok\":true,\"profile\":").append(json(profile)).append(",\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(json(lines.get(i)));
        }
        builder.append("]}");
        return builder.toString();
    }

    private String buildInstallResultJson(MinecraftInstallerService.InstallResult result) {
        return "{"
                + "\"ok\":" + result.ok + ","
                + "\"software\":" + json(result.software) + ","
                + "\"profile\":" + json(result.profile) + ","
                + "\"version\":" + json(result.version) + ","
                + "\"build\":" + json(result.build) + ","
                + "\"jar\":" + json(result.jar) + ","
                + "\"workdir\":" + json(result.workdir) + ","
                + "\"url\":" + json(result.url) + ","
                + "\"eulaAccepted\":" + result.eulaAccepted
                + "}";
    }

    private boolean handleWorkspaceApi(HttpExchange exchange, String method, String path) throws IOException {
        String tail = path.substring("/api/workspaces/".length());
        String[] parts = tail.split("/");
        if (parts.length == 0 || parts[0].isBlank()) return false;
        String workspaceId = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);

        if (parts.length == 1 && method.equals("GET")) {
            WorkspaceDefinition workspace = workspaceRegistryService.get(workspaceId);
            if (workspace == null) {
                sendJson(exchange, 404, "{\"ok\":false,\"error\":\"workspace not found\"}");
            } else {
                sendJson(exchange, 200, "{\"ok\":true,\"workspace\":" + workspaceJson(workspace) + "}");
            }
            return true;
        }

        if (parts.length < 3 || !parts[1].equals("files")) return false;
        String operation = parts[2];
        Map<String, String> query = getQueryParams(exchange.getRequestURI());
        String body = method.equals("GET") ? "" : readBody(exchange);

        try {
            if (method.equals("GET") && operation.equals("list")) {
                WorkspaceFileService.ListResult result = workspaceFileService.list(workspaceId, query.getOrDefault("path", ""));
                sendJson(exchange, 200, fileListJson(result));
                return true;
            }
            if (method.equals("GET") && operation.equals("read")) {
                WorkspaceFileService.ReadResult result = workspaceFileService.read(workspaceId, query.getOrDefault("path", ""));
                sendJson(exchange, 200, "{\"ok\":true,\"workspace\":" + json(result.workspace.id())
                        + ",\"path\":" + json(result.path)
                        + ",\"size\":" + result.size
                        + ",\"content\":" + json(result.content) + "}");
                return true;
            }
            if (method.equals("POST") && operation.equals("write")) {
                workspaceFileService.write(workspaceId, getParam(exchange, "path", body), getParam(exchange, "content", body));
                sendJson(exchange, 200, "{\"ok\":true}");
                return true;
            }
            if (method.equals("POST") && operation.equals("create")) {
                workspaceFileService.createFile(workspaceId, getParam(exchange, "path", body));
                sendJson(exchange, 200, "{\"ok\":true}");
                return true;
            }
            if (method.equals("POST") && operation.equals("mkdir")) {
                workspaceFileService.mkdir(workspaceId, getParam(exchange, "path", body));
                sendJson(exchange, 200, "{\"ok\":true}");
                return true;
            }
            if (method.equals("POST") && operation.equals("rename")) {
                workspaceFileService.rename(workspaceId, getParam(exchange, "from", body), getParam(exchange, "to", body));
                sendJson(exchange, 200, "{\"ok\":true}");
                return true;
            }
            if (method.equals("POST") && operation.equals("delete")) {
                workspaceFileService.delete(workspaceId, getParam(exchange, "path", body));
                sendJson(exchange, 200, "{\"ok\":true}");
                return true;
            }
        } catch (IOException error) {
            sendJson(exchange, 400, "{\"ok\":false,\"error\":" + json(error.getMessage()) + "}");
            return true;
        }
        return false;
    }

    private String buildCapabilitiesJson() {
        return "{\"ok\":true,\"features\":{"
                + "\"dashboard\":true,"
                + "\"minecraft\":true,"
                + "\"installer\":true,"
                + "\"console\":true,"
                + "\"workspaces\":true,"
                + "\"files\":true,"
                + "\"backups\":false,"
                + "\"players\":false,"
                + "\"network\":false,"
                + "\"system\":false}}";
    }

    private String buildWorkspacesJson() throws IOException {
        StringBuilder builder = new StringBuilder("{\"ok\":true,\"root\":")
                .append(json(workspaceRegistryService.getServerRoot().toString()))
                .append(",\"workspaces\":[");
        boolean first = true;
        for (WorkspaceDefinition workspace : workspaceRegistryService.list()) {
            if (!first) builder.append(',');
            first = false;
            builder.append(workspaceJson(workspace));
        }
        builder.append("]}");
        return builder.toString();
    }

    private String workspaceJson(WorkspaceDefinition workspace) {
        boolean running = !workspace.linkedMinecraftProfile().isBlank()
                && minecraftProcessManagerService.isRunning(workspace.linkedMinecraftProfile());
        return "{"
                + "\"id\":" + json(workspace.id()) + ","
                + "\"type\":" + json(workspace.type()) + ","
                + "\"name\":" + json(workspace.name()) + ","
                + "\"path\":" + json(workspace.root().toString()) + ","
                + "\"start\":" + json(workspace.startCommand()) + ","
                + "\"stop\":" + json(workspace.stopCommand()) + ","
                + "\"port\":" + json(workspace.port()) + ","
                + "\"linkedMinecraftProfile\":" + json(workspace.linkedMinecraftProfile()) + ","
                + "\"readOnly\":" + workspace.readOnly() + ","
                + "\"running\":" + running
                + "}";
    }

    private String fileListJson(WorkspaceFileService.ListResult result) {
        StringBuilder builder = new StringBuilder("{\"ok\":true,\"workspace\":")
                .append(json(result.workspace.id()))
                .append(",\"path\":").append(json(result.path))
                .append(",\"parent\":").append(json(result.parent))
                .append(",\"entries\":[");
        for (int i = 0; i < result.entries.size(); i++) {
            WorkspaceFileService.FileEntry entry = result.entries.get(i);
            if (i > 0) builder.append(',');
            builder.append("{")
                    .append("\"name\":").append(json(entry.name)).append(',')
                    .append("\"path\":").append(json(entry.path)).append(',')
                    .append("\"type\":").append(json(entry.type)).append(',')
                    .append("\"size\":").append(entry.size).append(',')
                    .append("\"modifiedAt\":").append(entry.modifiedAt).append(',')
                    .append("\"symlink\":").append(entry.symlink)
                    .append("}");
        }
        builder.append("]}");
        return builder.toString();
    }

    private boolean parseBoolean(String value) {
        if (value == null) return false;
        String clean = value.trim().toLowerCase(Locale.ROOT);
        return clean.equals("true") || clean.equals("yes") || clean.equals("1") || clean.equals("on");
    }

    private void handleStatic(HttpExchange exchange, String path) throws IOException {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET") && !exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            sendText(exchange, 405, "Method Not Allowed", "text/plain; charset=utf-8");
            return;
        }

        Path root = getPanelStaticRoot();
        Path file;
        if (path.equals("/") || path.equals("/index.html")) {
            file = root.resolve("index.html").normalize();
        } else {
            String clean = URLDecoder.decode(path.startsWith("/") ? path.substring(1) : path, StandardCharsets.UTF_8);
            file = root.resolve(clean).normalize();
        }

        if (!file.startsWith(root)) {
            sendText(exchange, 403, "Forbidden", "text/plain; charset=utf-8");
            return;
        }

        if (Files.isDirectory(file)) file = file.resolve("index.html");

        if (!Files.exists(file) || !Files.isRegularFile(file)) {
            if (path.equals("/") || path.equals("/index.html")) {
                sendText(exchange, 200, buildFrontendMissingHtml(), "text/html; charset=utf-8");
            } else {
                sendText(exchange, 404, "Not Found", "text/plain; charset=utf-8");
            }
            return;
        }

        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", contentType(file));
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(200, exchange.getRequestMethod().equalsIgnoreCase("HEAD") ? -1 : bytes.length);
        if (!exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    private boolean isAuthorized(HttpExchange exchange) {
        if (!stateStore.getBoolean("panel.auth.enabled", true)) return true;
        String expectedToken = stateStore.get("panel.auth.token", "").trim();
        if (expectedToken.isBlank()) return false;

        String provided = getQueryParams(exchange.getRequestURI()).getOrDefault("token", "").trim();
        Headers headers = exchange.getRequestHeaders();
        if (provided.isBlank()) provided = firstHeader(headers, "X-MJT-Token");
        if (provided.isBlank()) {
            provided = firstHeader(headers, "Authorization");
            if (provided.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                provided = provided.substring(7).trim();
            }
        }
        if (provided.isBlank()) provided = readCookie(headers, "MJT_TOKEN");
        return constantTimeEquals(expectedToken, provided);
    }

    private void ensurePanelToken() throws IOException {
        if (!stateStore.getBoolean("panel.auth.enabled", true)) return;
        if (stateStore.get("panel.auth.token", "").trim().isBlank()) {
            String token = generateToken();
            stateStore.set("panel.auth.token", token);
            System.out.println(YELLOW + "[Panel] Generated auth token: " + token + RESET);
            System.out.println(YELLOW + "[Panel] Save this token. Use it in panel login." + RESET);
        }
    }

    private boolean isFrontendInstalled() {
        return Files.exists(getPanelStaticRoot().resolve("index.html"));
    }

    private Path getPanelStaticRoot() {
        return Paths.get(stateStore.get("panel.static.root", stateStore.getConfigDir().resolve("panel/static").toString()))
                .toAbsolutePath()
                .normalize();
    }

    private String normalizeName(String name) {
        if (name == null) return "";
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private String normalizeKey(String key) {
        if (key == null) return null;
        String lower = key.toLowerCase(Locale.ROOT).trim();
        switch (lower) {
            case "enabled":
            case "panel.enabled": return "panel.enabled";
            case "host":
            case "bind":
            case "panel.host": return "panel.host";
            case "port":
            case "panel.port": return "panel.port";
            case "auth":
            case "auth-enabled":
            case "panel.auth.enabled": return "panel.auth.enabled";
            case "token":
            case "auth-token":
            case "panel.auth.token": return "panel.auth.token";
            case "theme":
            case "panel.theme": return "panel.theme";
            case "public-mode":
            case "publicmode":
            case "panel.public.mode": return "panel.public.mode";
            case "static-root":
            case "static":
            case "panel.static.root": return "panel.static.root";
            default: return null;
        }
    }

    private String normalizeBoolean(String value) {
        String clean = value == null ? "" : value.trim();
        return (clean.equalsIgnoreCase("true") || clean.equalsIgnoreCase("yes") || clean.equals("1") || clean.equalsIgnoreCase("on")) ? "true" : "false";
    }

    private int parseNumber(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); } catch (Exception e) { return fallback; }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private String getParam(HttpExchange exchange, String name, String body) {
        Map<String, String> query = getQueryParams(exchange.getRequestURI());
        if (query.containsKey(name)) return query.get(name);
        Map<String, String> form = parseParams(body);
        if (form.containsKey(name)) return form.get(name);
        Map<String, String> json = parseJsonObject(body);
        return json.getOrDefault(name, "");
    }

    private Map<String, String> getQueryParams(URI uri) {
        return parseParams(uri.getRawQuery());
    }

    private Map<String, String> parseParams(String raw) {
        Map<String, String> params = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return params;
        if (raw.trim().startsWith("{")) return params;
        for (String item : raw.split("&")) {
            int eq = item.indexOf('=');
            String key = eq < 0 ? item : item.substring(0, eq);
            String value = eq < 0 ? "" : item.substring(eq + 1);
            params.put(urlDecode(key), urlDecode(value));
        }
        return params;
    }

    private Map<String, String> parseJsonObject(String raw) {
        Map<String, String> values = new LinkedHashMap<>();
        if (raw == null || raw.isBlank() || !raw.trim().startsWith("{")) return values;
        String text = raw.trim();
        int i = 0;
        while (i < text.length()) {
            int keyStart = text.indexOf('"', i);
            if (keyStart < 0) break;
            int keyEnd = findStringEnd(text, keyStart + 1);
            if (keyEnd < 0) break;
            String key = unescapeJson(text.substring(keyStart + 1, keyEnd));
            int colon = text.indexOf(':', keyEnd + 1);
            if (colon < 0) break;
            int valueStart = text.indexOf('"', colon + 1);
            if (valueStart < 0) {
                i = colon + 1;
                continue;
            }
            int valueEnd = findStringEnd(text, valueStart + 1);
            if (valueEnd < 0) break;
            values.put(key, unescapeJson(text.substring(valueStart + 1, valueEnd)));
            i = valueEnd + 1;
        }
        return values;
    }

    private int findStringEnd(String text, int start) {
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private String unescapeJson(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
    }

    private String urlDecode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String firstHeader(Headers headers, String name) {
        if (headers == null || name == null || name.isBlank()) return "";
        List<String> values = headers.get(name);
        if (values == null || values.isEmpty()) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    values = entry.getValue();
                    break;
                }
            }
        }
        if (values == null || values.isEmpty()) return "";
        return values.get(0) == null ? "" : values.get(0).trim();
    }

    private String readCookie(Headers headers, String name) {
        String cookieHeader = firstHeader(headers, "Cookie");
        if (cookieHeader.isBlank() || name == null || name.isBlank()) return "";
        for (String cookie : cookieHeader.split(";")) {
            String clean = cookie.trim();
            int equals = clean.indexOf('=');
            if (equals <= 0) continue;
            String key = clean.substring(0, equals).trim();
            String value = clean.substring(equals + 1).trim();
            if (key.equals(name)) {
                try { return URLDecoder.decode(value, StandardCharsets.UTF_8).trim(); }
                catch (Exception ignored) { return value.trim(); }
            }
        }
        return "";
    }

    private boolean constantTimeEquals(String expected, String provided) {
        if (expected == null || provided == null) return false;
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        int diff = a.length ^ b.length;
        int max = Math.max(a.length, b.length);
        for (int i = 0; i < max; i++) {
            byte av = i < a.length ? a[i] : 0;
            byte bv = i < b.length ? b[i] : 0;
            diff |= av ^ bv;
        }
        return diff == 0;
    }

    private void sendJson(HttpExchange exchange, int code, String body) throws IOException {
        sendText(exchange, code, body, "application/json; charset=utf-8");
    }

    private void sendText(HttpExchange exchange, int code, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.sendResponseHeaders(code, exchange.getRequestMethod().equalsIgnoreCase("HEAD") ? -1 : bytes.length);
        if (!exchange.getRequestMethod().equalsIgnoreCase("HEAD")) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    private String json(String value) {
        if (value == null) return "null";
        StringBuilder builder = new StringBuilder();
        builder.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                default:
                    if (c < 32) builder.append(String.format("\\u%04x", (int)c));
                    else builder.append(c);
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private String jsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append(json(values.get(i)));
        }
        builder.append(']');
        return builder.toString();
    }

    private String generateToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) builder.append(String.format("%02x", b));
        return builder.toString();
    }

    private String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    private String buildFrontendMissingHtml() {
        String url = stateStore.get("app.panel.frontend.url", "https://github.com/mjt-project/mjt-panel-web/archive/refs/tags/0.0.1.zip");
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>MJT Panel Not Installed</title>"
                + "<style>body{margin:0;background:#0f172a;color:#e5e7eb;font-family:system-ui;display:grid;place-items:center;min-height:100vh}"
                + ".card{max-width:680px;background:#111827;border:1px solid #334155;border-radius:24px;padding:28px;box-shadow:0 22px 80px rgba(0,0,0,.35)}"
                + "code{display:block;background:#020617;padding:12px;border-radius:12px;color:#bae6fd;overflow:auto}p{color:#94a3b8;line-height:1.6}</style></head><body><main class=\"card\">"
                + "<h1>MJT Panel frontend is not installed</h1>"
                + "<p>Java core is running, but the external web panel files are missing.</p>"
                + "<code>.mjt panel install</code>"
                + "<p>Frontend URL stored in <b>MJT/core/app.properties</b>:</p>"
                + "<code>" + escapeHtml(url) + "</code>"
                + "</main></body></html>";
    }

    private String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private void tryLog(String text) {
        try { logService.write(text); } catch (IOException ignored) {}
    }
}
