package main.java.mjt.system;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

public class StateStore {
    private static final String APP_FILE = "app.properties";
    private static final String SSH_FILE = "ssh.properties";
    private static final String GATEWAY_FILE = "gateway.properties";
    private static final String WEB_FILE = "web.properties";
    private static final String CLOUDFLARE_FILE = "cloudflare.properties";

    private final Path configDir;
    private final Map<String, Properties> stores = new LinkedHashMap<>();

    private boolean firstRun = false;

    public StateStore(Path configPath) throws IOException {
        this.configDir = configPath.toAbsolutePath().normalize();
        load();
    }

    public synchronized void load() throws IOException {
        stores.clear();

        boolean createdConfigDir = false;

        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
            createdConfigDir = true;
        }

        if (!Files.isDirectory(configDir)) {
            throw new IOException("Config path is not a directory: " + configDir);
        }

        firstRun = createdConfigDir;

        loadConfigFile(APP_FILE, defaultAppConfig(), "Mini Java Terminal App Config");
        loadConfigFile(SSH_FILE, defaultSshConfig(), "Mini Java Terminal SSH/SFTP Config");
        loadConfigFile(GATEWAY_FILE, defaultGatewayConfig(), "Mini Java Terminal Gateway Config");
        loadConfigFile(WEB_FILE, defaultWebConfig(), "Mini Java Terminal Web Config");
        loadConfigFile(CLOUDFLARE_FILE, defaultCloudflareConfig(), "Mini Java Terminal Cloudflare Config");

        createReadmeIfMissing();
        migrateLegacyTerminalStateIfNeeded();
        migrateLegacyGatewayHttpConfigIfNeeded();
    }

    public synchronized void save() throws IOException {
        for (String fileName : stores.keySet()) {
            saveFile(fileName, commentFor(fileName));
        }
    }

    public synchronized String get(String key) {
        return get(key, "");
    }

    public synchronized String get(String key, String defaultValue) {
        String fileName = fileNameForKey(key);
        Properties properties = stores.get(fileName);

        if (properties == null) {
            return defaultValue;
        }

        return properties.getProperty(key, defaultValue);
    }

    public synchronized int getInt(String key, int defaultValue) {
        String value = get(key);

        if (value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public synchronized boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);

        if (value.isBlank()) {
            return defaultValue;
        }

        return value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("on");
    }

    public synchronized void set(String key, String value) throws IOException {
        String fileName = fileNameForKey(key);

        Properties properties = stores.computeIfAbsent(fileName, ignored -> new Properties());
        properties.setProperty(key, value);

        saveFile(fileName, commentFor(fileName));
    }

    public synchronized void remove(String key) throws IOException {
        String fileName = fileNameForKey(key);
        Properties properties = stores.get(fileName);

        if (properties == null) {
            return;
        }

        properties.remove(key);
        saveFile(fileName, commentFor(fileName));
    }

    public synchronized boolean has(String key) {
        String fileName = fileNameForKey(key);
        Properties properties = stores.get(fileName);

        return properties != null && properties.containsKey(key);
    }

    public synchronized Set<String> keys() {
        Set<String> keys = new TreeSet<>();

        for (Properties properties : stores.values()) {
            keys.addAll(properties.stringPropertyNames());
        }

        return keys;
    }

    public Path getStateFile() {
        return configDir.resolve(APP_FILE);
    }

    public Path getConfigDir() {
        return configDir;
    }

    public boolean isFirstRun() {
        return firstRun;
    }

    public String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String trimmed = value.trim();

        if (trimmed.length() <= 8) {
            return "********";
        }

        return trimmed.substring(0, 6)
                + "..."
                + trimmed.substring(trimmed.length() - 4);
    }

    private void loadConfigFile(
            String fileName,
            Properties defaults,
            String comment
    ) throws IOException {
        Path file = configDir.resolve(fileName);
        Properties properties = new Properties();

        boolean changed = false;

        if (Files.exists(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                properties.load(input);
            }
        } else {
            firstRun = true;
            changed = true;
        }

        for (String key : defaults.stringPropertyNames()) {
            if (!properties.containsKey(key)) {
                properties.setProperty(key, defaults.getProperty(key));
                changed = true;
            }
        }

        stores.put(fileName, properties);

        if (changed) {
            saveFile(fileName, comment);
        }
    }

    private void saveFile(String fileName, String comment) throws IOException {
        Path file = configDir.resolve(fileName);
        Properties properties = stores.computeIfAbsent(fileName, ignored -> new Properties());

        try (OutputStream output = Files.newOutputStream(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            properties.store(output, comment);
        }
    }

    private void createReadmeIfMissing() throws IOException {
        Path readme = configDir.resolve("README.txt");

        if (Files.exists(readme)) {
            return;
        }

        String content = """
                Mini Java Terminal Config Folder
                =================================

                app.properties
                  - App-level settings.

                ssh.properties
                  - SSH/SFTP server settings.
                  - ssh.terminal.mode supports:
                    real-tty = default terminal mode
                    basic    = fallback mode

                gateway.properties
                  - Gateway router public TCP and manual TCP route settings.
                  - Gateway forwards HTTP traffic to the separate HTTP service.

                web.properties
                  - HTTP and HTTPS service settings.
                  - http.host defaults to 127.0.0.1.
                  - http.port defaults to 8080.
                  - https.host defaults to 127.0.0.1.
                  - https.port defaults to 8443.

                cloudflare.properties
                  - Cloudflare DDNS settings.

                Notes:
                  - Do not share files containing passwords or tokens.
                  - Use mjt-config-show to view config files.
                  - Use mjt-config-reload after editing config files manually.
                """;

        Files.writeString(readme, content);
        firstRun = true;
    }

    private void migrateLegacyTerminalStateIfNeeded() throws IOException {
        if (getBoolean("app.migrated.terminal-state", false)) {
            return;
        }

        Path legacyFile = Path.of("terminal-state.properties").toAbsolutePath().normalize();

        if (!Files.exists(legacyFile) || Files.isDirectory(legacyFile)) {
            set("app.migrated.terminal-state", "true");
            return;
        }

        Properties legacy = new Properties();

        try (InputStream input = Files.newInputStream(legacyFile)) {
            legacy.load(input);
        }

        for (String key : legacy.stringPropertyNames()) {
            String value = legacy.getProperty(key, "");

            if (value == null || value.isBlank()) {
                continue;
            }

            if (!has(key) || get(key, "").isBlank()) {
                set(key, value);
            }
        }

        set("app.migrated.terminal-state", "true");
        set("app.legacy-state-file", legacyFile.toString());
    }


    private void migrateLegacyGatewayHttpConfigIfNeeded() throws IOException {
        if (getBoolean("app.migrated.gateway-http-split", false)) {
            return;
        }

        copyLegacyKeyIfPresent("gateway.http.enabled", "http.enabled");
        copyLegacyKeyIfPresent("gateway.http.root", "http.root");
        copyLegacyKeyIfPresent("gateway.http.index", "http.index");
        copyLegacyKeyIfPresent("gateway.http.spa", "http.spa");

        // Keep Gateway as router only. HTTP detection forwards to the local HTTP service.
        if (!has("gateway.route.http.enabled")) {
            set("gateway.route.http.enabled", "true");
        }
        if (!has("gateway.route.http.host")) {
            set("gateway.route.http.host", get("http.host", "127.0.0.1"));
        }
        if (!has("gateway.route.http.port")) {
            set("gateway.route.http.port", get("http.port", "8080"));
        }

        if (!has("gateway.route.https.enabled")) {
            set("gateway.route.https.enabled", "true");
        }
        if (!has("gateway.route.https.host")) {
            set("gateway.route.https.host", get("https.host", "127.0.0.1"));
        }
        if (!has("gateway.route.https.port")) {
            set("gateway.route.https.port", get("https.port", "8443"));
        }

        set("app.migrated.gateway-http-split", "true");
    }

    private void copyLegacyKeyIfPresent(String oldKey, String newKey) throws IOException {
        String oldValue = get(oldKey, "");

        if (oldValue == null || oldValue.isBlank()) {
            return;
        }

        if (!has(newKey) || get(newKey, "").isBlank()) {
            set(newKey, oldValue);
        }
    }

    private String fileNameForKey(String key) {
        String lower = key.toLowerCase().trim();

        if (lower.startsWith("cloudflare.")) {
            return CLOUDFLARE_FILE;
        }

        if (lower.startsWith("ssh.") || lower.startsWith("sftp.")) {
            return SSH_FILE;
        }

        if (lower.startsWith("http.") || lower.startsWith("https.") || lower.startsWith("web.")) {
            return WEB_FILE;
        }

        // Legacy v2.4.x key compatibility only. New HTTP keys use http.*
        if (lower.startsWith("gateway.http.")) {
            return WEB_FILE;
        }

        if (lower.startsWith("gateway.")) {
            return GATEWAY_FILE;
        }

        return APP_FILE;
    }

    private String commentFor(String fileName) {
        switch (fileName) {
            case SSH_FILE:
                return "Mini Java Terminal SSH/SFTP Config";

            case GATEWAY_FILE:
                return "Mini Java Terminal Gateway Config";

            case WEB_FILE:
                return "Mini Java Terminal Web Config";

            case CLOUDFLARE_FILE:
                return "Mini Java Terminal Cloudflare Config";

            case APP_FILE:
            default:
                return "Mini Java Terminal App Config";
        }
    }

    private Properties defaultAppConfig() {
        Properties properties = new Properties();

        properties.setProperty("app.name", "Mini Java Terminal");
        properties.setProperty("app.version", BuildInfo.VERSION);
        properties.setProperty("app.first-run", "true");
        properties.setProperty("app.migrated.terminal-state", "false");
        properties.setProperty("app.command.prefix", ".");
        properties.setProperty("app.prefix.show", ".");

        // KeepAlive bot defaults for offline-mode Minecraft servers
        properties.setProperty("bot.enabled", "false");
        properties.setProperty("bot.host", "127.0.0.1");
        properties.setProperty("bot.port", "25565");
        properties.setProperty("bot.username", "MJT_Renew");
        properties.setProperty("bot.reconnectSeconds", "30");
        properties.setProperty("bot.autoStartWithMinecraft", "true");
        properties.setProperty("bot.autoStopWithMinecraft", "true");
        return properties;
    }

    private Properties defaultSshConfig() {
        Properties properties = new Properties();

        properties.setProperty("ssh.enabled", "false");
        properties.setProperty("ssh.host", "127.0.0.1");
        properties.setProperty("ssh.port", "2022");
        properties.setProperty("ssh.username", "admin");
        properties.setProperty("ssh.password", "");
        properties.setProperty("ssh.root", "/home/container");

        
        properties.setProperty("# Terminal mode for SSH connections. Options: "," 'real-tty' or 'basic'");
        properties.setProperty("ssh.terminal.mode", "basic");
        properties.setProperty("ssh.terminal.notice", "true");

        return properties;
    }

    private Properties defaultGatewayConfig() {
        Properties properties = new Properties();

        properties.setProperty("gateway.enabled", "true");
        properties.setProperty("gateway.public.host", "0.0.0.0");
        properties.setProperty("gateway.public.port", "auto");

        // Gateway only routes/forwards HTTP/HTTPS to the separate local web services.
        properties.setProperty("gateway.route.http.enabled", "true");
        properties.setProperty("gateway.route.http.host", "127.0.0.1");
        properties.setProperty("gateway.route.http.port", "8080");
        properties.setProperty("gateway.route.https.enabled", "true");
        properties.setProperty("gateway.route.https.host", "127.0.0.1");
        properties.setProperty("gateway.route.https.port", "8443");

        properties.setProperty("gateway.ssh.enabled", "true");
        properties.setProperty("gateway.ssh.host", "127.0.0.1");
        properties.setProperty("gateway.ssh.port", "2022");

        properties.setProperty("gateway.tcp.enabled", "true");
        properties.setProperty("gateway.tcp.default", "close");
        properties.setProperty("gateway.tcp.routes", "");

        return properties;
    }

    private Properties defaultWebConfig() {
        Properties properties = new Properties();

        properties.setProperty("http.enabled", "true");
        properties.setProperty("http.host", "127.0.0.1");
        properties.setProperty("http.port", "8080");
        properties.setProperty("http.root", "/home/container/www");
        properties.setProperty("http.index", "index.html");
        properties.setProperty("http.spa", "false");
        properties.setProperty("http.autoHttps", "true");

        properties.setProperty("https.enabled", "false");
        properties.setProperty("https.host", "127.0.0.1");
        properties.setProperty("https.port", "8443");
        properties.setProperty("https.root", "/home/container/www");
        properties.setProperty("https.index", "index.html");
        properties.setProperty("https.spa", "false");
        properties.setProperty("https.keystore", "mjt-config/https.p12");
        properties.setProperty("https.keystore.password", "change-me");
        properties.setProperty("https.key.alias", "mjt");
        properties.setProperty("https.cert.cn", "localhost");

        return properties;
    }

    private Properties defaultCloudflareConfig() {
        Properties properties = new Properties();

        properties.setProperty("cloudflare.token", "");
        properties.setProperty("cloudflare.zone", "");
        properties.setProperty("cloudflare.name", "");
        properties.setProperty("cloudflare.proxied", "false");
        properties.setProperty("cloudflare.ttl", "120");
        properties.setProperty("cloudflare.interval", "300");

        return properties;
    }
}