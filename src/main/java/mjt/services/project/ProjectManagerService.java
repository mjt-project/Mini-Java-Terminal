package main.java.mjt.services.project;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import main.java.mjt.services.proot.ProotDistroService;
import main.java.mjt.services.service.GuestServiceManager;
import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

/**
 * Product-level project registry.
 *
 * <p>A project is the user-facing unit in MJT. It owns one workspace folder and
 * one internal guest service. The service remains available for advanced
 * troubleshooting, but normal users create projects rather than hand-writing
 * service records.</p>
 */
public final class ProjectManagerService {
    private static final String PROJECT_IDS_KEY = "project.ids";
    private static final String PROJECT_PREFIX = "project.";
    private static final Pattern PROJECT_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");
    private static final int DEFAULT_PORT_START = 3000;
    private static final int DEFAULT_PORT_END = 3999;

    private final StateStore stateStore;
    private final LogService logService;
    private final GuestServiceManager guestServiceManager;
    private final ProotDistroService prootDistroService;

    public ProjectManagerService(
            StateStore stateStore,
            LogService logService,
            GuestServiceManager guestServiceManager,
            ProotDistroService prootDistroService
    ) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.logService = Objects.requireNonNull(logService, "logService");
        this.guestServiceManager = Objects.requireNonNull(guestServiceManager, "guestServiceManager");
        this.prootDistroService = Objects.requireNonNull(prootDistroService, "prootDistroService");
    }

    public List<ProjectTemplate> catalog() {
        return List.of(
                new ProjectTemplate(
                        "node-http",
                        "Node.js HTTP app",
                        "A small dependency-free Node.js HTTP server. Install Node.js in the active environment before starting.",
                        "node"
                ),
                new ProjectTemplate(
                        "python-http",
                        "Python HTTP app",
                        "A small dependency-free Python HTTP server. Install Python in the active environment before starting.",
                        "python"
                ),
                new ProjectTemplate(
                        "static-site",
                        "Static website",
                        "A starter HTML site served with Python's built-in HTTP server.",
                        "python"
                ),
                new ProjectTemplate(
                        "java-http",
                        "Java HTTP app",
                        "A dependency-free Java HTTP server. Install a JDK in the active environment before starting.",
                        "java"
                )
        );
    }

    public synchronized List<ProjectInfo> projects() {
        List<ProjectInfo> result = new ArrayList<>();
        for (String id : projectIds()) {
            try {
                result.add(project(id));
            } catch (IOException ignored) {
                // Keep one corrupt record from taking down the Panel list. The
                // record remains removable through the API/CLI once repaired.
            }
        }
        return result;
    }

    public synchronized ProjectInfo project(String rawId) throws IOException {
        String id = requireProjectId(rawId);
        if (!projectIds().contains(id)) {
            throw new IOException("Project not found: " + id);
        }
        String templateId = stateStore.get(key(id, "template"), "");
        ProjectTemplate template = requireTemplate(templateId);
        String serviceId = stateStore.get(key(id, "serviceId"), serviceIdFor(id));
        Path workdir = Path.of(stateStore.get(key(id, "workdir"), workspaceRoot().resolve(id).toString()))
                .toAbsolutePath()
                .normalize();
        int port = parsePort(stateStore.get(key(id, "port"), "0"));
        String createdAt = stateStore.get(key(id, "createdAt"), "");
        String startupNotice = stateStore.get(key(id, "startupNotice"), "");
        return new ProjectInfo(
                id,
                template.id(),
                template.label(),
                template.runtime(),
                workdir.toString(),
                serviceId,
                port,
                guestServiceManager.isRunning(serviceId),
                createdAt,
                startupNotice
        );
    }

    public synchronized ProjectInfo create(CreateRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        String id = requireProjectId(request.id());
        ProjectTemplate template = requireTemplate(request.templateId());
        if (projectIds().contains(id)) {
            throw new IOException("A project with this name already exists: " + id);
        }

        // Projects cannot claim a runtime before the operator has intentionally
        // prepared one. This avoids creating host-shell workloads by accident.
        prootDistroService.validateActiveRuntime();

        Path workspace = workspaceRoot();
        if (workspace.toString().contains(" ")) {
            throw new IOException("The configured workspace path contains spaces. MJT Project v1 requires a workspace path without spaces.");
        }
        Files.createDirectories(workspace);
        Path projectDirectory = workspace.resolve(id).normalize();
        if (!projectDirectory.startsWith(workspace)) {
            throw new IOException("Project directory escapes the configured workspace root.");
        }
        if (Files.exists(projectDirectory) && directoryHasContent(projectDirectory)) {
            throw new IOException("Project folder already exists and is not empty: " + projectDirectory);
        }
        Files.createDirectories(projectDirectory);

        String serviceId = serviceIdFor(id);
        if (!stateStore.get("guest.service." + serviceId + ".command", "").isBlank()) {
            throw new IOException("The internal runtime ID is already in use: " + serviceId);
        }
        int port = allocatePort();
        writeTemplate(projectDirectory, template, id, port);
        String command = launchCommand(template.id(), port);

        boolean serviceAdded = false;
        try {
            guestServiceManager.addService(serviceId + " " + template.runtime() + " " + projectDirectory + " " + command);
            serviceAdded = true;
            configureGeneratedService(serviceId, port);

            List<String> ids = projectIds();
            ids.add(id);
            saveProjectIds(ids);
            stateStore.set(key(id, "template"), template.id());
            stateStore.set(key(id, "serviceId"), serviceId);
            stateStore.set(key(id, "workdir"), projectDirectory.toString());
            stateStore.set(key(id, "port"), String.valueOf(port));
            stateStore.set(key(id, "createdAt"), Instant.now().toString());
            stateStore.set(key(id, "startupNotice"), "");

            String startupNotice = "";
            if (request.start()) {
                try {
                    guestServiceManager.startService(serviceId);
                } catch (IOException startError) {
                    startupNotice = startError.getMessage() == null ? "Project was created but could not start." : startError.getMessage();
                    stateStore.set(key(id, "startupNotice"), startupNotice);
                }
            }
            log("[PROJECT CREATE] " + id + " template=" + template.id() + " service=" + serviceId + "\n");
            return project(id);
        } catch (IOException error) {
            if (serviceAdded) {
                try { guestServiceManager.removeService(serviceId); } catch (Exception ignored) { }
            }
            throw error;
        }
    }

    public synchronized ProjectInfo action(String rawId, String rawAction) throws IOException {
        ProjectInfo project = project(rawId);
        String action = rawAction == null ? "" : rawAction.trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "start" -> guestServiceManager.startService(project.serviceId());
            case "stop" -> guestServiceManager.stopService(project.serviceId());
            case "restart" -> guestServiceManager.restartService(project.serviceId());
            case "publish" -> guestServiceManager.publishService(project.serviceId());
            case "unpublish" -> guestServiceManager.unpublishService(project.serviceId());
            case "health" -> guestServiceManager.runHealthCheck(project.serviceId());
            default -> throw new IOException("Unsupported project action: " + rawAction);
        }
        return project(project.id());
    }

    /**
     * Removes MJT metadata and the generated service. Workspace files are kept
     * deliberately: destructive deletion belongs in the Files page, not in a
     * one-click runtime action.
     */
    public synchronized void remove(String rawId) throws IOException {
        ProjectInfo project = project(rawId);
        try { guestServiceManager.removeService(project.serviceId()); } catch (Exception ignored) { }
        List<String> ids = projectIds();
        ids.remove(project.id());
        saveProjectIds(ids);
        for (String suffix : List.of("template", "serviceId", "workdir", "port", "createdAt", "startupNotice")) {
            stateStore.remove(key(project.id(), suffix));
        }
        log("[PROJECT REMOVE] " + project.id() + " files-kept=" + project.workdir() + "\n");
    }

    private void configureGeneratedService(String serviceId, int port) throws IOException {
        guestServiceManager.setServiceConfig(serviceId + " host 127.0.0.1");
        guestServiceManager.setServiceConfig(serviceId + " port " + port);
        guestServiceManager.setServiceConfig(serviceId + " autostart true");
        guestServiceManager.setServiceConfig(serviceId + " restart-policy on-failure");
        guestServiceManager.setServiceConfig(serviceId + " restart-max 3");
        guestServiceManager.setServiceConfig(serviceId + " restart-delay 5");
        guestServiceManager.setServiceConfig(serviceId + " health-enabled true");
        guestServiceManager.setServiceConfig(serviceId + " health-path /");
        guestServiceManager.setServiceConfig(serviceId + " health-interval 30");
        guestServiceManager.setServiceConfig(serviceId + " health-timeout 3000");
        guestServiceManager.setServiceConfig(serviceId + " health-failures 3");
        guestServiceManager.setServiceConfig(serviceId + " health-action restart");
    }

    private Path workspaceRoot() throws IOException {
        String configured = stateStore.get(
                "proot.workspace",
                stateStore.get("workspace.root", "/home/container/server")
        ).trim();
        if (configured.isBlank()) configured = "/home/container/server";
        Path root = Path.of(configured).toAbsolutePath().normalize();
        if (!root.isAbsolute()) throw new IOException("Configured workspace root must be absolute.");
        return root;
    }

    private int allocatePort() throws IOException {
        int start = parsePort(stateStore.get("project.port.start", String.valueOf(DEFAULT_PORT_START)));
        if (start < 1024) start = DEFAULT_PORT_START;
        int end = Math.max(start, DEFAULT_PORT_END);
        for (int port = start; port <= end; port++) {
            if (configuredPortInUse(port)) continue;
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(false);
                socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
                return port;
            } catch (IOException ignored) {
                // Port is already bound by another host-local process.
            }
        }
        throw new IOException("No free loopback port is available in " + start + "-" + end + ".");
    }

    private boolean configuredPortInUse(int candidate) {
        String raw = stateStore.get("guest.services", "");
        if (raw.isBlank()) return false;
        for (String serviceId : raw.split(",")) {
            String id = serviceId.trim();
            if (id.isBlank()) continue;
            if (parsePort(stateStore.get("guest.service." + id + ".port", "0")) == candidate) return true;
        }
        return false;
    }

    private void writeTemplate(Path directory, ProjectTemplate template, String id, int port) throws IOException {
        switch (template.id()) {
            case "node-http" -> writeNodeTemplate(directory, id, port);
            case "python-http" -> writePythonTemplate(directory, id, port);
            case "static-site" -> writeStaticTemplate(directory, id, port);
            case "java-http" -> writeJavaTemplate(directory, id, port);
            default -> throw new IOException("Unsupported project template: " + template.id());
        }
        Files.writeString(directory.resolve(".gitignore"), "node_modules/\n*.class\n__pycache__/\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(".mjt-project.json"), "{\n"
                + "  \"id\": \"" + jsonEscape(id) + "\",\n"
                + "  \"template\": \"" + jsonEscape(template.id()) + "\",\n"
                + "  \"port\": " + port + "\n"
                + "}\n", StandardCharsets.UTF_8);
    }

    private void writeNodeTemplate(Path directory, String id, int port) throws IOException {
        Files.writeString(directory.resolve("server.js"), """
                const http = require('node:http');
                const host = process.env.HOST || '127.0.0.1';
                const port = Number(process.env.PORT || 3000);

                const server = http.createServer((request, response) => {
                  response.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
                  response.end(JSON.stringify({ ok: true, project: '%s', path: request.url }));
                });

                server.listen(port, host, () => console.log(`%s listening on http://${host}:${port}`));
                """.formatted(jsEscape(id), jsEscape(id)), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("package.json"), "{\n  \"name\": \"" + jsonEscape(id) + "\",\n  \"private\": true,\n  \"scripts\": { \"start\": \"node server.js\" }\n}\n", StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("README.md"), "# " + id + "\n\nA Node.js HTTP project created by MJT.\n\nExpected local origin: `http://127.0.0.1:" + port + "`\n", StandardCharsets.UTF_8);
    }

    private void writePythonTemplate(Path directory, String id, int port) throws IOException {
        Files.writeString(directory.resolve("app.py"), """
                import json
                import os
                from http.server import BaseHTTPRequestHandler, HTTPServer

                HOST = os.environ.get("HOST", "127.0.0.1")
                PORT = int(os.environ.get("PORT", "3000"))

                class Handler(BaseHTTPRequestHandler):
                    def do_GET(self):
                        data = json.dumps({"ok": True, "project": "%s", "path": self.path}).encode("utf-8")
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Content-Length", str(len(data)))
                        self.end_headers()
                        self.wfile.write(data)
                    def log_message(self, format, *args):
                        print(format %% args)

                HTTPServer((HOST, PORT), Handler).serve_forever()
                """.formatted(pyEscape(id)), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("README.md"), "# " + id + "\n\nA Python HTTP project created by MJT.\n\nExpected local origin: `http://127.0.0.1:" + port + "`\n", StandardCharsets.UTF_8);
    }

    private void writeStaticTemplate(Path directory, String id, int port) throws IOException {
        Files.writeString(directory.resolve("index.html"), """
                <!doctype html>
                <html lang="en">
                  <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>%s</title></head>
                  <body style="font-family: system-ui; margin: 3rem; line-height: 1.5">
                    <h1>%s</h1>
                    <p>This static site was created by MJT.</p>
                    <p>It is private on <code>127.0.0.1:%d</code> until you explicitly publish it.</p>
                  </body>
                </html>
                """.formatted(htmlEscape(id), htmlEscape(id), port), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("README.md"), "# " + id + "\n\nA static website created by MJT.\n\nExpected local origin: `http://127.0.0.1:" + port + "`\n", StandardCharsets.UTF_8);
    }

    private void writeJavaTemplate(Path directory, String id, int port) throws IOException {
        Files.writeString(directory.resolve("Main.java"), """
                import com.sun.net.httpserver.HttpServer;
                import java.net.InetSocketAddress;
                import java.nio.charset.StandardCharsets;

                public class Main {
                  public static void main(String[] args) throws Exception {
                    String host = System.getenv().getOrDefault("HOST", "127.0.0.1");
                    int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "3000"));
                    HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
                    server.createContext("/", exchange -> {
                      byte[] body = ("{\\\"ok\\\":true,\\\"project\\\":\\\"%s\\\"}").getBytes(StandardCharsets.UTF_8);
                      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                      exchange.sendResponseHeaders(200, body.length);
                      exchange.getResponseBody().write(body);
                      exchange.close();
                    });
                    server.start();
                    System.out.println("%s listening on http://" + host + ":" + port);
                  }
                }
                """.formatted(javaEscape(id), javaEscape(id)), StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("README.md"), "# " + id + "\n\nA Java HTTP project created by MJT.\n\nExpected local origin: `http://127.0.0.1:" + port + "`\n", StandardCharsets.UTF_8);
    }

    private String launchCommand(String templateId, int port) throws IOException {
        return switch (templateId) {
            case "node-http" -> "HOST=127.0.0.1 PORT=" + port + " node server.js";
            case "python-http" -> "HOST=127.0.0.1 PORT=" + port + " python3 app.py";
            case "static-site" -> "python3 -m http.server " + port + " --bind 127.0.0.1";
            case "java-http" -> "javac --add-modules jdk.httpserver Main.java && HOST=127.0.0.1 PORT=" + port + " java --add-modules jdk.httpserver Main";
            default -> throw new IOException("Unsupported project template: " + templateId);
        };
    }

    private ProjectTemplate requireTemplate(String rawId) throws IOException {
        String id = rawId == null ? "" : rawId.trim().toLowerCase(Locale.ROOT);
        for (ProjectTemplate template : catalog()) {
            if (template.id().equals(id)) return template;
        }
        throw new IOException("Unknown project template: " + rawId);
    }

    private List<String> projectIds() {
        List<String> result = new ArrayList<>();
        String raw = stateStore.get(PROJECT_IDS_KEY, "").trim();
        if (raw.isBlank()) return result;
        for (String candidate : raw.split(",")) {
            String id = candidate.trim();
            if (PROJECT_ID.matcher(id).matches() && !result.contains(id)) result.add(id);
        }
        return result;
    }

    private void saveProjectIds(List<String> ids) throws IOException {
        stateStore.set(PROJECT_IDS_KEY, String.join(",", ids));
    }

    private String requireProjectId(String raw) throws IOException {
        String id = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!PROJECT_ID.matcher(id).matches()) {
            throw new IOException("Project name must use lowercase letters, numbers, and hyphens (1-48 characters).");
        }
        return id;
    }

    private String serviceIdFor(String projectId) {
        return "project-" + projectId;
    }

    private String key(String projectId, String suffix) {
        return PROJECT_PREFIX + projectId + "." + suffix;
    }

    private static boolean directoryHasContent(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.findAny().isPresent();
        }
    }

    private static int parsePort(String raw) {
        try {
            int value = Integer.parseInt(raw == null ? "0" : raw.trim());
            return value >= 0 && value <= 65535 ? value : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void log(String text) {
        try { logService.write(text); } catch (IOException ignored) { }
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsEscape(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String pyEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String javaEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    public record ProjectTemplate(String id, String label, String description, String runtime) { }

    public record CreateRequest(String id, String templateId, boolean start) { }

    public record ProjectInfo(
            String id,
            String templateId,
            String templateLabel,
            String runtime,
            String workdir,
            String serviceId,
            int port,
            boolean running,
            String createdAt,
            String startupNotice
    ) { }
}
