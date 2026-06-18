package main.java.mjt.system;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

public class StateStore {
    private static final String APP_FILE = "core/app.properties";
    private static final String WORKSPACES_FILE = "core/workspaces.properties";
    private static final String SSH_FILE = "services/ssh/ssh.properties";
    private static final String GATEWAY_FILE = "services/gateway/gateway.properties";
    private static final String TCP_FILE = "services/tcp/tcp-routes.properties";
    private static final String HTTP_FILE = "services/http/http.properties";
    private static final String HTTP_SITES_FILE = "services/http/sites/sites.properties";
    private static final String CLOUDFLARE_ACCOUNT_FILE = "services/cloudflare/account.properties";
    private static final String CLOUDFLARE_DDNS_FILE = "services/cloudflare/ddns-public-ipv4/ddns.properties";
    private static final String CLOUDFLARE_TUNNEL_FILE = "services/cloudflare/tunnel/tunnel.properties";
    private static final String MINECRAFT_FILE = "services/minecraft/minecraft.properties";
    private static final String PANEL_FILE = "services/panel/panel.properties";
    private static final String SYSTEM_DOWNLOAD_FILE = "system/downloads/downloads.properties";
    private static final String BOT_FILE = "services/bot/keepalive.properties";

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

        createServiceFolders();

        loadConfigFile(APP_FILE, defaultAppConfig(), "Mini Java Terminal App Config");
        loadConfigFile(WORKSPACES_FILE, defaultWorkspacesConfig(), "Mini Java Terminal Workspace Registry");
        loadConfigFile(SSH_FILE, defaultSshConfig(), "Mini Java Terminal SSH/SFTP Config");
        loadConfigFile(GATEWAY_FILE, defaultGatewayConfig(), "Mini Java Terminal Gateway Config");
        loadConfigFile(TCP_FILE, defaultTcpConfig(), "Mini Java Terminal TCP Routes Config");
        loadConfigFile(HTTP_FILE, defaultHttpConfig(), "Mini Java Terminal HTTP Config");
        loadConfigFile(HTTP_SITES_FILE, defaultHttpSitesConfig(), "Mini Java Terminal HTTP Sites Config");
        loadConfigFile(CLOUDFLARE_ACCOUNT_FILE, defaultCloudflareAccountConfig(), "Mini Java Terminal Cloudflare Account Config");
        loadConfigFile(CLOUDFLARE_DDNS_FILE, defaultCloudflareDdnsConfig(), "Mini Java Terminal Cloudflare DDNS Config");
        loadConfigFile(CLOUDFLARE_TUNNEL_FILE, defaultCloudflareTunnelConfig(), "Mini Java Terminal Cloudflare Tunnel Config");
        loadConfigFile(MINECRAFT_FILE, defaultMinecraftConfig(), "Mini Java Terminal Minecraft Config");
        loadConfigFile(PANEL_FILE, defaultPanelConfig(), "Mini Java Terminal Control Panel Config");
        loadConfigFile(SYSTEM_DOWNLOAD_FILE, defaultSystemDownloadConfig(), "Mini Java Terminal System Download Config");
        loadConfigFile(BOT_FILE, defaultBotConfig(), "Mini Java Terminal KeepAlive Bot Config");

        createReadmeIfMissing();
        migrateLegacyConfigIfNeeded();
        migrateLegacyGatewayHttpConfigIfNeeded();
        createDefaultWebFoldersIfMissing();
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
                || value.equalsIgnoreCase("on")
                || value.equalsIgnoreCase("enabled");
    }

    public synchronized void set(String key, String value) throws IOException {
        String fileName = fileNameForKey(key);

        Properties properties = stores.computeIfAbsent(fileName, ignored -> new Properties());
        properties.setProperty(key, value == null ? "" : value);

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

    private void createServiceFolders() throws IOException {
        Files.createDirectories(configDir.resolve("core"));
        Files.createDirectories(configDir.resolve("services/ssh"));
        Files.createDirectories(configDir.resolve("services/cloudflare/ddns-public-ipv4"));
        Files.createDirectories(configDir.resolve("services/cloudflare/tunnel"));
        Files.createDirectories(configDir.resolve("services/http/sites"));
        Files.createDirectories(configDir.resolve("services/tcp"));
        Files.createDirectories(configDir.resolve("services/gateway"));
        Files.createDirectories(configDir.resolve("services/minecraft"));
        Files.createDirectories(configDir.resolve("services/panel"));
        Files.createDirectories(configDir.resolve("services/bot"));
        Files.createDirectories(configDir.resolve("services/https"));
        Files.createDirectories(configDir.resolve("system/downloads/cloudflared"));
        Files.createDirectories(configDir.resolve("system/downloads/panel"));
        Files.createDirectories(configDir.resolve("system/downloads/minecraft"));
        Files.createDirectories(configDir.resolve("system/tasks"));
        Files.createDirectories(configDir.resolve("panel/static"));
        Files.createDirectories(configDir.resolve("panel/assets"));

        Files.createDirectories(websiteRoot("main"));
        Files.createDirectories(websiteRoot("docs"));
        Files.createDirectories(websiteRoot("panel"));
        Files.createDirectories(guestWebsiteRootBase());
        Files.createDirectories(serverRoot().resolve("Minecraft/Velocity"));
        Files.createDirectories(serverRoot().resolve("Minecraft/smp"));
        Files.createDirectories(serverRoot().resolve("Minecraft/lobby"));

        Files.createDirectories(configDir.resolve("runtime/pids"));
        Files.createDirectories(configDir.resolve("runtime/cache"));
        Files.createDirectories(configDir.resolve("logs"));
    }

    private void loadConfigFile(
            String fileName,
            Properties defaults,
            String comment
    ) throws IOException {
        Path file = configDir.resolve(fileName);
        Properties properties = new Properties();

        boolean changed = false;

        Files.createDirectories(file.getParent());

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

        Files.createDirectories(file.getParent());

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

                MJT/ is the main runtime/config folder for Mini Java Terminal v2.6.0+.

                core/
                  - App-level settings.

                services/ssh/
                  - SSH/SFTP server settings.

                services/cloudflare/
                  account.properties
                  ddns-public-ipv4/ddns.properties
                  tunnel/tunnel.properties

                services/http/
                  http.properties
                  sites/sites.properties

                services/tcp/
                  tcp-routes.properties

                services/gateway/
                  gateway.properties

                services/minecraft/
                  minecraft.properties

                services/bot/
                  keepalive.properties

                system/downloads/
                  - Per-task helper binaries and installer metadata.
                  - cloudflared/ stores the Cloudflare Tunnel binary installed by MJT.

                Website content is stored outside MJT:
                  /home/container/server/website/www/main
                  /home/container/server/website/www/docs
                  /home/container/server/website/www/panel
                  /home/container/server/website/www/guest

                Minecraft workspaces are stored outside MJT:
                  /home/container/server/Minecraft/Velocity
                  /home/container/server/Minecraft/smp
                  /home/container/server/Minecraft/lobby

                runtime/
                  - Runtime cache and pid data.

                logs/
                  - Runtime logs.

                Notes:
                  - Do not share files containing passwords or tokens.
                  - Gateway is a TCP router/forwarder only.
                  - HTTP sites are local services.
                  - Cloudflare Tunnel is the recommended public HTTPS path for web.
                """;

        Files.writeString(readme, content);
        firstRun = true;
    }

    private void createDefaultWebFoldersIfMissing() throws IOException {
        createIndexIfMissing(websiteRoot("main"), "MJT Main Site", "This is the default main website.");
        createIndexIfMissing(websiteRoot("docs"), "MJT Docs Site", "This is the default docs website.");
        createIndexIfMissing(websiteRoot("panel"), "MJT Panel Site", "This is the default panel website.");
    }

    private void createIndexIfMissing(Path root, String title, String text) throws IOException {
        Path index = root.resolve("index.html");

        Files.createDirectories(root);

        if (Files.exists(index)) {
            return;
        }

        String html = """
                <!DOCTYPE html>
                <html lang=\"en\">
                <head>
                    <meta charset=\"UTF-8\">
                    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
                    <title>%s</title>
                </head>
                <body>
                    <h1>%s</h1>
                    <p>%s</p>
                    <p><b>Mini Java Terminal v%s</b></p>
                </body>
                </html>
                """.formatted(title, title, text, BuildInfo.VERSION);

        Files.writeString(index, html);
    }

    private void migrateLegacyConfigIfNeeded() throws IOException {
        if (getBoolean("app.migrated.legacy-config", false)) {
            return;
        }

        importLegacyFlatFile(Path.of("terminal-state.properties").toAbsolutePath().normalize());

        Path oldConfigDir = Path.of("mjt-config").toAbsolutePath().normalize();
        if (Files.exists(oldConfigDir) && Files.isDirectory(oldConfigDir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(oldConfigDir, "*.properties")) {
                for (Path file : stream) {
                    importLegacyFlatFile(file);
                }
            }
        }

        set("app.migrated.legacy-config", "true");
    }

    private void importLegacyFlatFile(Path legacyFile) throws IOException {
        if (!Files.exists(legacyFile) || Files.isDirectory(legacyFile)) {
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
    }

    private void migrateLegacyGatewayHttpConfigIfNeeded() throws IOException {
        if (getBoolean("app.migrated.gateway-http-split", false)) {
            return;
        }

        copyLegacyKeyIfPresent("gateway.http.enabled", "http.enabled");
        copyLegacyKeyIfPresent("gateway.http.root", "http.site.main.root");
        copyLegacyKeyIfPresent("gateway.http.index", "http.site.main.index");
        copyLegacyKeyIfPresent("gateway.http.spa", "http.site.main.spa");

        if (!has("gateway.route.http.enabled")) {
            set("gateway.route.http.enabled", "false");
        }
        if (!has("gateway.route.http.host")) {
            set("gateway.route.http.host", get("http.site.main.host", "127.0.0.1"));
        }
        if (!has("gateway.route.http.port")) {
            set("gateway.route.http.port", get("http.site.main.port", "8080"));
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

        if (lower.startsWith("workspace.")) {
            return WORKSPACES_FILE;
        }

        if (lower.startsWith("tunnel.")) {
            return CLOUDFLARE_TUNNEL_FILE;
        }

        if (lower.startsWith("cloudflare.account.")) {
            return CLOUDFLARE_ACCOUNT_FILE;
        }

        if (lower.startsWith("cloudflare.")) {
            return CLOUDFLARE_DDNS_FILE;
        }

        if (lower.startsWith("ssh.") || lower.startsWith("sftp.")) {
            return SSH_FILE;
        }

        if (lower.startsWith("website.")) {
            return HTTP_SITES_FILE;
        }

        if (lower.startsWith("http.site.")) {
            return HTTP_SITES_FILE;
        }

        if (lower.startsWith("http.") || lower.startsWith("https.") || lower.startsWith("web.")) {
            return HTTP_FILE;
        }

        if (lower.startsWith("gateway.tcp.")) {
            return TCP_FILE;
        }

        if (lower.startsWith("gateway.")) {
            return GATEWAY_FILE;
        }

        if (lower.startsWith("minecraft.")) {
            return MINECRAFT_FILE;
        }

        if (lower.startsWith("panel.")) {
            return PANEL_FILE;
        }

        if (lower.startsWith("system.download.")) {
            return SYSTEM_DOWNLOAD_FILE;
        }

        if (lower.startsWith("bot.")) {
            return BOT_FILE;
        }

        return APP_FILE;
    }

    private String commentFor(String fileName) {
        switch (fileName) {
            case WORKSPACES_FILE:
                return "Mini Java Terminal Workspace Registry";
            case SSH_FILE:
                return "Mini Java Terminal SSH/SFTP Config";
            case GATEWAY_FILE:
                return "Mini Java Terminal Gateway Router Config";
            case TCP_FILE:
                return "Mini Java Terminal TCP Routes Config";
            case HTTP_FILE:
                return "Mini Java Terminal HTTP/HTTPS Service Config";
            case HTTP_SITES_FILE:
                return "Mini Java Terminal HTTP Sites Config";
            case CLOUDFLARE_ACCOUNT_FILE:
                return "Mini Java Terminal Cloudflare Account Config";
            case CLOUDFLARE_DDNS_FILE:
                return "Mini Java Terminal Cloudflare DDNS Config";
            case CLOUDFLARE_TUNNEL_FILE:
                return "Mini Java Terminal Cloudflare Tunnel Config";
            case MINECRAFT_FILE:
                return "Mini Java Terminal Minecraft Config";
            case PANEL_FILE:
                return "Mini Java Terminal Control Panel Config";
            case SYSTEM_DOWNLOAD_FILE:
                return "Mini Java Terminal System Download Config";
            case BOT_FILE:
                return "Mini Java Terminal KeepAlive Bot Config";
            case APP_FILE:
            default:
                return "Mini Java Terminal App Config";
        }
    }

    private Path serverRoot() {
        return Path.of("/home/container/server").toAbsolutePath().normalize();
    }

    private Path websiteRoot(String siteName) {
        String clean = siteName == null || siteName.isBlank() ? "main" : siteName.trim();
        return serverRoot().resolve("website/www").resolve(clean).toAbsolutePath().normalize();
    }

    private Path guestWebsiteRootBase() {
        return serverRoot().resolve("website/www/guest").toAbsolutePath().normalize();
    }

    private Properties defaultAppConfig() {
        Properties properties = new Properties();

        properties.setProperty("app.name", "Mini Java Terminal");
        properties.setProperty("app.version", BuildInfo.VERSION);
        properties.setProperty("app.first-run", "true");
        properties.setProperty("app.migrated.legacy-config", "false");
        properties.setProperty("app.migrated.gateway-http-split", "false");
        properties.setProperty("app.command.prefix", ".");
        properties.setProperty("app.prefix.show", ".");
        properties.setProperty("app.workspace.enabled", "true");

        // External static frontend for the MJT Control Panel.
        // Keep this in core/app.properties so the frontend source can be updated
        // without touching service-specific panel runtime config.
        properties.setProperty("app.panel.frontend.source", "github");
        properties.setProperty("app.panel.frontend.repo", "mjt-project/mjt-panel-web");
        properties.setProperty("app.panel.frontend.tag", "0.0.1");
        properties.setProperty("app.panel.frontend.autoUrlFromTag", "true");
        properties.setProperty("app.panel.frontend.url", "https://github.com/mjt-project/mjt-panel-web/archive/refs/tags/0.0.1.zip");
        properties.setProperty("app.panel.frontend.installed.version", "");
        properties.setProperty("app.panel.frontend.installed.at", "");
        properties.setProperty("app.panel.frontend.installed.url", "");

        // Minecraft installer sources. Keep remote source URLs in app.properties so
        // they can be changed without rebuilding the Java core.
        properties.setProperty("app.minecraft.installer.papermc.base", "https://fill.papermc.io/v3/projects");
        properties.setProperty("app.minecraft.installer.purpur.base", "https://api.purpurmc.org/v2/purpur");
        properties.setProperty("app.minecraft.installer.userAgent", "MiniJavaTerminal/" + BuildInfo.VERSION + " (https://github.com/mjt-project/Mini-Java-Terminal)");
        return properties;
    }

    private Properties defaultWorkspacesConfig() {
        Properties properties = new Properties();
        Path root = serverRoot();
        properties.setProperty("workspace.root", root.toString());
        properties.setProperty("workspace.files.maxReadBytes", "1048576");
        properties.setProperty("workspace.ids", "server-root,velocity,smp,lobby");

        properties.setProperty("workspace.server-root.type", "folder");
        properties.setProperty("workspace.server-root.name", "Server Root");
        properties.setProperty("workspace.server-root.path", root.toString());
        properties.setProperty("workspace.server-root.start", "");
        properties.setProperty("workspace.server-root.stop", "");
        properties.setProperty("workspace.server-root.port", "");
        properties.setProperty("workspace.server-root.linkedMinecraftProfile", "");
        properties.setProperty("workspace.server-root.readOnly", "false");

        addMinecraftWorkspaceDefaults(properties, "velocity", "minecraft-velocity", "Velocity Proxy", root.resolve("Minecraft/Velocity"), "25565");
        addMinecraftWorkspaceDefaults(properties, "smp", "minecraft-paper", "SMP", root.resolve("Minecraft/smp"), "25566");
        addMinecraftWorkspaceDefaults(properties, "lobby", "minecraft-paper", "Lobby", root.resolve("Minecraft/lobby"), "25567");
        return properties;
    }

    private void addMinecraftWorkspaceDefaults(Properties properties, String id, String type, String name, Path path, String port) {
        String base = "workspace." + id + ".";
        properties.setProperty(base + "type", type);
        properties.setProperty(base + "name", name);
        properties.setProperty(base + "path", path.toString());
        properties.setProperty(base + "start", "bash start.sh");
        properties.setProperty(base + "stop", id.equals("velocity") ? "shutdown" : "stop");
        properties.setProperty(base + "port", port);
        properties.setProperty(base + "linkedMinecraftProfile", id);
        properties.setProperty(base + "readOnly", "false");
    }

    private Properties defaultSshConfig() {
        Properties properties = new Properties();

        properties.setProperty("ssh.enabled", "false");
        properties.setProperty("ssh.host", "127.0.0.1");
        properties.setProperty("ssh.port", "2022");
        properties.setProperty("ssh.username", "admin");
        properties.setProperty("ssh.password", "");
        properties.setProperty("ssh.root", "/home/container");
        properties.setProperty("ssh.terminal.mode", "basic");
        properties.setProperty("ssh.terminal.notice", "true");
        return properties;
    }

    private Properties defaultGatewayConfig() {
        Properties properties = new Properties();

        properties.setProperty("gateway.enabled", "true");
        properties.setProperty("gateway.public.host", "0.0.0.0");
        properties.setProperty("gateway.public.port", "auto");
        properties.setProperty("gateway.route.http.enabled", "false");
        properties.setProperty("gateway.route.http.host", "127.0.0.1");
        properties.setProperty("gateway.route.http.port", "8081");
        properties.setProperty("gateway.route.https.enabled", "false");
        properties.setProperty("gateway.route.https.host", "127.0.0.1");
        properties.setProperty("gateway.route.https.port", "8443");
        properties.setProperty("gateway.ssh.enabled", "true");
        properties.setProperty("gateway.ssh.host", "127.0.0.1");
        properties.setProperty("gateway.ssh.port", "2022");
        return properties;
    }

    private Properties defaultTcpConfig() {
        Properties properties = new Properties();

        properties.setProperty("gateway.tcp.enabled", "true");
        properties.setProperty("gateway.tcp.default", "close");
        properties.setProperty("gateway.tcp.routes", "");
        return properties;
    }

    private Properties defaultHttpConfig() {
        Properties properties = new Properties();

        properties.setProperty("http.enabled", "true");
        properties.setProperty("http.sites", "main");
        properties.setProperty("http.host", "127.0.0.1");
        properties.setProperty("http.port", "8081");
        properties.setProperty("http.root", websiteRoot("main").toString());
        properties.setProperty("http.index", "index.html");
        properties.setProperty("http.spa", "false");
        properties.setProperty("http.autoHttps", "true");

        properties.setProperty("https.enabled", "false");
        properties.setProperty("https.host", "127.0.0.1");
        properties.setProperty("https.port", "8443");
        properties.setProperty("https.root", websiteRoot("main").toString());
        properties.setProperty("https.index", "index.html");
        properties.setProperty("https.spa", "false");
        properties.setProperty("https.keystore", configDir.resolve("services/https/https.p12").toString());
        properties.setProperty("https.keystore.password", "change-me");
        properties.setProperty("https.key.alias", "mjt");
        properties.setProperty("https.cert.cn", "localhost");
        return properties;
    }

    private Properties defaultHttpSitesConfig() {
        Properties properties = new Properties();

        properties.setProperty("http.site.main.enabled", "true");
        properties.setProperty("http.site.main.host", "127.0.0.1");
        properties.setProperty("http.site.main.port", "8081");
        properties.setProperty("http.site.main.root", websiteRoot("main").toString());
        properties.setProperty("http.site.main.index", "index.html");
        properties.setProperty("http.site.main.spa", "false");

        properties.setProperty("http.site.docs.enabled", "false");
        properties.setProperty("http.site.docs.host", "127.0.0.1");
        properties.setProperty("http.site.docs.port", "8082");
        properties.setProperty("http.site.docs.root", websiteRoot("docs").toString());
        properties.setProperty("http.site.docs.index", "index.html");
        properties.setProperty("http.site.docs.spa", "true");

        properties.setProperty("http.site.panel.enabled", "false");
        properties.setProperty("http.site.panel.host", "127.0.0.1");
        properties.setProperty("http.site.panel.port", "8083");
        properties.setProperty("http.site.panel.root", websiteRoot("panel").toString());
        properties.setProperty("http.site.panel.index", "index.html");
        properties.setProperty("http.site.panel.spa", "false");

        properties.setProperty("website.guests", "");
        properties.setProperty("website.guest.nextPort", "8091");
        properties.setProperty("website.guest.rootBase", guestWebsiteRootBase().toString());
        return properties;
    }

    private Properties defaultCloudflareAccountConfig() {
        Properties properties = new Properties();
        properties.setProperty("cloudflare.account.id", "");
        properties.setProperty("cloudflare.account.email", "");
        return properties;
    }

    private Properties defaultCloudflareDdnsConfig() {
        Properties properties = new Properties();

        properties.setProperty("cloudflare.apiToken", "");
        properties.setProperty("cloudflare.zoneId", "");
        properties.setProperty("cloudflare.recordId", "");
        properties.setProperty("cloudflare.recordName", "");
        properties.setProperty("cloudflare.proxied", "false");
        properties.setProperty("cloudflare.ttl", "120");
        properties.setProperty("cloudflare.intervalSeconds", "300");
        properties.setProperty("cloudflare.lastIp", "");

        // Legacy compatibility keys.
        properties.setProperty("cloudflare.token", "");
        properties.setProperty("cloudflare.zone", "");
        properties.setProperty("cloudflare.name", "");
        properties.setProperty("cloudflare.interval", "300");
        return properties;
    }

    private Properties defaultCloudflareTunnelConfig() {
        Properties properties = new Properties();

        properties.setProperty("tunnel.enabled", "true");
        properties.setProperty("tunnel.provider", "cloudflare");
        properties.setProperty("tunnel.mode", "quick");
        properties.setProperty("tunnel.autoStart", "false");
        properties.setProperty("tunnel.cloudflared.path", "cloudflared");
        properties.setProperty("tunnel.token", "");
        properties.setProperty("tunnel.name", "");
        properties.setProperty("tunnel.id", "");
        properties.setProperty("tunnel.credentialsFile", "");
        properties.setProperty("tunnel.configFile", configDir.resolve("services/cloudflare/tunnel/config.yml").toString());
        properties.setProperty("tunnel.local.url", "http://127.0.0.1:8081");
        properties.setProperty("tunnel.routes", "main");
        properties.setProperty("tunnel.route.main.enabled", "true");
        properties.setProperty("tunnel.route.main.hostname", "");
        properties.setProperty("tunnel.route.main.service", "http://127.0.0.1:8081");
        properties.setProperty("tunnel.route.docs.enabled", "false");
        properties.setProperty("tunnel.route.docs.hostname", "");
        properties.setProperty("tunnel.route.docs.service", "http://127.0.0.1:8082");
        properties.setProperty("tunnel.route.panel.enabled", "false");
        properties.setProperty("tunnel.route.panel.hostname", "");
        properties.setProperty("tunnel.route.panel.service", "http://127.0.0.1:8083");
        return properties;
    }

    private Properties defaultMinecraftConfig() {
        Properties properties = new Properties();
        properties.setProperty("minecraft.start-command", "bash start.sh");
        properties.setProperty("minecraft.stop-command", "stop");
        properties.setProperty("minecraft.host", "127.0.0.1");
        properties.setProperty("minecraft.port", "25565");
        properties.setProperty("minecraft.workdir", serverRoot().resolve("Minecraft/smp").toString());
        properties.setProperty("minecraft.active", "smp");
        properties.setProperty("minecraft.profiles", "velocity,smp,lobby");

        properties.setProperty("minecraft.profile.velocity.type", "velocity");
        properties.setProperty("minecraft.profile.velocity.workdir", serverRoot().resolve("Minecraft/Velocity").toString());
        properties.setProperty("minecraft.profile.velocity.command", "bash start.sh");
        properties.setProperty("minecraft.profile.velocity.stop", "shutdown");
        properties.setProperty("minecraft.profile.velocity.port", "25565");

        properties.setProperty("minecraft.profile.smp.type", "paper");
        properties.setProperty("minecraft.profile.smp.workdir", serverRoot().resolve("Minecraft/smp").toString());
        properties.setProperty("minecraft.profile.smp.command", "bash start.sh");
        properties.setProperty("minecraft.profile.smp.stop", "stop");
        properties.setProperty("minecraft.profile.smp.port", "25566");

        properties.setProperty("minecraft.profile.lobby.type", "paper");
        properties.setProperty("minecraft.profile.lobby.workdir", serverRoot().resolve("Minecraft/lobby").toString());
        properties.setProperty("minecraft.profile.lobby.command", "bash start.sh");
        properties.setProperty("minecraft.profile.lobby.stop", "stop");
        properties.setProperty("minecraft.profile.lobby.port", "25567");

        properties.setProperty("minecraft.installer.defaultXms", "1G");
        properties.setProperty("minecraft.installer.defaultXmx", "2G");
        properties.setProperty("minecraft.installer.velocityXms", "128M");
        properties.setProperty("minecraft.installer.velocityXmx", "512M");
        properties.setProperty("minecraft.installer.serverJar", "minecraft.jar");
        properties.setProperty("minecraft.installer.velocityJar", "Velocity.jar");
        properties.setProperty("minecraft.installer.autoAcceptEula", "false");
        return properties;
    }

    private Properties defaultPanelConfig() {
        Properties properties = new Properties();
        properties.setProperty("panel.enabled", "false");
        properties.setProperty("panel.autoStart", "true");
        properties.setProperty("panel.host", "127.0.0.1");
        properties.setProperty("panel.port", "9090");
        properties.setProperty("panel.auth.enabled", "true");
        properties.setProperty("panel.auth.token", "");
        properties.setProperty("panel.public.mode", "local-only");
        properties.setProperty("panel.theme", "dark");
        properties.setProperty("panel.static.root", configDir.resolve("panel/static").toAbsolutePath().normalize().toString());
        return properties;
    }

    private Properties defaultSystemDownloadConfig() {
        Properties properties = new Properties();

        Path cloudflaredDir = configDir.resolve("system/downloads/cloudflared").toAbsolutePath().normalize();
        Path minecraftDir = configDir.resolve("system/downloads/minecraft").toAbsolutePath().normalize();

        properties.setProperty("system.download.cloudflared.dir", cloudflaredDir.toString());
        properties.setProperty("system.download.cloudflared.path", cloudflaredDir.resolve("cloudflared").toString());
        properties.setProperty("system.download.cloudflared.url.override", "");
        properties.setProperty("system.download.cloudflared.url", "");
        properties.setProperty("system.download.cloudflared.asset", "");
        properties.setProperty("system.download.cloudflared.os", "");
        properties.setProperty("system.download.cloudflared.arch", "");
        properties.setProperty("system.download.cloudflared.version", "");
        properties.setProperty("system.download.cloudflared.status", "never");

        properties.setProperty("system.download.minecraft.dir", minecraftDir.toString());
        properties.setProperty("system.download.minecraft.lastSoftware", "");
        properties.setProperty("system.download.minecraft.lastVersion", "");
        properties.setProperty("system.download.minecraft.lastBuild", "");
        properties.setProperty("system.download.minecraft.lastUrl", "");
        properties.setProperty("system.download.minecraft.status", "never");
        return properties;
    }

    private Properties defaultBotConfig() {
        Properties properties = new Properties();
        properties.setProperty("bot.enabled", "false");
        properties.setProperty("bot.host", "127.0.0.1");
        properties.setProperty("bot.port", "25565");
        properties.setProperty("bot.username", "MJT_Renew");
        properties.setProperty("bot.reconnectSeconds", "30");
        properties.setProperty("bot.autoStartWithMinecraft", "true");
        properties.setProperty("bot.autoStopWithMinecraft", "true");
        return properties;
    }
}
