package main.java.mjt.services.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

public class HttpService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final StateStore stateStore;
    private final LogService logService;
    private final ExecutorService executor = Executors.newFixedThreadPool(16);

    private HttpServer httpServer;
    private volatile boolean running = false;

    public HttpService(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
    }

    public synchronized void start() {
        if (running) {
            System.out.println(YELLOW + "[HTTP] Service is already running." + RESET);
            return;
        }

        if (!stateStore.getBoolean("http.enabled", true)) {
            System.out.println(YELLOW + "[HTTP] Service is disabled. Use: .mjt http set enabled true" + RESET);
            return;
        }

        String host = stateStore.get("http.host", "127.0.0.1").trim();
        int port = stateStore.getInt("http.port", 8080);

        if (host.isBlank()) {
            host = "127.0.0.1";
        }

        if (port <= 0 || port > 65535) {
            port = 8080;
        }

        try {
            httpServer = HttpServer.create(new InetSocketAddress(host, port), 0);
            httpServer.createContext("/", this::handleRequest);
            httpServer.setExecutor(executor);
            httpServer.start();
            running = true;

            System.out.println(GREEN + "==================================================" + RESET);
            System.out.println(GREEN + " HTTP Service" + RESET);
            System.out.println(GREEN + "==================================================" + RESET);
            System.out.println(CYAN + " Local HTTP : " + host + ":" + port + RESET);
            System.out.println(" Root       : " + stateStore.get("http.root", "/home/container/www"));
            System.out.println(" Index      : " + stateStore.get("http.index", "index.html"));
            System.out.println(" SPA mode   : " + stateStore.get("http.spa", "false"));
            System.out.println(" Auto HTTPS : " + stateStore.get("http.autoHttps", "true"));
            System.out.println(YELLOW + " Note       : HTTP is local-only. Public HTTPS should be handled by the outer proxy/tunnel." + RESET);
            System.out.println();

            logService.write("[HTTP START] " + host + ":" + port + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[HTTP] Start error: " + e.getMessage() + RESET);

            try {
                logService.write("[HTTP START ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized void stop() {
        if (!running || httpServer == null) {
            System.out.println(YELLOW + "[HTTP] Service is not running." + RESET);
            return;
        }

        httpServer.stop(0);
        httpServer = null;
        running = false;

        System.out.println(YELLOW + "[HTTP] Service stopped." + RESET);

        try {
            logService.write("[HTTP STOP]\n");
        } catch (IOException ignored) {
        }
    }

    public void status() {
        System.out.println(CYAN + "[HTTP STATUS]" + RESET);
        System.out.println("Running    : " + running);
        System.out.println("Enabled    : " + stateStore.get("http.enabled", "true"));
        System.out.println("Host       : " + stateStore.get("http.host", "127.0.0.1"));
        System.out.println("Port       : " + stateStore.get("http.port", "8080"));
        System.out.println("Root       : " + stateStore.get("http.root", "/home/container/www"));
        System.out.println("Index      : " + stateStore.get("http.index", "index.html"));
        System.out.println("SPA        : " + stateStore.get("http.spa", "false"));
        System.out.println("Auto HTTPS : " + stateStore.get("http.autoHttps", "true"));
    }

    public void showConfig() {
        status();
    }

    public void setConfig(String key, String value) throws IOException {
        String realKey = normalizeKey(key);

        if (realKey == null) {
            System.out.println(RED + "[HTTP] Invalid key: " + key + RESET);
            printSetHelp();
            return;
        }

        value = normalizeValue(realKey, value);
        stateStore.set(realKey, value);

        System.out.println(GREEN + "[HTTP] Saved " + realKey + " = " + value + RESET);
        logService.write("[HTTP SET] " + realKey + " = " + value + "\n");
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if (!method.equals("GET") && !method.equals("HEAD")) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        Path webRoot = Paths.get(stateStore.get("http.root", "/home/container/www"))
                .toAbsolutePath()
                .normalize();

        String indexFileName = stateStore.get("http.index", "index.html").trim();
        boolean spaFallback = stateStore.getBoolean("http.spa", false);

        Path targetFile = resolveHttpFile(webRoot, exchange.getRequestURI(), indexFileName);

        if (targetFile == null) {
            sendText(exchange, 403, "Forbidden");
            return;
        }

        if (!Files.exists(targetFile) || !Files.isRegularFile(targetFile)) {
            if (spaFallback) {
                Path fallbackFile = webRoot.resolve(indexFileName).normalize();

                if (fallbackFile.startsWith(webRoot)
                        && Files.exists(fallbackFile)
                        && Files.isRegularFile(fallbackFile)) {
                    targetFile = fallbackFile;
                } else {
                    sendText(exchange, 404, "Not Found");
                    return;
                }
            } else {
                sendText(exchange, 404, "Not Found");
                return;
            }
        }

        byte[] fileBytes = Files.readAllBytes(targetFile);

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", guessContentType(targetFile));
        headers.set("Cache-Control", "no-cache");

        if (stateStore.getBoolean("http.autoHttps", true)) {
            headers.set("X-MJT-Auto-HTTPS", "outer-proxy");
        }

        exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : fileBytes.length);

        if (!method.equals("HEAD")) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(fileBytes);
            }
        } else {
            exchange.close();
        }

        try {
            logService.write("[HTTP] " + method + " " + exchange.getRequestURI() + " -> " + targetFile + "\n");
        } catch (IOException ignored) {
        }
    }

    private Path resolveHttpFile(Path webRoot, URI uri, String indexFileName) {
        try {
            String cleanPath = uri.getPath();

            if (cleanPath == null || cleanPath.isBlank() || cleanPath.equals("/")) {
                cleanPath = "/" + indexFileName;
            }

            cleanPath = URLDecoder.decode(cleanPath, StandardCharsets.UTF_8);

            if (cleanPath.endsWith("/")) {
                cleanPath = cleanPath + indexFileName;
            }

            while (cleanPath.startsWith("/")) {
                cleanPath = cleanPath.substring(1);
            }

            Path target = webRoot.resolve(cleanPath).normalize();

            if (!target.startsWith(webRoot)) {
                return null;
            }

            return target;

        } catch (Exception e) {
            return null;
        }
    }

    private void sendText(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);

        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String guessContentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".txt")) return "text/plain; charset=utf-8";

        return "application/octet-stream";
    }

    private String normalizeKey(String key) {
        if (key == null) {
            return null;
        }

        String lower = key.toLowerCase().trim();

        switch (lower) {
            case "enabled":
            case "enable":
            case "http.enabled":
                return "http.enabled";

            case "host":
            case "bind":
            case "http.host":
                return "http.host";

            case "port":
            case "http.port":
                return "http.port";

            case "root":
            case "folder":
            case "path":
            case "http.root":
                return "http.root";

            case "index":
            case "index-file":
            case "indexfile":
            case "http.index":
                return "http.index";

            case "spa":
            case "http.spa":
                return "http.spa";

            case "auto-https":
            case "autohttps":
            case "https":
            case "http.autohttps":
                return "http.autoHttps";

            default:
                return null;
        }
    }

    private String normalizeValue(String realKey, String value) {
        String clean = value == null ? "" : value.trim();

        if (realKey.equals("http.enabled")
                || realKey.equals("http.spa")
                || realKey.equals("http.autoHttps")) {
            return normalizeBoolean(clean);
        }

        if (realKey.equals("http.host") && clean.isBlank()) {
            return "127.0.0.1";
        }

        if (realKey.equals("http.port")) {
            int port = parseNumber(clean, 8080);

            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid HTTP port.");
            }

            return String.valueOf(port);
        }

        if (realKey.equals("http.index") && clean.isBlank()) {
            return "index.html";
        }

        if (realKey.equals("http.root") && clean.isBlank()) {
            return "/home/container/www";
        }

        return clean;
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

    private int parseNumber(String value, int defaultValue) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void printSetHelp() {
        System.out.println(YELLOW + "Valid HTTP keys:" + RESET);
        System.out.println(".mjt http set enabled true");
        System.out.println(".mjt http set host 127.0.0.1");
        System.out.println(".mjt http set port 8080");
        System.out.println(".mjt http set root /home/container/www");
        System.out.println(".mjt http set index index.html");
        System.out.println(".mjt http set spa true");
        System.out.println(".mjt http set auto-https true");
    }
}
