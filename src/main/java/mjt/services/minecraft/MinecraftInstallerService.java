package main.java.mjt.services.minecraft;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

public class MinecraftInstallerService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static final Pattern JSON_STRING = Pattern.compile("\\\"([^\\\"\\\\]*(?:\\\\.[^\\\"\\\\]*)*)\\\"");
    private static final Pattern VERSION_ARRAY = Pattern.compile("\\\"versions\\\"\\s*:\\s*(?:\\{.*?\\[([^\\]]+)\\]|\\[([^\\]]+)\\])", Pattern.DOTALL);
    private static final Pattern SERVER_DEFAULT_URL = Pattern.compile("\\\"server:default\\\"\\s*:\\s*\\{[^}]*\\\"url\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"", Pattern.DOTALL);
    private static final Pattern ANY_DOWNLOAD_URL = Pattern.compile("\\\"url\\\"\\s*:\\s*\\\"(https?://[^\\\"]+)\\\"");
    private static final Pattern BUILD_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*([0-9]+)");

    private final StateStore stateStore;
    private final LogService logService;

    public MinecraftInstallerService(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
    }

    public void printConfig() {
        System.out.println(CYAN + "[MINECRAFT INSTALLER]" + RESET);
        System.out.println("PaperMC base : " + stateStore.get("app.minecraft.installer.papermc.base", "https://fill.papermc.io/v3/projects"));
        System.out.println("Purpur base  : " + stateStore.get("app.minecraft.installer.purpur.base", "https://api.purpurmc.org/v2/purpur"));
        System.out.println("User-Agent   : " + userAgent());
        System.out.println("Download dir : " + downloadDir());
        System.out.println("Default RAM  : " + stateStore.get("minecraft.installer.defaultXms", "1G") + " / " + stateStore.get("minecraft.installer.defaultXmx", "2G"));
        System.out.println("Auto EULA    : " + stateStore.get("minecraft.installer.autoAcceptEula", "false"));
        System.out.println();
        System.out.println("Supported software: velocity, paper, purpur");
        System.out.println("Example: .mjt minecraft install paper smp latest --accept-eula");
        System.out.println("Example: .mjt minecraft install velocity velocity latest");
    }

    public InstallResult install(String rawSoftware, String rawProfile, String rawVersion, String rawBuild, boolean acceptEula, boolean force) throws IOException {
        String software = normalizeSoftware(rawSoftware);
        if (!isSupportedSoftware(software)) {
            throw new IOException("Unsupported Minecraft software: " + rawSoftware + " (supported: velocity, paper, purpur)");
        }

        String profile = normalizeProfile(rawProfile);
        if (profile.isBlank()) {
            profile = software.equals("velocity") ? "velocity" : "smp";
        }

        String version = rawVersion == null || rawVersion.isBlank() ? "latest" : rawVersion.trim();
        String build = rawBuild == null || rawBuild.isBlank() ? "latest" : rawBuild.trim();
        boolean autoAccept = acceptEula || stateStore.getBoolean("minecraft.installer.autoAcceptEula", false);

        InstallPlan plan = resolveDownload(software, version, build);
        Path workdir = getProfileWorkdir(profile, software);
        Files.createDirectories(workdir);

        String jarName = defaultJarName(software);
        Path jar = workdir.resolve(jarName).toAbsolutePath().normalize();
        if (Files.exists(jar) && !force) {
            backupExistingJar(jar);
        }

        Path downloadTarget = downloadDir().resolve(software + "-" + plan.version + "-" + plan.build + ".jar").toAbsolutePath().normalize();
        Files.createDirectories(downloadTarget.getParent());

        System.out.println(CYAN + "[Minecraft Installer] Software : " + software + RESET);
        System.out.println(CYAN + "[Minecraft Installer] Version  : " + plan.version + RESET);
        System.out.println(CYAN + "[Minecraft Installer] Build    : " + plan.build + RESET);
        System.out.println(CYAN + "[Minecraft Installer] URL      : " + plan.url + RESET);
        System.out.println(CYAN + "[Minecraft Installer] Workdir  : " + workdir + RESET);

        downloadFile(plan.url, downloadTarget);
        Files.copy(downloadTarget, jar, StandardCopyOption.REPLACE_EXISTING);

        createStartScript(software, workdir, jarName);
        createDefaultServerFiles(software, profile, workdir, autoAccept);
        saveProfileConfig(software, profile, workdir, jarName, plan);

        String message = "Installed " + software + " " + plan.version + " build " + plan.build + " as profile " + profile;
        System.out.println(GREEN + "[Minecraft Installer] " + message + RESET);
        if ((software.equals("paper") || software.equals("purpur")) && !autoAccept) {
            System.out.println(YELLOW + "[Minecraft Installer] EULA not auto-accepted. Edit eula.txt before first start, or reinstall with --accept-eula." + RESET);
        }

        logService.write("[MINECRAFT INSTALL] " + message + " | " + workdir + " | " + plan.url + "\n");
        return new InstallResult(true, software, profile, plan.version, plan.build, jar.toString(), workdir.toString(), plan.url, autoAccept);
    }

    public String providersJson() {
        return "{\"ok\":true,\"providers\":["
                + "{\"id\":\"velocity\",\"name\":\"Velocity\",\"type\":\"proxy\"},"
                + "{\"id\":\"paper\",\"name\":\"Paper\",\"type\":\"server\"},"
                + "{\"id\":\"purpur\",\"name\":\"Purpur\",\"type\":\"server\"}"
                + "]}";
    }

    private InstallPlan resolveDownload(String software, String version, String build) throws IOException {
        if (software.equals("paper") || software.equals("velocity")) {
            return resolvePaperMc(software, version, build);
        }
        if (software.equals("purpur")) {
            return resolvePurpur(version, build);
        }
        throw new IOException("Unsupported software: " + software);
    }

    private InstallPlan resolvePaperMc(String project, String version, String build) throws IOException {
        String base = trimTrailingSlash(stateStore.get("app.minecraft.installer.papermc.base", "https://fill.papermc.io/v3/projects"));
        String cleanVersion = version == null || version.isBlank() || version.equalsIgnoreCase("latest")
                ? latestPaperMcVersion(base, project)
                : version.trim();

        String buildJson = readUrl(base + "/" + url(project) + "/versions/" + url(cleanVersion) + "/builds");
        String selectedBlock;
        String buildId;

        if (build != null && !build.isBlank() && !build.equalsIgnoreCase("latest")) {
            String directJson = readUrl(base + "/" + url(project) + "/versions/" + url(cleanVersion) + "/builds/" + url(build.trim()));
            String url = extractDownloadUrl(directJson);
            return new InstallPlan(project, cleanVersion, build.trim(), url);
        }

        selectedBlock = firstObjectContaining(buildJson, "\"channel\"", "STABLE");
        if (selectedBlock == null) {
            selectedBlock = firstJsonObject(buildJson);
        }
        if (selectedBlock == null) {
            throw new IOException("No builds found for " + project + " " + cleanVersion);
        }

        buildId = extractBuildId(selectedBlock);
        String downloadUrl = extractDownloadUrl(selectedBlock);
        if (downloadUrl.isBlank()) {
            downloadUrl = extractDownloadUrl(buildJson);
        }
        if (downloadUrl.isBlank()) {
            throw new IOException("Cannot find download URL for " + project + " " + cleanVersion);
        }
        if (buildId.isBlank()) {
            buildId = "latest";
        }
        return new InstallPlan(project, cleanVersion, buildId, downloadUrl);
    }

    private String latestPaperMcVersion(String base, String project) throws IOException {
        String json = readUrl(base + "/" + url(project));
        Matcher matcher = VERSION_ARRAY.matcher(json);
        List<String> versions = new ArrayList<>();
        if (matcher.find()) {
            String group = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            versions.addAll(extractStrings(group));
        }
        if (versions.isEmpty()) {
            versions.addAll(extractStrings(json));
        }
        return latestVersion(versions);
    }

    private InstallPlan resolvePurpur(String version, String build) throws IOException {
        String base = trimTrailingSlash(stateStore.get("app.minecraft.installer.purpur.base", "https://api.purpurmc.org/v2/purpur"));
        String cleanVersion = version == null || version.isBlank() || version.equalsIgnoreCase("latest")
                ? latestPurpurVersion(base)
                : version.trim();

        String buildText = build == null || build.isBlank() ? "latest" : build.trim();
        String url;
        String resolvedBuild = buildText;
        if (buildText.equalsIgnoreCase("latest")) {
            url = base + "/" + url(cleanVersion) + "/latest/download";
            try {
                String versionJson = readUrl(base + "/" + url(cleanVersion));
                resolvedBuild = latestPurpurBuild(versionJson);
            } catch (Exception ignored) {
                resolvedBuild = "latest";
            }
        } else {
            url = base + "/" + url(cleanVersion) + "/" + url(buildText) + "/download";
        }
        return new InstallPlan("purpur", cleanVersion, resolvedBuild, url);
    }

    private String latestPurpurVersion(String base) throws IOException {
        String json = readUrl(base);
        List<String> versions = new ArrayList<>();
        Matcher matcher = VERSION_ARRAY.matcher(json);
        if (matcher.find()) {
            String group = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            versions.addAll(extractStrings(group));
        }
        if (versions.isEmpty()) {
            versions.addAll(extractStrings(json));
        }
        return latestVersion(versions);
    }

    private String latestPurpurBuild(String json) {
        List<String> strings = extractStrings(json);
        String latest = "latest";
        for (String s : strings) {
            if (s.matches("[0-9]+")) {
                if (latest.equals("latest") || Integer.parseInt(s) > Integer.parseInt(latest)) {
                    latest = s;
                }
            }
        }
        return latest;
    }

    private String extractDownloadUrl(String json) throws IOException {
        Matcher serverDefault = SERVER_DEFAULT_URL.matcher(json);
        if (serverDefault.find()) {
            return unescapeJson(serverDefault.group(1));
        }
        Matcher any = ANY_DOWNLOAD_URL.matcher(json);
        if (any.find()) {
            return unescapeJson(any.group(1));
        }
        return "";
    }

    private String extractBuildId(String json) {
        Matcher matcher = BUILD_ID.matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String firstObjectContaining(String json, String key, String value) {
        int index = json.indexOf(key);
        while (index >= 0) {
            int objectStart = json.lastIndexOf('{', index);
            int objectEnd = findMatchingBrace(json, objectStart);
            if (objectStart >= 0 && objectEnd > objectStart) {
                String object = json.substring(objectStart, objectEnd + 1);
                if (object.contains(value)) {
                    return object;
                }
            }
            index = json.indexOf(key, index + key.length());
        }
        return null;
    }

    private String firstJsonObject(String json) {
        int start = json.indexOf('{');
        int end = findMatchingBrace(json, start);
        return start >= 0 && end > start ? json.substring(start, end + 1) : null;
    }

    private int findMatchingBrace(String json, int start) {
        if (start < 0 || start >= json.length() || json.charAt(start) != '{') return -1;
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;
            if (c == '{') depth++;
            if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private void downloadFile(String url, Path target) throws IOException {
        HttpURLConnection connection = openConnection(url, 0);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Download failed: HTTP " + status + " from " + url);
        }
        try (InputStream input = new BufferedInputStream(connection.getInputStream())) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            connection.disconnect();
        }
    }

    private String readUrl(String url) throws IOException {
        HttpURLConnection connection = openConnection(url, 0);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            throw new IOException("Request failed: HTTP " + status + " from " + url);
        }
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } finally {
            connection.disconnect();
        }
        return builder.toString();
    }

    private HttpURLConnection openConnection(String url, int redirectCount) throws IOException {
        if (redirectCount > 6) {
            throw new IOException("Too many redirects for " + url);
        }
        URL target = URI.create(url).toURL();
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("User-Agent", userAgent());
        int status = connection.getResponseCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
            String location = connection.getHeaderField("Location");
            connection.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException("Redirect without Location from " + url);
            }
            return openConnection(URI.create(url).resolve(location).toString(), redirectCount + 1);
        }
        return connection;
    }

    private void backupExistingJar(Path jar) throws IOException {
        if (!Files.exists(jar)) return;
        String stamp = String.valueOf(Instant.now().getEpochSecond());
        Path backup = jar.resolveSibling(jar.getFileName().toString() + ".old-" + stamp);
        Files.move(jar, backup, StandardCopyOption.REPLACE_EXISTING);
        System.out.println(YELLOW + "[Minecraft Installer] Existing jar backed up: " + backup.getFileName() + RESET);
    }

    private void createStartScript(String software, Path workdir, String jarName) throws IOException {
        String xms = stateStore.get("minecraft.installer.defaultXms", "1G").trim();
        String xmx = stateStore.get("minecraft.installer.defaultXmx", "2G").trim();
        if (software.equals("velocity")) {
            xms = stateStore.get("minecraft.installer.velocityXms", "128M").trim();
            xmx = stateStore.get("minecraft.installer.velocityXmx", "512M").trim();
        }
        String content = "#!/usr/bin/env bash\n"
                + "set -Eeuo pipefail\n"
                + "cd \"$(dirname \"$0\")\"\n"
                + "exec java -Xms" + xms + " -Xmx" + xmx + " -jar " + jarName + (software.equals("velocity") ? "" : " nogui") + "\n";
        Path script = workdir.resolve("start.sh");
        Files.writeString(script, content, StandardCharsets.UTF_8);
        try {
            script.toFile().setExecutable(true, false);
        } catch (Exception ignored) {
        }
    }

    private void createDefaultServerFiles(String software, String profile, Path workdir, boolean acceptEula) throws IOException {
        if (software.equals("paper") || software.equals("purpur")) {
            Path eula = workdir.resolve("eula.txt");
            if (!Files.exists(eula)) {
                Files.writeString(eula, "# Generated by Mini Java Terminal\n# Only set true if you agree to the Minecraft EULA.\neula=" + acceptEula + "\n", StandardCharsets.UTF_8);
            }
            Path serverProperties = workdir.resolve("server.properties");
            if (!Files.exists(serverProperties)) {
                String port = stateStore.get("minecraft.profile." + profile + ".port", software.equals("paper") ? "25566" : "25567");
                String text = "server-ip=127.0.0.1\n"
                        + "server-port=" + port + "\n"
                        + "online-mode=false\n"
                        + "motd=MJT " + software + " server\n";
                Files.writeString(serverProperties, text, StandardCharsets.UTF_8);
            }
        }
    }

    private void saveProfileConfig(String software, String profile, Path workdir, String jarName, InstallPlan plan) throws IOException {
        List<String> profiles = profileNames();
        if (!profiles.contains(profile)) {
            profiles.add(profile);
            stateStore.set("minecraft.profiles", String.join(",", profiles));
        }
        String base = "minecraft.profile." + profile + ".";
        stateStore.set(base + "type", software);
        stateStore.set(base + "software", software);
        stateStore.set(base + "workdir", workdir.toString());
        stateStore.set(base + "command", "bash start.sh");
        stateStore.set(base + "stop", software.equals("velocity") ? "shutdown" : "stop");
        if (!stateStore.has(base + "port") || stateStore.get(base + "port", "").isBlank()) {
            stateStore.set(base + "port", defaultPort(software, profile));
        }
        stateStore.set(base + "jar", jarName);
        stateStore.set(base + "version", plan.version);
        stateStore.set(base + "build", plan.build);
        stateStore.set(base + "downloadUrl", plan.url);
        stateStore.set(base + "installedAt", Instant.now().toString());
        stateStore.set("minecraft.active", profile);
    }

    private List<String> profileNames() {
        String raw = stateStore.get("minecraft.profiles", "velocity,smp,lobby").trim();
        List<String> result = new ArrayList<>();
        for (String item : raw.split(",")) {
            String clean = normalizeProfile(item);
            if (!clean.isBlank() && !result.contains(clean)) result.add(clean);
        }
        return result;
    }

    private Path getProfileWorkdir(String profile, String software) {
        String configured = stateStore.get("minecraft.profile." + profile + ".workdir", "").trim();
        if (!configured.isBlank()) return Paths.get(configured).toAbsolutePath().normalize();
        String folder = software.equals("velocity") ? "Velocity" : profile;
        return Paths.get("/home/container/server/Minecraft").resolve(folder).toAbsolutePath().normalize();
    }

    private String defaultJarName(String software) {
        if (software.equals("velocity")) return stateStore.get("minecraft.installer.velocityJar", "Velocity.jar");
        return stateStore.get("minecraft.installer.serverJar", "minecraft.jar");
    }

    private String defaultPort(String software, String profile) {
        if (software.equals("velocity")) return "25565";
        if (profile.equals("lobby")) return "25567";
        return "25566";
    }

    private Path downloadDir() {
        String dir = stateStore.get("system.download.minecraft.dir", "").trim();
        if (!dir.isBlank()) return Paths.get(dir).toAbsolutePath().normalize();
        return stateStore.getConfigDir().resolve("system/downloads/minecraft").toAbsolutePath().normalize();
    }

    private String userAgent() {
        String ua = stateStore.get("app.minecraft.installer.userAgent", "").trim();
        if (!ua.isBlank()) return ua;
        return "MiniJavaTerminal/" + main.java.mjt.system.BuildInfo.VERSION + " (https://github.com/mjt-project/Mini-Java-Terminal)";
    }

    private String normalizeSoftware(String value) {
        String clean = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (clean.equals("papermc")) return "paper";
        if (clean.equals("velocitymc")) return "velocity";
        if (clean.equals("purpurmc")) return "purpur";
        return clean.replaceAll("[^a-z0-9_-]", "");
    }

    private boolean isSupportedSoftware(String software) {
        return software.equals("velocity") || software.equals("paper") || software.equals("purpur");
    }

    private String normalizeProfile(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private List<String> extractStrings(String text) {
        List<String> result = new ArrayList<>();
        Matcher matcher = JSON_STRING.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String value = unescapeJson(matcher.group(1));
            if (!value.isBlank()) result.add(value);
        }
        return result;
    }

    private String latestVersion(List<String> versions) throws IOException {
        List<String> candidates = new ArrayList<>();
        for (String v : versions) {
            if (v.matches("[0-9]+(\\.[0-9]+){1,3}([-.][A-Za-z0-9]+)?")) {
                candidates.add(v);
            }
        }
        if (candidates.isEmpty()) {
            throw new IOException("Cannot resolve latest Minecraft version from API response.");
        }
        candidates.sort(new VersionComparator().reversed());
        return candidates.get(0);
    }

    private static final class VersionComparator implements Comparator<String> {
        @Override
        public int compare(String left, String right) {
            String[] a = left.split("[-.]");
            String[] b = right.split("[-.]");
            int max = Math.max(a.length, b.length);
            for (int i = 0; i < max; i++) {
                String av = i < a.length ? a[i] : "0";
                String bv = i < b.length ? b[i] : "0";
                int cmp;
                if (av.matches("[0-9]+") && bv.matches("[0-9]+")) {
                    cmp = Integer.compare(Integer.parseInt(av), Integer.parseInt(bv));
                } else {
                    cmp = av.compareToIgnoreCase(bv);
                }
                if (cmp != 0) return cmp;
            }
            return 0;
        }
    }

    private String url(String value) {
        return value.replace(" ", "%20");
    }

    private String trimTrailingSlash(String value) {
        String clean = value == null || value.isBlank() ? "" : value.trim();
        while (clean.endsWith("/")) clean = clean.substring(0, clean.length() - 1);
        return clean;
    }

    private String unescapeJson(String value) {
        return value == null ? "" : value.replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static final class InstallPlan {
        final String software;
        final String version;
        final String build;
        final String url;

        InstallPlan(String software, String version, String build, String url) {
            this.software = software;
            this.version = version;
            this.build = build;
            this.url = url;
        }
    }

    public static final class InstallResult {
        public final boolean ok;
        public final String software;
        public final String profile;
        public final String version;
        public final String build;
        public final String jar;
        public final String workdir;
        public final String url;
        public final boolean eulaAccepted;

        InstallResult(boolean ok, String software, String profile, String version, String build, String jar, String workdir, String url, boolean eulaAccepted) {
            this.ok = ok;
            this.software = software;
            this.profile = profile;
            this.version = version;
            this.build = build;
            this.jar = jar;
            this.workdir = workdir;
            this.url = url;
            this.eulaAccepted = eulaAccepted;
        }
    }
}
