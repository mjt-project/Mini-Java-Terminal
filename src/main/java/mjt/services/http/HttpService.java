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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final ExecutorService executor = Executors.newFixedThreadPool(32);
    private final Map<String, HttpServer> servers = new LinkedHashMap<>();

    public HttpService(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
    }

    public synchronized void start() {
        if (!stateStore.getBoolean("http.enabled", true)) {
            System.out.println(YELLOW + "[HTTP] Service is disabled. Use: .mjt http set enabled true" + RESET);
            return;
        }

        List<String> sites = getSiteNames();

        if (sites.isEmpty()) {
            sites.add("main");
        }

        boolean startedAny = false;

        for (String siteName : sites) {
            if (stateStore.getBoolean(siteKey(siteName, "enabled"), true)) {
                startedAny |= startSiteInternal(siteName, false);
            }
        }

        if (!startedAny && !servers.isEmpty()) {
            System.out.println(YELLOW + "[HTTP] All enabled sites are already running." + RESET);
        }
    }

    public synchronized void stop() {
        if (servers.isEmpty()) {
            System.out.println(YELLOW + "[HTTP] No HTTP site is running." + RESET);
            return;
        }

        for (String siteName : new ArrayList<>(servers.keySet())) {
            stopSiteInternal(siteName, false);
        }

        System.out.println(YELLOW + "[HTTP] All HTTP sites stopped." + RESET);
    }

    public synchronized void startSite(String siteName) {
        String clean = normalizeSiteName(siteName);

        if (clean.equalsIgnoreCase("all")) {
            start();
            return;
        }

        if (clean.isBlank()) {
            System.out.println(RED + "[HTTP] Invalid site name." + RESET);
            return;
        }

        startSiteInternal(clean, true);
    }

    public synchronized void stopSite(String siteName) {
        String clean = normalizeSiteName(siteName);

        if (clean.equalsIgnoreCase("all")) {
            stop();
            return;
        }

        stopSiteInternal(clean, true);
    }

    public synchronized void restartSite(String siteName) {
        String clean = normalizeSiteName(siteName);

        if (clean.equalsIgnoreCase("all")) {
            stop();
            start();
            return;
        }

        stopSiteInternal(clean, false);
        startSiteInternal(clean, true);
    }

    public void status() {
        System.out.println(CYAN + "[HTTP STATUS]" + RESET);
        System.out.println("Enabled    : " + stateStore.get("http.enabled", "true"));
        System.out.println("Sites      : " + String.join(",", getSiteNames()));
        System.out.println("Running    : " + (servers.isEmpty() ? "none" : String.join(",", servers.keySet())));
        System.out.println("Auto HTTPS : " + stateStore.get("http.autoHttps", "true"));
        System.out.println();

        for (String siteName : getSiteNames()) {
            printSiteLine(siteName);
        }
    }

    public void showConfig() {
        status();
    }

    public void listSites() {
        System.out.println(CYAN + "[HTTP SITES]" + RESET);

        List<String> sites = getSiteNames();

        if (sites.isEmpty()) {
            System.out.println("No sites configured.");
            return;
        }

        for (String siteName : sites) {
            printSiteLine(siteName);
        }
    }

    public void showSite(String siteName) {
        String clean = normalizeSiteName(siteName);

        if (clean.isBlank()) {
            System.out.println(RED + "[HTTP] Invalid site name." + RESET);
            return;
        }

        System.out.println(CYAN + "[HTTP SITE] " + clean + RESET);
        System.out.println("Running : " + servers.containsKey(clean));
        System.out.println("Enabled : " + stateStore.get(siteKey(clean, "enabled"), "false"));
        System.out.println("Host    : " + getSiteHost(clean));
        System.out.println("Port    : " + getSitePort(clean));
        System.out.println("Root    : " + getSiteRoot(clean));
        System.out.println("Index   : " + getSiteIndex(clean));
        System.out.println("SPA     : " + getSiteSpa(clean));
    }

    public void addSite(String raw) throws IOException {
        String[] parts = raw.trim().split("\\s+", 4);

        if (parts.length < 4) {
            System.out.println(RED + "Usage: .mjt http site add <name> <host> <port> <root>" + RESET);
            System.out.println("Example: .mjt http site add docs 127.0.0.1 8082 /home/container/server/website/www/docs");
            return;
        }

        addSite(parts[0], parts[1], parts[2], parts[3]);
    }

    public void addSite(String name, String host, String portText, String root) throws IOException {
        String siteName = normalizeSiteName(name);

        if (siteName.isBlank()) {
            System.out.println(RED + "[HTTP] Invalid site name." + RESET);
            return;
        }

        int port = parseNumber(portText, -1);

        if (port <= 0 || port > 65535) {
            System.out.println(RED + "[HTTP] Invalid port." + RESET);
            return;
        }

        List<String> sites = getSiteNames();

        if (!sites.contains(siteName)) {
            sites.add(siteName);
            saveSiteNames(sites);
        }

        stateStore.set(siteKey(siteName, "enabled"), "true");
        stateStore.set(siteKey(siteName, "host"), host == null || host.isBlank() ? "127.0.0.1" : host.trim());
        stateStore.set(siteKey(siteName, "port"), String.valueOf(port));
        stateStore.set(siteKey(siteName, "root"), root.trim());
        stateStore.set(siteKey(siteName, "index"), "index.html");
        stateStore.set(siteKey(siteName, "spa"), "false");

        Files.createDirectories(Paths.get(root).toAbsolutePath().normalize());

        System.out.println(GREEN + "[HTTP] Added site: " + siteName + " -> " + host + ":" + port + RESET);
        logService.write("[HTTP SITE ADD] " + siteName + " " + host + ":" + port + " " + root + "\n");
    }

    public void removeSite(String siteName) throws IOException {
        String clean = normalizeSiteName(siteName);

        if (clean.equals("main")) {
            System.out.println(RED + "[HTTP] The main site cannot be removed. Disable it instead." + RESET);
            return;
        }

        stopSiteInternal(clean, false);

        List<String> sites = getSiteNames();
        sites.remove(clean);
        saveSiteNames(sites);

        stateStore.remove(siteKey(clean, "enabled"));
        stateStore.remove(siteKey(clean, "host"));
        stateStore.remove(siteKey(clean, "port"));
        stateStore.remove(siteKey(clean, "root"));
        stateStore.remove(siteKey(clean, "index"));
        stateStore.remove(siteKey(clean, "spa"));

        System.out.println(GREEN + "[HTTP] Removed site: " + clean + RESET);
        logService.write("[HTTP SITE REMOVE] " + clean + "\n");
    }

    public void setSiteConfig(String raw) throws IOException {
        String[] parts = raw.trim().split("\\s+", 3);

        if (parts.length < 3) {
            System.out.println(RED + "Usage: .mjt http site set <name> <key> <value>" + RESET);
            return;
        }

        String siteName = normalizeSiteName(parts[0]);
        String key = parts[1].trim().toLowerCase(Locale.ROOT);
        String value = parts[2].trim();

        if (siteName.isBlank()) {
            System.out.println(RED + "[HTTP] Invalid site name." + RESET);
            return;
        }

        String realKey = normalizeSiteConfigKey(siteName, key);

        if (realKey == null) {
            System.out.println(RED + "[HTTP] Invalid site key: " + key + RESET);
            System.out.println("Valid keys: enabled, host, port, root, index, spa");
            return;
        }

        if (realKey.endsWith(".port")) {
            int port = parseNumber(value, -1);

            if (port <= 0 || port > 65535) {
                System.out.println(RED + "[HTTP] Invalid port." + RESET);
                return;
            }

            value = String.valueOf(port);
        }

        if (realKey.endsWith(".enabled") || realKey.endsWith(".spa")) {
            value = normalizeBoolean(value);
        }

        stateStore.set(realKey, value);
        System.out.println(GREEN + "[HTTP] Saved " + realKey + " = " + value + RESET);
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

    private boolean startSiteInternal(String siteName, boolean printIfAlreadyRunning) {
        String clean = normalizeSiteName(siteName);

        if (servers.containsKey(clean)) {
            if (printIfAlreadyRunning) {
                System.out.println(YELLOW + "[HTTP] Site already running: " + clean + RESET);
            }
            return false;
        }

        String host = getSiteHost(clean);
        int port = getSitePort(clean);
        String root = getSiteRoot(clean);
        String index = getSiteIndex(clean);
        boolean spa = getSiteSpa(clean);

        try {
            Files.createDirectories(Paths.get(root).toAbsolutePath().normalize());

            HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
            server.createContext("/", exchange -> handleRequest(exchange, clean, root, index, spa));
            server.setExecutor(executor);
            server.start();
            servers.put(clean, server);

            System.out.println(GREEN + "==================================================" + RESET);
            System.out.println(GREEN + " HTTP Site: " + clean + RESET);
            System.out.println(GREEN + "==================================================" + RESET);
            System.out.println(CYAN + " Local HTTP : " + host + ":" + port + RESET);
            System.out.println(" Root       : " + root);
            System.out.println(" Index      : " + index);
            System.out.println(" SPA mode   : " + spa);
            System.out.println(YELLOW + " Note       : Use Cloudflare Tunnel for public HTTPS." + RESET);
            System.out.println();

            logService.write("[HTTP SITE START] " + clean + " " + host + ":" + port + "\n");
            return true;

        } catch (Exception e) {
            System.out.println(RED + "[HTTP] Start error for site " + clean + ": " + e.getMessage() + RESET);

            try {
                logService.write("[HTTP SITE START ERROR] " + clean + " " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }

            return false;
        }
    }

    private boolean stopSiteInternal(String siteName, boolean printIfNotRunning) {
        String clean = normalizeSiteName(siteName);
        HttpServer server = servers.remove(clean);

        if (server == null) {
            if (printIfNotRunning) {
                System.out.println(YELLOW + "[HTTP] Site is not running: " + clean + RESET);
            }
            return false;
        }

        server.stop(0);
        System.out.println(YELLOW + "[HTTP] Site stopped: " + clean + RESET);

        try {
            logService.write("[HTTP SITE STOP] " + clean + "\n");
        } catch (IOException ignored) {
        }

        return true;
    }

    private void handleRequest(HttpExchange exchange, String siteName, String root, String indexFileName, boolean spaFallback)
            throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if (!method.equals("GET") && !method.equals("HEAD")) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        Path webRoot = Paths.get(root)
                .toAbsolutePath()
                .normalize();

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
        headers.set("X-MJT-Site", siteName);

        if (stateStore.getBoolean("http.autoHttps", true)) {
            headers.set("X-MJT-Auto-HTTPS", "cloudflare-tunnel");
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
            logService.write("[HTTP " + siteName + "] " + method + " " + exchange.getRequestURI() + " -> " + targetFile + "\n");
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

    private List<String> getSiteNames() {
        String raw = stateStore.get("http.sites", "main").trim();
        List<String> sites = new ArrayList<>();

        for (String item : raw.split(",")) {
            String clean = normalizeSiteName(item);

            if (!clean.isBlank() && !sites.contains(clean)) {
                sites.add(clean);
            }
        }

        return sites;
    }

    private void saveSiteNames(List<String> sites) throws IOException {
        stateStore.set("http.sites", String.join(",", sites));
    }

    private String siteKey(String siteName, String key) {
        return "http.site." + normalizeSiteName(siteName) + "." + key;
    }

    private String getSiteHost(String siteName) {
        return stateStore.get(siteKey(siteName, "host"), stateStore.get("http.host", "127.0.0.1")).trim();
    }

    private int getSitePort(String siteName) {
        int fallback = stateStore.getInt("http.port", 8081);
        return stateStore.getInt(siteKey(siteName, "port"), fallback);
    }

    private String getSiteRoot(String siteName) {
        return stateStore.get(siteKey(siteName, "root"), stateStore.get("http.root", "/home/container/server/website/www/main")).trim();
    }

    private String getSiteIndex(String siteName) {
        return stateStore.get(siteKey(siteName, "index"), stateStore.get("http.index", "index.html")).trim();
    }

    private boolean getSiteSpa(String siteName) {
        return stateStore.getBoolean(siteKey(siteName, "spa"), stateStore.getBoolean("http.spa", false));
    }

    private void printSiteLine(String siteName) {
        System.out.println("  " + siteName
                + " | running=" + servers.containsKey(siteName)
                + " | enabled=" + stateStore.get(siteKey(siteName, "enabled"), "false")
                + " | " + getSiteHost(siteName) + ":" + getSitePort(siteName)
                + " | root=" + getSiteRoot(siteName));
    }

    private String normalizeSiteConfigKey(String siteName, String key) {
        switch (key.toLowerCase(Locale.ROOT)) {
            case "enabled":
            case "enable":
                return siteKey(siteName, "enabled");
            case "host":
            case "bind":
                return siteKey(siteName, "host");
            case "port":
                return siteKey(siteName, "port");
            case "root":
            case "folder":
            case "path":
                return siteKey(siteName, "root");
            case "index":
                return siteKey(siteName, "index");
            case "spa":
                return siteKey(siteName, "spa");
            default:
                return null;
        }
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
            case "sites":
            case "http.sites":
                return "http.sites";
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
            int port = parseNumber(clean, 8081);

            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid HTTP port.");
            }

            return String.valueOf(port);
        }

        if (realKey.equals("http.index") && clean.isBlank()) {
            return "index.html";
        }

        if (realKey.equals("http.root") && clean.isBlank()) {
            return "/home/container/server/website/www/main";
        }

        return clean;
    }

    private String normalizeSiteName(String name) {
        if (name == null) {
            return "";
        }

        String clean = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        clean = clean.replaceAll("-+", "-");

        if (clean.equals("-") || clean.equals("_")) {
            return "";
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
        System.out.println(".mjt http set sites main,docs,panel");
        System.out.println(".mjt http set auto-https true");
        System.out.println(".mjt http site add docs 127.0.0.1 8082 /home/container/server/website/www/docs");
        System.out.println(".mjt http site set docs spa true");
    }
}
