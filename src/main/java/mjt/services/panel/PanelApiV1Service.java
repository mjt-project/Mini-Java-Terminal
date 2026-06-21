package main.java.mjt.services.panel;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import main.java.mjt.services.minecraft.MinecraftProcessManagerService;
import main.java.mjt.services.proot.ProotDistroService;
import main.java.mjt.services.project.ProjectManagerService;
import main.java.mjt.services.service.GuestServiceManager;
import main.java.mjt.system.BuildInfo;
import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

/**
 * Loopback-only REST API for the panel.
 *
 * <p>This is intentionally independent from the legacy {@link PanelService} so
 * existing installations can migrate their frontend without changing the old
 * panel listener in-place. It binds only on loopback by default, uses the same
 * panel auth token, and does not accept tokens in a URL query string.</p>
 */
public final class PanelApiV1Service {
    public static final String API_PREFIX = "/api/v1";

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final int MAX_LOG_LINES = 2_000;

    private final StateStore stateStore;
    private final LogService logService;
    private final MinecraftProcessManagerService minecraftProcessManagerService;
    private final GuestServiceManager guestServiceManager;
    private final ProotDistroService prootDistroService;
    private final ProjectManagerService projectManagerService;
    private final ExecutorService executor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "mjt-panel-api-v1");
        thread.setDaemon(true);
        return thread;
    });

    private HttpServer server;
    private volatile boolean running;

    public PanelApiV1Service(
            StateStore stateStore,
            LogService logService,
            MinecraftProcessManagerService minecraftProcessManagerService,
            GuestServiceManager guestServiceManager,
            ProotDistroService prootDistroService
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.logService = Objects.requireNonNull(logService, "logService");
        this.minecraftProcessManagerService = Objects.requireNonNull(
                minecraftProcessManagerService,
                "minecraftProcessManagerService"
        );
        this.guestServiceManager = Objects.requireNonNull(guestServiceManager, "guestServiceManager");
        this.prootDistroService = Objects.requireNonNull(prootDistroService, "prootDistroService");
        this.projectManagerService = new ProjectManagerService(
                this.stateStore, this.logService, this.guestServiceManager, this.prootDistroService
        );
    }

    public synchronized void start() {
        if (running) {
            System.out.println(YELLOW + "[Panel API] Already running." + RESET);
            return;
        }
        if (!stateStore.getBoolean("panel.api.enabled", true)) {
            System.out.println(YELLOW + "[Panel API] Disabled. Use panel.api.enabled=true to enable it." + RESET);
            return;
        }

        String host = apiHost();
        int port = apiPort();
        try {
            server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
            running = true;
            System.out.println(GREEN + "[Panel API] v1 listening on http://" + host + ":" + port + API_PREFIX + RESET);
            log("[PANEL API V1 START] " + host + ":" + port + "\n");
        } catch (Exception e) {
            server = null;
            running = false;
            System.out.println(RED + "[Panel API] Start error: " + e.getMessage() + RESET);
            logQuietly("[PANEL API V1 START ERROR] " + e.getMessage() + "\n");
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (running) {
            System.out.println(YELLOW + "[Panel API] Stopped." + RESET);
            logQuietly("[PANEL API V1 STOP]\n");
        }
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    public void showConfig() {
        System.out.println(CYAN + "[PANEL API V1]" + RESET);
        System.out.println("Running     : " + running);
        System.out.println("Enabled     : " + stateStore.get("panel.api.enabled", "true"));
        System.out.println("Host        : " + apiHost());
        System.out.println("Port        : " + apiPort());
        System.out.println("Prefix      : " + API_PREFIX);
        System.out.println("CORS origin : " + stateStore.get("panel.api.cors.origins", defaultCorsOrigins()));
        System.out.println("Auth token  : " + stateStore.maskSecret(stateStore.get("panel.auth.token", "")));
    }

    /**
     * Rescue/admin configuration for the loopback API listener. This is kept
     * intentionally small: panel API settings must never expose the listener
     * on a public interface.
     */
    public void setConfig(String rawKey, String rawValue) throws IOException {
        String key = rawKey == null ? "" : rawKey.trim().toLowerCase(Locale.ROOT);
        String value = rawValue == null ? "" : rawValue.trim();
        String storeKey;
        switch (key) {
            case "enabled":
            case "panel.api.enabled":
                storeKey = "panel.api.enabled";
                value = booleanValue(value);
                break;
            case "autostart":
            case "auto-start":
            case "panel.api.autostart":
                storeKey = "panel.api.autoStart";
                value = booleanValue(value);
                break;
            case "host":
            case "bind":
            case "panel.api.host":
                if (!isLoopback(value.toLowerCase(Locale.ROOT))) {
                    throw new IOException("Panel API host must be 127.0.0.1, localhost, or ::1.");
                }
                storeKey = "panel.api.host";
                value = value.toLowerCase(Locale.ROOT);
                break;
            case "port":
            case "panel.api.port":
                int port;
                try {
                    port = Integer.parseInt(value);
                } catch (NumberFormatException invalid) {
                    throw new IOException("Panel API port must be a number.");
                }
                if (port < 1024 || port > 65535) {
                    throw new IOException("Panel API port must be between 1024 and 65535.");
                }
                storeKey = "panel.api.port";
                value = String.valueOf(port);
                break;
            case "cors-origins":
            case "cors":
            case "panel.api.cors.origins":
                validateCorsOrigins(value);
                storeKey = "panel.api.cors.origins";
                break;
            default:
                throw new IOException("Valid panel API keys: enabled, autostart, host, port, cors-origins.");
        }
        stateStore.set(storeKey, value);
        System.out.println(GREEN + "[Panel API] Saved " + storeKey + " = " + value + RESET);
        log("[PANEL API V1 SET] " + storeKey + "\n");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            applyCors(exchange);
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendEmpty(exchange, 204);
                return;
            }

            String path = normalizePath(exchange.getRequestURI());
            if (!path.equals(API_PREFIX) && !path.startsWith(API_PREFIX + "/")) {
                sendJson(exchange, 404, error("not_found", "Unknown API endpoint."));
                return;
            }
            if (!isAuthorized(exchange)) {
                sendJson(exchange, 401, error("unauthorized", "Missing or invalid panel token."));
                return;
            }

            String route = path.length() == API_PREFIX.length() ? "/" : path.substring(API_PREFIX.length());
            dispatch(exchange, route);
        } catch (ApiException e) {
            sendJson(exchange, e.status, error(e.code, e.getMessage()));
        } catch (Exception e) {
            logQuietly("[PANEL API V1 ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n");
            sendJson(exchange, 500, error("internal_error", "The control API could not process this request."));
        } finally {
            exchange.close();
        }
    }

    private void dispatch(HttpExchange exchange, String route) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (route.equals("/auth/check") && method.equals("GET")) {
            sendJson(exchange, 200, "{\"ok\":true,\"authenticated\":true}");
            return;
        }
        if (route.equals("/status") && method.equals("GET")) {
            sendJson(exchange, 200, buildStatusJson());
            return;
        }
        if (route.equals("/capabilities") && method.equals("GET")) {
            sendJson(exchange, 200, "{\"ok\":true,\"apiVersion\":1,\"features\":{\"files\":false,\"backups\":false,\"players\":false,\"network\":true,\"system\":true,\"services\":true,\"serviceEvents\":true,\"distros\":true,\"distroJobs\":true,\"projects\":true}}");
            return;
        }
        if (route.equals("/distros/catalog") && method.equals("GET")) {
            sendJson(exchange, 200, buildDistroCatalogJson());
            return;
        }
        if (route.equals("/distros") && method.equals("GET")) {
            sendJson(exchange, 200, buildDistrosJson());
            return;
        }
        if (route.equals("/distros/engine") && method.equals("GET")) {
            sendJson(exchange, 200, buildDistroEngineJson());
            return;
        }
        if (route.equals("/distros/engine/install") && method.equals("POST")) {
            try {
                sendJson(exchange, 202, buildDistroJobQueuedJson(prootDistroService.installEngineAsync()));
            } catch (IOException e) {
                throw new ApiException(400, "distro_error", e.getMessage());
            }
            return;
        }
        if (route.equals("/distros/engine/update") && method.equals("POST")) {
            Map<String, String> body = readJsonObject(exchange);
            try {
                sendJson(exchange, 202, buildDistroJobQueuedJson(prootDistroService.updateEngineAsync(body.get("version"))));
            } catch (IOException e) {
                throw new ApiException(400, "distro_error", e.getMessage());
            }
            return;
        }
        if (route.equals("/distros/install") && method.equals("POST")) {
            createDistro(exchange);
            return;
        }
        if (route.startsWith("/distros/")) {
            handleDistroRoute(exchange, route.substring("/distros/".length()));
            return;
        }
        if (route.equals("/projects/catalog") && method.equals("GET")) {
            sendJson(exchange, 200, buildProjectCatalogJson());
            return;
        }
        if (route.equals("/projects") && method.equals("GET")) {
            sendJson(exchange, 200, buildProjectsJson());
            return;
        }
        if (route.equals("/projects") && method.equals("POST")) {
            createProject(exchange);
            return;
        }
        if (route.startsWith("/projects/")) {
            handleProjectRoute(exchange, route.substring("/projects/".length()));
            return;
        }
        if (route.equals("/minecraft/status") && method.equals("GET")) {
            sendJson(exchange, 200, buildMinecraftProfilesJson());
            return;
        }
        if (route.equals("/minecraft/logs") && method.equals("GET")) {
            Map<String, String> query = query(exchange.getRequestURI());
            String profile = query.getOrDefault("profile", minecraftProcessManagerService.getAttachedProfile());
            int lines = boundedInt(query.get("lines"), 300, 1, MAX_LOG_LINES);
            sendJson(exchange, 200, buildMinecraftLogsJson(profile, lines));
            return;
        }
        if (route.startsWith("/minecraft/") && method.equals("POST")) {
            handleMinecraftAction(exchange, route.substring("/minecraft/".length()));
            return;
        }
        if (route.equals("/services") && method.equals("GET")) {
            sendJson(exchange, 200, buildServicesJson());
            return;
        }
        if (route.equals("/services") && method.equals("POST")) {
            createService(exchange);
            return;
        }
        if (route.equals("/network") && method.equals("GET")) {
            sendJson(exchange, 200, buildNetworkJson());
            return;
        }
        if (route.startsWith("/services/")) {
            handleServiceRoute(exchange, route.substring("/services/".length()));
            return;
        }
        throw new ApiException(404, "not_found", "Unknown API endpoint.");
    }


    private void createProject(HttpExchange exchange) throws IOException {
        Map<String, String> body = readJsonObject(exchange);
        String id = required(body, "id", 48);
        String templateId = required(body, "templateId", 64);
        boolean start = !"false".equalsIgnoreCase(body.getOrDefault("start", "true"));
        try {
            ProjectManagerService.ProjectInfo project = projectManagerService.create(
                    new ProjectManagerService.CreateRequest(id, templateId, start)
            );
            sendJson(exchange, 201, "{\"ok\":true,\"project\":" + buildProjectJson(project) + "}");
        } catch (IOException e) {
            throw new ApiException(400, "project_error", e.getMessage());
        }
    }

    private void handleProjectRoute(HttpExchange exchange, String tail) throws IOException {
        String[] parts = tail.split("/", 3);
        String id = parts.length == 0 ? "" : parts[0];
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String operation = parts.length > 1 ? parts[1] : "";
        try {
            if (operation.isBlank() && method.equals("GET")) {
                sendJson(exchange, 200, "{\"ok\":true,\"project\":" + buildProjectJson(projectManagerService.project(id)) + "}");
                return;
            }
            if (operation.isBlank() && method.equals("DELETE")) {
                projectManagerService.remove(id);
                sendJson(exchange, 200, "{\"ok\":true,\"id\":" + json(id) + ",\"filesKept\":true}");
                return;
            }
            if (method.equals("POST") && List.of("start", "stop", "restart", "publish", "unpublish", "health").contains(operation)) {
                ProjectManagerService.ProjectInfo project = projectManagerService.action(id, operation);
                sendJson(exchange, 200, "{\"ok\":true,\"project\":" + buildProjectJson(project) + "}");
                return;
            }
        } catch (IOException e) {
            throw new ApiException(400, "project_error", e.getMessage());
        }
        throw new ApiException(405, "method_not_allowed", "Unsupported project operation.");
    }

    private void createDistro(HttpExchange exchange) throws IOException {
        Map<String, String> body = readJsonObject(exchange);
        String catalogId = required(body, "catalogId", 64);
        String name = body.getOrDefault("name", "");
        boolean activate = !"false".equalsIgnoreCase(body.getOrDefault("activate", "true"));
        try {
            String jobId = prootDistroService.installEnvironmentAsync(catalogId, name, activate);
            sendJson(exchange, 202, buildDistroJobQueuedJson(jobId));
        } catch (IOException e) {
            throw new ApiException(400, "distro_error", e.getMessage());
        }
    }

    private void handleDistroRoute(HttpExchange exchange, String tail) throws IOException {
        String[] parts = tail.split("/", 3);
        String first = parts.length == 0 ? "" : parts[0];
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if ("jobs".equals(first) && parts.length == 2 && method.equals("GET")) {
            try {
                sendJson(exchange, 200, buildDistroJobJson(prootDistroService.getJob(parts[1])));
            } catch (IOException e) {
                throw new ApiException(404, "distro_job_not_found", e.getMessage());
            }
            return;
        }

        String name = cleanDistroName(first);
        if (name.isBlank()) {
            throw new ApiException(400, "invalid_environment_name", "Environment name is required.");
        }
        String operation = parts.length > 1 ? parts[1] : "";
        try {
            if (operation.isBlank() && method.equals("GET")) {
                sendJson(exchange, 200, buildDistroEnvironmentJson(prootDistroService.environment(name)));
                return;
            }
            if (operation.equals("activate") && method.equals("POST")) {
                sendJson(exchange, 202, buildDistroJobQueuedJson(prootDistroService.activateEnvironmentAsync(name)));
                return;
            }
            if (operation.equals("remove") && method.equals("POST")) {
                sendJson(exchange, 202, buildDistroJobQueuedJson(prootDistroService.removeEnvironmentAsync(name)));
                return;
            }
        } catch (IOException e) {
            throw new ApiException(400, "distro_error", e.getMessage());
        }
        throw new ApiException(405, "method_not_allowed", "Unsupported environment operation.");
    }

    private void handleMinecraftAction(HttpExchange exchange, String action) throws IOException {
        Map<String, String> body = readJsonObject(exchange);
        String profile = cleanId(body.get("profile"));
        if (profile.isBlank()) profile = minecraftProcessManagerService.getAttachedProfile();
        switch (action) {
            case "start":
                minecraftProcessManagerService.startProfile(profile);
                break;
            case "stop":
                minecraftProcessManagerService.stopProfile(profile);
                break;
            case "kill":
                minecraftProcessManagerService.killProfile(profile);
                break;
            case "restart":
                minecraftProcessManagerService.stopProfile(profile);
                minecraftProcessManagerService.startProfile(profile);
                break;
            case "send":
                String command = required(body, "command", 16_384);
                minecraftProcessManagerService.sendLine(profile, command);
                break;
            default:
                throw new ApiException(404, "not_found", "Unknown Minecraft action.");
        }
        sendJson(exchange, 200, "{\"ok\":true,\"profile\":" + json(profile) + "}");
    }

    private void handleServiceRoute(HttpExchange exchange, String tail) throws IOException {
        String[] parts = tail.split("/", 3);
        String id = cleanId(parts.length == 0 ? "" : parts[0]);
        if (id.isBlank()) throw new ApiException(400, "invalid_service_id", "Service id is required.");
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        String operation = parts.length > 1 ? parts[1] : "";

        if (operation.isBlank()) {
            if (method.equals("GET")) {
                ensureServiceExists(id);
                sendJson(exchange, 200, buildServiceJson(id));
                return;
            }
            if (method.equals("PATCH")) {
                updateService(exchange, id);
                return;
            }
            if (method.equals("DELETE")) {
                guestServiceManager.removeService(id);
                sendJson(exchange, 200, "{\"ok\":true,\"id\":" + json(id) + "}");
                return;
            }
        }
        if (operation.equals("logs") && method.equals("GET")) {
            ensureServiceExists(id);
            int lines = boundedInt(query(exchange.getRequestURI()).get("lines"), 300, 1, MAX_LOG_LINES);
            sendJson(exchange, 200, buildServiceLogsJson(id, lines));
            return;
        }
        if (operation.equals("events") && method.equals("GET")) {
            ensureServiceExists(id);
            streamServiceEvents(exchange, id);
            return;
        }
        if (method.equals("POST")) {
            ensureServiceExists(id);
            switch (operation) {
                case "start": guestServiceManager.startService(id); break;
                case "stop": guestServiceManager.stopService(id); break;
                case "restart": guestServiceManager.restartService(id); break;
                case "publish": guestServiceManager.publishService(id); break;
                case "unpublish": guestServiceManager.unpublishService(id); break;
                case "health": guestServiceManager.runHealthCheck(id); break;
                default: throw new ApiException(404, "not_found", "Unknown service action.");
            }
            sendJson(exchange, 200, buildServiceJson(id));
            return;
        }
        throw new ApiException(405, "method_not_allowed", "This API operation does not accept " + method + ".");
    }

    private void createService(HttpExchange exchange) throws IOException {
        Map<String, String> body = readJsonObject(exchange);
        String id = cleanId(required(body, "id", 48));
        String type = required(body, "type", 32);
        String workdir = required(body, "workdir", 4_096);
        String command = required(body, "command", 16_384);
        guestServiceManager.addService(id + " " + type + " " + workdir + " " + command);
        applyOptionalServiceSettings(id, body);
        sendJson(exchange, 201, buildServiceJson(id));
    }

    private void updateService(HttpExchange exchange, String id) throws IOException {
        Map<String, String> body = readJsonObject(exchange);
        if (body.isEmpty()) throw new ApiException(400, "invalid_request", "At least one service property is required.");
        applyOptionalServiceSettings(id, body);
        sendJson(exchange, 200, buildServiceJson(id));
    }

    private void applyOptionalServiceSettings(String id, Map<String, String> body) throws IOException {
        Map<String, String> allowed = new LinkedHashMap<>();
        allowed.put("enabled", "enabled");
        allowed.put("type", "type");
        allowed.put("workdir", "workdir");
        allowed.put("command", "command");
        allowed.put("host", "host");
        allowed.put("port", "port");
        allowed.put("publicEnabled", "public-enabled");
        allowed.put("publicHostname", "public-hostname");
        allowed.put("autostart", "autostart");
        allowed.put("restartPolicy", "restart-policy");
        allowed.put("restartMax", "restart-max");
        allowed.put("restartDelay", "restart-delay");
        allowed.put("healthEnabled", "health-enabled");
        allowed.put("healthPath", "health-path");
        allowed.put("healthInterval", "health-interval");
        allowed.put("healthTimeout", "health-timeout");
        allowed.put("healthFailures", "health-failures");
        allowed.put("healthAction", "health-action");
        for (Map.Entry<String, String> entry : allowed.entrySet()) {
            String value = body.get(entry.getKey());
            if (value == null) continue;
            guestServiceManager.setServiceConfig(id + " " + entry.getValue() + " " + value);
        }
    }

    private void streamServiceEvents(HttpExchange exchange, String id) throws IOException {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache, no-transform");
        headers.set("Connection", "keep-alive");
        headers.set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody()) {
            List<String> previous = Collections.emptyList();
            for (int tick = 0; tick < 120; tick++) {
                List<String> current = guestServiceManager.getRecentLogs(id, MAX_LOG_LINES);
                if (!current.equals(previous)) {
                    int from = commonPrefix(previous, current);
                    for (int index = from; index < current.size(); index++) {
                        writeSse(output, "log", "{\"id\":" + json(id) + ",\"line\":" + json(current.get(index)) + "}");
                    }
                    previous = current;
                }
                writeSse(output, "status", buildServiceJson(id));
                output.flush();
                sleep(1_000L);
            }
        } catch (IOException ignored) {
            // Browser tab closed or network changed; there is no server-side error.
        }
    }


    private String buildProjectCatalogJson() {
        StringBuilder builder = new StringBuilder("{\"ok\":true,\"activeEnvironment\":");
        builder.append(json(prootDistroService.getActiveEnvironment())).append(",\"templates\":[");
        List<ProjectManagerService.ProjectTemplate> entries = projectManagerService.catalog();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) builder.append(',');
            ProjectManagerService.ProjectTemplate entry = entries.get(index);
            builder.append("{\"id\":").append(json(entry.id()))
                    .append(",\"label\":").append(json(entry.label()))
                    .append(",\"description\":").append(json(entry.description()))
                    .append(",\"runtime\":").append(json(entry.runtime()))
                    .append('}');
        }
        return builder.append("]}").toString();
    }

    private String buildProjectsJson() {
        StringBuilder builder = new StringBuilder("{\"ok\":true,\"projects\":[");
        List<ProjectManagerService.ProjectInfo> entries = projectManagerService.projects();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) builder.append(',');
            builder.append(buildProjectJson(entries.get(index)));
        }
        return builder.append("]}").toString();
    }

    private String buildProjectJson(ProjectManagerService.ProjectInfo project) {
        return "{\"id\":" + json(project.id())
                + ",\"templateId\":" + json(project.templateId())
                + ",\"templateLabel\":" + json(project.templateLabel())
                + ",\"runtime\":" + json(project.runtime())
                + ",\"workdir\":" + json(project.workdir())
                + ",\"serviceId\":" + json(project.serviceId())
                + ",\"port\":" + project.port()
                + ",\"running\":" + project.running()
                + ",\"createdAt\":" + json(project.createdAt())
                + ",\"startupNotice\":" + json(project.startupNotice())
                + "}";
    }

    private String buildStatusJson() {
        return "{\"ok\":true,\"apiVersion\":1,\"coreVersion\":" + json(BuildInfo.VERSION)
                + ",\"release\":" + json(BuildInfo.RELEASE)
                + ",\"serverTime\":" + json(Instant.now().toString())
                + ",\"activeProfile\":" + json(minecraftProcessManagerService.getAttachedProfile())
                + ",\"guestServices\":" + serviceIds().size()
                + ",\"activeEnvironment\":" + json(prootDistroService.getActiveEnvironment())
                + ",\"hostArchitecture\":" + json(prootDistroService.engineInfo().hostArchitecture())
                + ",\"distroEngineReady\":" + prootDistroService.engineInfo().engineReady() + "}";
    }

    private String buildDistroCatalogJson() {
        ProotDistroService.EngineInfo engine = prootDistroService.engineInfo();
        StringBuilder builder = new StringBuilder("{\"ok\":true,\"hostArchitecture\":");
        builder.append(json(engine.hostArchitecture()))
                .append(",\"displayArchitecture\":").append(json(engine.displayArchitecture()))
                .append(",\"supported\":").append(engine.architectureSupported())
                .append(",\"environments\":[");
        List<ProotDistroService.CatalogEntry> entries = prootDistroService.catalog();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) builder.append(',');
            ProotDistroService.CatalogEntry entry = entries.get(index);
            builder.append("{\"id\":").append(json(entry.id()))
                    .append(",\"label\":").append(json(entry.label()))
                    .append(",\"image\":").append(json(entry.image()))
                    .append(",\"architecture\":").append(json(entry.displayArchitecture()))
                    .append(",\"packageManager\":").append(json(entry.packageManager()))
                    .append('}');
        }
        return builder.append("]}").toString();
    }

    private String buildDistroEngineJson() {
        ProotDistroService.EngineInfo info = prootDistroService.engineInfo();
        return "{\"ok\":true,\"engine\":{"
                + "\"enabled\":" + info.enabled()
                + ",\"linuxHost\":" + info.linuxHost()
                + ",\"hostArchitecture\":" + json(info.hostArchitecture())
                + ",\"displayArchitecture\":" + json(info.displayArchitecture())
                + ",\"architectureSupported\":" + info.architectureSupported()
                + ",\"python\":" + json(info.python())
                + ",\"pythonReady\":" + info.pythonReady()
                + ",\"proot\":" + json(info.proot())
                + ",\"prootReady\":" + info.prootReady()
                + ",\"path\":" + json(info.enginePath())
                + ",\"ready\":" + info.engineReady()
                + ",\"version\":" + json(info.engineVersion())
                + ",\"activeEnvironment\":" + json(info.activeEnvironment())
                + ",\"runtimeDirectory\":" + json(info.runtimeDirectory())
                + ",\"cacheDirectory\":" + json(info.cacheDirectory())
                + "}}";
    }

    private String buildDistrosJson() throws IOException {
        StringBuilder builder = new StringBuilder("{\"ok\":true,\"activeEnvironment\":");
        builder.append(json(prootDistroService.getActiveEnvironment())).append(",\"environments\":[");
        List<ProotDistroService.EnvironmentInfo> entries = prootDistroService.environments();
        for (int index = 0; index < entries.size(); index++) {
            if (index > 0) builder.append(',');
            builder.append(buildDistroEnvironmentJson(entries.get(index)));
        }
        return builder.append("]}").toString();
    }

    private String buildDistroEnvironmentJson(ProotDistroService.EnvironmentInfo environment) {
        return "{\"name\":" + json(environment.name())
                + ",\"source\":" + json(environment.source())
                + ",\"rootfs\":" + json(environment.rootfs())
                + ",\"architecture\":" + json(environment.architecture())
                + ",\"active\":" + environment.active()
                + ",\"ready\":" + environment.ready()
                + "}";
    }

    private String buildDistroJobQueuedJson(String jobId) {
        return "{\"ok\":true,\"accepted\":true,\"jobId\":" + json(jobId) + "}";
    }

    private String buildDistroJobJson(ProotDistroService.JobInfo job) {
        return "{\"ok\":true,\"job\":{"
                + "\"id\":" + json(job.id())
                + ",\"type\":" + json(job.type())
                + ",\"target\":" + json(job.target())
                + ",\"state\":" + json(job.state())
                + ",\"message\":" + json(job.message())
                + ",\"createdAt\":" + json(job.createdAt())
                + ",\"startedAt\":" + json(job.startedAt())
                + ",\"finishedAt\":" + json(job.finishedAt())
                + ",\"logs\":" + jsonArray(job.logs())
                + "}}";
    }

    private String buildMinecraftProfilesJson() {
        StringBuilder builder = new StringBuilder("{\"ok\":true,\"profiles\":[");
        List<String> profiles = minecraftProcessManagerService.getProfileNames();
        for (int index = 0; index < profiles.size(); index++) {
            if (index > 0) builder.append(',');
            String id = profiles.get(index);
            String base = "minecraft.profile." + id + ".";
            builder.append("{\"name\":").append(json(id))
                    .append(",\"type\":").append(json(stateStore.get(base + "type", "minecraft")))
                    .append(",\"workdir\":").append(json(minecraftProcessManagerService.getProfileWorkdir(id)))
                    .append(",\"command\":").append(json(minecraftProcessManagerService.getProfileCommand(id)))
                    .append(",\"port\":").append(json(stateStore.get(base + "port", "")))
                    .append(",\"version\":").append(json(stateStore.get(base + "version", "")))
                    .append(",\"running\":").append(minecraftProcessManagerService.isRunning(id))
                    .append('}');
        }
        return builder.append("]}").toString();
    }

    private String buildMinecraftLogsJson(String rawProfile, int lines) {
        String profile = cleanId(rawProfile);
        if (profile.isBlank()) profile = minecraftProcessManagerService.getAttachedProfile();
        List<String> logs = minecraftProcessManagerService.getRecentOutputLines(profile, lines);
        return "{\"ok\":true,\"profile\":" + json(profile) + ",\"lines\":" + jsonArray(logs) + "}";
    }

    private String buildServicesJson() {
        StringBuilder builder = new StringBuilder("{\"ok\":true,\"services\":[");
        List<String> ids = serviceIds();
        for (int index = 0; index < ids.size(); index++) {
            if (index > 0) builder.append(',');
            builder.append(buildServiceJson(ids.get(index)));
        }
        return builder.append("]}").toString();
    }

    private String buildServiceJson(String id) {
        String prefix = "guest.service." + id + ".";
        String status = runtime(id, "health-status", stateStore.getBoolean(prefix + "health.enabled", false) ? "pending" : "off");
        return "{"
                + "\"id\":" + json(id)
                + ",\"enabled\":" + stateStore.getBoolean(prefix + "enabled", true)
                + ",\"type\":" + json(stateStore.get(prefix + "type", "custom"))
                + ",\"runtime\":\"proot\""
                + ",\"workdir\":" + json(stateStore.get(prefix + "workdir", ""))
                + ",\"command\":" + json(stateStore.get(prefix + "command", ""))
                + ",\"host\":" + json(stateStore.get(prefix + "host", "127.0.0.1"))
                + ",\"port\":" + numberOrZero(stateStore.get(prefix + "port", "0"))
                + ",\"running\":" + guestServiceManager.isRunning(id)
                + ",\"public\":{\"enabled\":" + stateStore.getBoolean(prefix + "public.enabled", false)
                + ",\"hostname\":" + json(stateStore.get(prefix + "public.hostname", "")) + "}"
                + ",\"lifecycle\":{\"autostart\":" + stateStore.getBoolean(prefix + "autostart", false)
                + ",\"restartPolicy\":" + json(stateStore.get(prefix + "restart.policy", "never"))
                + ",\"restartMax\":" + numberOrZero(stateStore.get(prefix + "restart.max-attempts", "3"))
                + ",\"restartDelay\":" + numberOrZero(stateStore.get(prefix + "restart.delay-seconds", "5")) + "}"
                + ",\"health\":{\"enabled\":" + stateStore.getBoolean(prefix + "health.enabled", false)
                + ",\"path\":" + json(stateStore.get(prefix + "health.path", "/"))
                + ",\"status\":" + json(status)
                + ",\"detail\":" + json(runtime(id, "health-detail", ""))
                + ",\"failures\":" + numberOrZero(runtime(id, "health-failures", "0")) + "}"
                + ",\"runtimeState\":{\"lastExitCode\":" + numberOrNull(runtime(id, "last-exit-code", ""))
                + ",\"restartCount\":" + numberOrZero(runtime(id, "restart-count", "0"))
                + ",\"lastRestartReason\":" + json(runtime(id, "last-restart-reason", "")) + "}"
                + "}";
    }

    private String buildServiceLogsJson(String id, int lines) throws IOException {
        return "{\"ok\":true,\"id\":" + json(id)
                + ",\"lines\":" + jsonArray(guestServiceManager.getRecentLogs(id, lines)) + "}";
    }

    private String buildNetworkJson() {
        StringBuilder builder = new StringBuilder("{\"ok\":true");
        builder.append(",\"gateway\":{\"enabled\":")
                .append(stateStore.getBoolean("gateway.enabled", true))
                .append(",\"host\":").append(json(stateStore.get("gateway.public.host", "0.0.0.0")))
                .append(",\"port\":").append(json(stateStore.get("gateway.public.port", "auto"))).append('}');
        builder.append(",\"tunnel\":{\"enabled\":")
                .append(stateStore.getBoolean("tunnel.enabled", false))
                .append(",\"mode\":").append(json(stateStore.get("tunnel.mode", "token")))
                .append(",\"running\":").append(stateStore.getBoolean("tunnel.running", false)).append('}');
        builder.append(",\"publishedServices\":[");
        boolean first = true;
        for (String id : serviceIds()) {
            String prefix = "guest.service." + id + ".";
            if (!stateStore.getBoolean(prefix + "public.enabled", false)) continue;
            if (!first) builder.append(',');
            first = false;
            builder.append("{\"serviceId\":").append(json(id))
                    .append(",\"hostname\":").append(json(stateStore.get(prefix + "public.hostname", "")))
                    .append(",\"origin\":").append(json("http://" + stateStore.get(prefix + "host", "127.0.0.1") + ":" + stateStore.get(prefix + "port", "0")))
                    .append('}');
        }
        return builder.append("]}").toString();
    }

    private boolean isAuthorized(HttpExchange exchange) {
        if (!stateStore.getBoolean("panel.auth.enabled", true)) return true;
        String expected = stateStore.get("panel.auth.token", "").trim();
        if (expected.isBlank()) return false;
        String supplied = exchange.getRequestHeaders().getFirst("X-MJT-Token");
        if (supplied == null || supplied.isBlank()) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                supplied = authorization.substring(7).trim();
            }
        }
        return constantTimeEquals(expected, supplied == null ? "" : supplied);
    }

    private void applyCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && allowedOrigins().contains(origin)) {
            Headers headers = exchange.getResponseHeaders();
            headers.set("Access-Control-Allow-Origin", origin);
            headers.set("Vary", "Origin");
            headers.set("Access-Control-Allow-Headers", "Content-Type, X-MJT-Token, Authorization");
            headers.set("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE, OPTIONS");
            headers.set("Access-Control-Max-Age", "600");
        }
    }

    private List<String> allowedOrigins() {
        String raw = stateStore.get("panel.api.cors.origins", defaultCorsOrigins());
        List<String> origins = new ArrayList<>();
        for (String item : raw.split(",")) {
            String origin = item.trim();
            if (isValidCorsOrigin(origin)) origins.add(origin);
        }
        return origins;
    }

    /**
     * CORS is an explicit allow-list, not a wildcard. Local HTTP origins are
     * accepted for development; a published panel must use an exact HTTPS
     * origin such as {@code https://panel.example.com}. Query strings, paths,
     * credentials and fragments are deliberately rejected.
     */
    private void validateCorsOrigins(String raw) throws IOException {
        if (raw == null || raw.trim().isBlank()) {
            throw new IOException("CORS origins cannot be empty.");
        }
        for (String item : raw.split(",")) {
            String origin = item.trim();
            if (!isValidCorsOrigin(origin)) {
                throw new IOException("Invalid CORS origin: " + origin);
            }
        }
    }

    private boolean isValidCorsOrigin(String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            URI origin = URI.create(raw);
            String scheme = origin.getScheme();
            String host = origin.getHost();
            if (scheme == null || host == null || origin.getUserInfo() != null
                    || origin.getQuery() != null || origin.getFragment() != null) {
                return false;
            }
            String path = origin.getPath();
            if (path != null && !path.isBlank() && !path.equals("/")) return false;
            if (scheme.equalsIgnoreCase("https")) return true;
            if (!scheme.equalsIgnoreCase("http")) return false;
            String lowerHost = host.toLowerCase(Locale.ROOT);
            return lowerHost.equals("localhost") || lowerHost.equals("127.0.0.1") || lowerHost.equals("::1");
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String booleanValue(String value) {
        return value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("on") || value.equals("1") ? "true" : "false";
    }

    private String defaultCorsOrigins() {
        int panelPort = stateStore.getInt("panel.port", 9090);
        return "http://127.0.0.1:" + panelPort + ",http://localhost:" + panelPort;
    }

    private String apiHost() {
        String host = stateStore.get("panel.api.host", "127.0.0.1").trim().toLowerCase(Locale.ROOT);
        return isLoopback(host) ? host : "127.0.0.1";
    }

    private int apiPort() {
        int configured = stateStore.getInt("panel.api.port", 9091);
        return configured >= 1024 && configured <= 65535 ? configured : 9091;
    }

    private boolean isLoopback(String host) {
        return host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1");
    }

    private String normalizePath(URI uri) {
        String path = uri == null ? "/" : uri.getPath();
        if (path == null || path.isBlank()) return "/";
        String decoded = URLDecoder.decode(path, StandardCharsets.UTF_8);
        if (!decoded.startsWith("/")) return "/" + decoded;
        return decoded;
    }

    private Map<String, String> query(URI uri) {
        String raw = uri == null ? null : uri.getRawQuery();
        if (raw == null || raw.isBlank()) return Collections.emptyMap();
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            values.put(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8)
            );
        }
        return values;
    }

    private Map<String, String> readJsonObject(HttpExchange exchange) throws IOException {
        byte[] data = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (data.length > MAX_BODY_BYTES) throw new ApiException(413, "payload_too_large", "Request body is too large.");
        String text = new String(data, StandardCharsets.UTF_8).trim();
        if (text.isBlank()) return Collections.emptyMap();
        return FlatJson.parseObject(text);
    }

    private void ensureServiceExists(String id) throws IOException {
        if (!serviceIds().contains(id)) throw new ApiException(404, "service_not_found", "Service not found: " + id);
    }

    private List<String> serviceIds() {
        String raw = stateStore.get("guest.services", "");
        List<String> ids = new ArrayList<>();
        for (String value : raw.split(",")) {
            String id = cleanId(value);
            if (!id.isBlank() && !ids.contains(id)) ids.add(id);
        }
        return ids;
    }

    private String runtime(String id, String suffix, String fallback) {
        return stateStore.get("guest.service." + id + ".runtime." + suffix, fallback);
    }

    private static int boundedInt(String raw, int fallback, int min, int max) {
        try {
            int value = Integer.parseInt(raw == null ? "" : raw.trim());
            return value >= min && value <= max ? value : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int commonPrefix(List<String> previous, List<String> current) {
        int length = Math.min(previous.size(), current.size());
        int index = 0;
        while (index < length && Objects.equals(previous.get(index), current.get(index))) index++;
        return index;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String required(Map<String, String> values, String key, int maxLength) throws IOException {
        String value = values.get(key);
        if (value == null || value.trim().isBlank()) throw new ApiException(400, "invalid_request", "Missing required field: " + key);
        if (value.length() > maxLength) throw new ApiException(400, "invalid_request", "Field is too long: " + key);
        return value.trim();
    }

    private static String cleanId(String raw) {
        if (raw == null) return "";
        String id = raw.trim().toLowerCase(Locale.ROOT);
        return id.matches("[a-z0-9][a-z0-9_-]{0,47}") ? id : "";
    }

    private static String cleanDistroName(String raw) {
        if (raw == null) return "";
        String name = raw.trim().toLowerCase(Locale.ROOT);
        return name.matches("[a-z0-9][a-z0-9._-]{0,63}") ? name : "";
    }

    private static String numberOrZero(String raw) {
        try { return String.valueOf(Math.max(0, Integer.parseInt(raw))); } catch (Exception ignored) { return "0"; }
    }

    private static String numberOrNull(String raw) {
        try { return String.valueOf(Integer.parseInt(raw)); } catch (Exception ignored) { return "null"; }
    }

    private static String jsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) builder.append(',');
            builder.append(json(values.get(index)));
        }
        return builder.append(']').toString();
    }

    private static String json(String value) {
        if (value == null) return "null";
        StringBuilder builder = new StringBuilder(value.length() + 16).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"': builder.append("\\\""); break;
                case '\\': builder.append("\\\\"); break;
                case '\b': builder.append("\\b"); break;
                case '\f': builder.append("\\f"); break;
                case '\n': builder.append("\\n"); break;
                case '\r': builder.append("\\r"); break;
                case '\t': builder.append("\\t"); break;
                default:
                    if (character < 0x20) builder.append(String.format("\\u%04x", (int) character));
                    else builder.append(character);
            }
        }
        return builder.append('"').toString();
    }

    private static String error(String code, String message) {
        return "{\"ok\":false,\"error\":{\"code\":" + json(code) + ",\"message\":" + json(message) + "}}";
    }

    private static void writeSse(OutputStream output, String event, String data) throws IOException {
        output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
        output.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }

    private static boolean constantTimeEquals(String left, String right) {
        byte[] a = left.getBytes(StandardCharsets.UTF_8);
        byte[] b = right.getBytes(StandardCharsets.UTF_8);
        int mismatch = a.length ^ b.length;
        int size = Math.max(a.length, b.length);
        for (int index = 0; index < size; index++) {
            byte av = index < a.length ? a[index] : 0;
            byte bv = index < b.length ? b[index] : 0;
            mismatch |= av ^ bv;
        }
        return mismatch == 0;
    }

    private void log(String line) throws IOException {
        logService.write(line);
    }

    private void logQuietly(String line) {
        try { log(line); } catch (IOException ignored) { }
    }

    private static final class ApiException extends IOException {
        private static final long serialVersionUID = 1L;

        private final int status;
        private final String code;

        private ApiException(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    /** Small JSON object parser for request payloads; arrays and nested objects are deliberately rejected. */
    private static final class FlatJson {
        private final String text;
        private int index;

        private FlatJson(String text) {
            this.text = text;
        }

        private static Map<String, String> parseObject(String text) throws IOException {
            FlatJson parser = new FlatJson(text);
            Map<String, String> result = new LinkedHashMap<>();
            parser.whitespace();
            parser.expect('{');
            parser.whitespace();
            if (parser.consume('}')) return result;
            while (true) {
                parser.whitespace();
                String key = parser.string();
                parser.whitespace();
                parser.expect(':');
                parser.whitespace();
                String value = parser.scalar();
                result.put(key, value);
                parser.whitespace();
                if (parser.consume('}')) break;
                parser.expect(',');
            }
            parser.whitespace();
            if (!parser.done()) throw new ApiException(400, "invalid_json", "Unexpected data after JSON object.");
            return result;
        }

        private String scalar() throws IOException {
            if (peek('"')) return string();
            int start = index;
            while (index < text.length() && ",}".indexOf(text.charAt(index)) < 0 && !Character.isWhitespace(text.charAt(index))) index++;
            String literal = text.substring(start, index).trim();
            if (literal.equals("true") || literal.equals("false") || literal.equals("null") || literal.matches("-?[0-9]+")) {
                return literal.equals("null") ? "" : literal;
            }
            throw new ApiException(400, "invalid_json", "Only JSON string, boolean and integer values are supported.");
        }

        private String string() throws IOException {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char current = text.charAt(index++);
                if (current == '"') return builder.toString();
                if (current != '\\') {
                    builder.append(current);
                    continue;
                }
                if (index >= text.length()) throw new ApiException(400, "invalid_json", "Invalid JSON escape.");
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"': builder.append('"'); break;
                    case '\\': builder.append('\\'); break;
                    case '/': builder.append('/'); break;
                    case 'b': builder.append('\b'); break;
                    case 'f': builder.append('\f'); break;
                    case 'n': builder.append('\n'); break;
                    case 'r': builder.append('\r'); break;
                    case 't': builder.append('\t'); break;
                    case 'u':
                        if (index + 4 > text.length()) throw new ApiException(400, "invalid_json", "Invalid Unicode escape.");
                        try {
                            builder.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                            index += 4;
                        } catch (NumberFormatException invalid) {
                            throw new ApiException(400, "invalid_json", "Invalid Unicode escape.");
                        }
                        break;
                    default: throw new ApiException(400, "invalid_json", "Invalid JSON escape.");
                }
            }
            throw new ApiException(400, "invalid_json", "Unclosed JSON string.");
        }

        private void whitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) index++;
        }

        private boolean consume(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private boolean peek(char expected) {
            return index < text.length() && text.charAt(index) == expected;
        }

        private void expect(char expected) throws IOException {
            if (!consume(expected)) throw new ApiException(400, "invalid_json", "Malformed JSON request.");
        }

        private boolean done() {
            return index >= text.length();
        }
    }
}
