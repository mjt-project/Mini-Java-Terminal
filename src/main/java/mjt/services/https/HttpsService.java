package main.java.mjt.services.https;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

public class HttpsService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final StateStore stateStore;
    private final LogService logService;
    private final ExecutorService executor = Executors.newFixedThreadPool(16);

    private HttpsServer httpsServer;
    private volatile boolean running = false;

    public HttpsService(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
    }

    public synchronized void start() {
        if (running) {
            System.out.println(YELLOW + "[HTTPS] Service is already running." + RESET);
            return;
        }

        if (!stateStore.getBoolean("https.enabled", false)) {
            System.out.println(YELLOW + "[HTTPS] Service is disabled. Use: .mjt https set enabled true" + RESET);
            return;
        }

        String host = stateStore.get("https.host", "127.0.0.1").trim();
        int port = stateStore.getInt("https.port", 8443);
        String keyStorePath = stateStore.get("https.keystore", "mjt-config/https.p12").trim();
        String keyStorePassword = stateStore.get("https.keystore.password", "change-me");

        if (host.isBlank()) {
            host = "127.0.0.1";
        }

        if (port <= 0 || port > 65535) {
            port = 8443;
        }

        try {
            Path keyStoreFile = Paths.get(keyStorePath).toAbsolutePath().normalize();

            if (!Files.exists(keyStoreFile)) {
                System.out.println(RED + "[HTTPS] Keystore not found: " + keyStoreFile + RESET);
                System.out.println(YELLOW + "Run: .mjt https cert self-signed" + RESET);
                return;
            }

            SSLContext sslContext = createSslContext(keyStoreFile, keyStorePassword);

            httpsServer = HttpsServer.create(new InetSocketAddress(host, port), 0);
            httpsServer.setHttpsConfigurator(new HttpsConfigurator(sslContext));
            httpsServer.createContext("/", this::handleRequest);
            httpsServer.setExecutor(executor);
            httpsServer.start();
            running = true;

            System.out.println(GREEN + "==================================================" + RESET);
            System.out.println(GREEN + " HTTPS Service" + RESET);
            System.out.println(GREEN + "==================================================" + RESET);
            System.out.println(CYAN + " Local HTTPS : " + host + ":" + port + RESET);
            System.out.println(" Root        : " + getRoot());
            System.out.println(" Index       : " + getIndexFileName());
            System.out.println(" SPA mode    : " + stateStore.get("https.spa", stateStore.get("http.spa", "false")));
            System.out.println(" Keystore    : " + keyStoreFile);
            System.out.println(YELLOW + " Note        : Self-signed certificates will show a browser warning." + RESET);
            System.out.println();

            logService.write("[HTTPS START] " + host + ":" + port + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[HTTPS] Start error: " + e.getMessage() + RESET);

            try {
                logService.write("[HTTPS START ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized void stop() {
        if (!running || httpsServer == null) {
            System.out.println(YELLOW + "[HTTPS] Service is not running." + RESET);
            return;
        }

        httpsServer.stop(0);
        httpsServer = null;
        running = false;

        System.out.println(YELLOW + "[HTTPS] Service stopped." + RESET);

        try {
            logService.write("[HTTPS STOP]\n");
        } catch (IOException ignored) {
        }
    }

    public void status() {
        System.out.println(CYAN + "[HTTPS STATUS]" + RESET);
        System.out.println("Running    : " + running);
        System.out.println("Enabled    : " + stateStore.get("https.enabled", "false"));
        System.out.println("Host       : " + stateStore.get("https.host", "127.0.0.1"));
        System.out.println("Port       : " + stateStore.get("https.port", "8443"));
        System.out.println("Root       : " + getRoot());
        System.out.println("Index      : " + getIndexFileName());
        System.out.println("SPA        : " + stateStore.get("https.spa", stateStore.get("http.spa", "false")));
        System.out.println("Keystore   : " + stateStore.get("https.keystore", "mjt-config/https.p12"));
        System.out.println("Password   : " + stateStore.maskSecret(stateStore.get("https.keystore.password", "")));
    }

    public void showConfig() {
        status();
    }

    public void setConfig(String key, String value) throws IOException {
        String realKey = normalizeKey(key);

        if (realKey == null) {
            System.out.println(RED + "[HTTPS] Invalid key: " + key + RESET);
            printSetHelp();
            return;
        }

        value = normalizeValue(realKey, value);
        stateStore.set(realKey, value);

        if (realKey.equals("https.keystore.password")) {
            System.out.println(GREEN + "[HTTPS] Saved " + realKey + " = " + stateStore.maskSecret(value) + RESET);
        } else {
            System.out.println(GREEN + "[HTTPS] Saved " + realKey + " = " + value + RESET);
        }

        logService.write("[HTTPS SET] " + realKey + "\n");
    }

    public void generateSelfSignedCertificate() {
        String keyStorePath = stateStore.get("https.keystore", "mjt-config/https.p12").trim();
        String keyStorePassword = stateStore.get("https.keystore.password", "change-me").trim();
        String alias = stateStore.get("https.key.alias", "mjt").trim();
        String commonName = stateStore.get("https.cert.cn", "localhost").trim();

        if (keyStorePassword.length() < 6) {
            System.out.println(RED + "[HTTPS] Keystore password must be at least 6 characters for keytool." + RESET);
            return;
        }

        if (alias.isBlank()) {
            alias = "mjt";
        }

        if (commonName.isBlank()) {
            commonName = "localhost";
        }

        try {
            Path keyStoreFile = Paths.get(keyStorePath).toAbsolutePath().normalize();
            Files.createDirectories(keyStoreFile.getParent());

            if (Files.exists(keyStoreFile)) {
                System.out.println(YELLOW + "[HTTPS] Keystore already exists: " + keyStoreFile + RESET);
                System.out.println(YELLOW + "Delete it first if you want to regenerate it." + RESET);
                return;
            }

            List<String> command = new ArrayList<>();
            command.add(resolveKeytoolCommand());
            command.add("-genkeypair");
            command.add("-alias");
            command.add(alias);
            command.add("-keyalg");
            command.add("RSA");
            command.add("-keysize");
            command.add("2048");
            command.add("-validity");
            command.add("3650");
            command.add("-storetype");
            command.add("PKCS12");
            command.add("-keystore");
            command.add(keyStoreFile.toString());
            command.add("-storepass");
            command.add(keyStorePassword);
            command.add("-keypass");
            command.add(keyStorePassword);
            command.add("-dname");
            command.add("CN=" + commonName);
            command.add("-noprompt");

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            int exitCode = process.waitFor();

            if (exitCode == 0 && Files.exists(keyStoreFile)) {
                System.out.println(GREEN + "[HTTPS] Self-signed certificate created." + RESET);
                System.out.println("Keystore: " + keyStoreFile);
                System.out.println(YELLOW + "Browser note: self-signed certificates will show a warning." + RESET);
                logService.write("[HTTPS CERT SELF-SIGNED] " + keyStoreFile + "\n");
            } else {
                System.out.println(RED + "[HTTPS] keytool failed. Exit code: " + exitCode + RESET);
                System.out.println(output);
                logService.write("[HTTPS CERT ERROR] " + output + "\n");
            }

        } catch (Exception e) {
            System.out.println(RED + "[HTTPS] Certificate generation error: " + e.getMessage() + RESET);
            try {
                logService.write("[HTTPS CERT ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private SSLContext createSslContext(Path keyStoreFile, String password) throws Exception {
        char[] passwordChars = password.toCharArray();

        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStoreFile)) {
            keyStore.load(input, passwordChars);
        }

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, passwordChars);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
        return sslContext;
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if (!method.equals("GET") && !method.equals("HEAD")) {
            sendText(exchange, 405, "Method Not Allowed");
            return;
        }

        Path webRoot = Paths.get(getRoot()).toAbsolutePath().normalize();
        String indexFileName = getIndexFileName();
        boolean spaFallback = stateStore.getBoolean("https.spa", stateStore.getBoolean("http.spa", false));

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
        headers.set("Strict-Transport-Security", "max-age=31536000");

        exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : fileBytes.length);

        if (!method.equals("HEAD")) {
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(fileBytes);
            }
        } else {
            exchange.close();
        }

        try {
            logService.write("[HTTPS] " + method + " " + exchange.getRequestURI() + " -> " + targetFile + "\n");
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
            case "https.enabled":
                return "https.enabled";

            case "host":
            case "bind":
            case "https.host":
                return "https.host";

            case "port":
            case "https.port":
                return "https.port";

            case "root":
            case "folder":
            case "webroot":
            case "https.root":
                return "https.root";

            case "index":
            case "index-file":
            case "https.index":
                return "https.index";

            case "spa":
            case "https.spa":
                return "https.spa";

            case "keystore":
            case "store":
            case "https.keystore":
                return "https.keystore";

            case "password":
            case "pass":
            case "keystore-password":
            case "https.keystore.password":
                return "https.keystore.password";

            case "alias":
            case "key-alias":
            case "https.key.alias":
                return "https.key.alias";

            case "cn":
            case "common-name":
            case "cert-cn":
            case "https.cert.cn":
                return "https.cert.cn";

            default:
                return null;
        }
    }

    private String normalizeValue(String realKey, String value) {
        String clean = value == null ? "" : value.trim();

        if (realKey.equals("https.enabled") || realKey.equals("https.spa")) {
            return normalizeBoolean(clean);
        }

        if (realKey.equals("https.port")) {
            int port = parseNumber(clean, 8443);
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid HTTPS port.");
            }
            return String.valueOf(port);
        }

        if (realKey.equals("https.host") && clean.isBlank()) {
            return "127.0.0.1";
        }

        if (realKey.equals("https.keystore") && clean.isBlank()) {
            return "mjt-config/https.p12";
        }

        if (realKey.equals("https.keystore.password") && clean.isBlank()) {
            return "change-me";
        }

        if (realKey.equals("https.key.alias") && clean.isBlank()) {
            return "mjt";
        }

        if (realKey.equals("https.cert.cn") && clean.isBlank()) {
            return "localhost";
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

    private String getRoot() {
        return stateStore.get("https.root", stateStore.get("http.root", "/home/container/www")).trim();
    }

    private String getIndexFileName() {
        return stateStore.get("https.index", stateStore.get("http.index", "index.html")).trim();
    }

    private String resolveKeytoolCommand() {
        String javaHome = System.getProperty("java.home", "");
        if (!javaHome.isBlank()) {
            Path keytool = Paths.get(javaHome, "bin", isWindowsHost() ? "keytool.exe" : "keytool");
            if (Files.isExecutable(keytool)) {
                return keytool.toString();
            }
        }
        return "keytool";
    }

    private boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private void printSetHelp() {
        System.out.println(YELLOW + "Valid HTTPS keys:" + RESET);
        System.out.println(".mjt https set enabled true");
        System.out.println(".mjt https set host 127.0.0.1");
        System.out.println(".mjt https set port 8443");
        System.out.println(".mjt https set root /home/container/www");
        System.out.println(".mjt https set keystore /home/container/mjt-config/https.p12");
        System.out.println(".mjt https set password change-me");
        System.out.println(".mjt https set cn localhost");
        System.out.println(".mjt https cert self-signed");
    }
}
