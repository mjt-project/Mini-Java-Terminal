package main.java.mjt.services.panel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

public class PanelFrontendInstallerService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static final String DEFAULT_URL = "https://github.com/mjt-project/mjt-panel-web/archive/refs/tags/0.0.1.zip";

    private final StateStore stateStore;
    private final LogService logService;

    public PanelFrontendInstallerService(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
    }

    public void show() {
        Path staticRoot = getStaticRoot();
        System.out.println(CYAN + "[PANEL FRONTEND]" + RESET);
        System.out.println("URL              : " + getDownloadUrl());
        System.out.println("Repo             : " + stateStore.get("app.panel.frontend.repo", "mjt-project/mjt-panel-web"));
        System.out.println("Tag              : " + stateStore.get("app.panel.frontend.tag", "0.0.1"));
        System.out.println("Static root      : " + staticRoot);
        System.out.println("Installed        : " + Files.exists(staticRoot.resolve("index.html")));
        System.out.println("Installed version: " + stateStore.get("app.panel.frontend.installed.version", ""));
        System.out.println("Installed at     : " + stateStore.get("app.panel.frontend.installed.at", ""));
        System.out.println("Downloaded file  : " + getDownloadFile());
    }

    public void setUrl(String url) throws IOException {
        String clean = url == null ? "" : url.trim();
        if (clean.isBlank()) {
            System.out.println(RED + "[Panel Frontend] URL cannot be empty." + RESET);
            return;
        }
        stateStore.set("app.panel.frontend.url", clean);
        System.out.println(GREEN + "[Panel Frontend] Saved URL in core/app.properties" + RESET);
        System.out.println(clean);
    }

    public void setTag(String tag) throws IOException {
        String clean = tag == null ? "" : tag.trim();
        if (clean.isBlank()) {
            System.out.println(RED + "[Panel Frontend] Tag cannot be empty." + RESET);
            return;
        }
        stateStore.set("app.panel.frontend.tag", clean);
        if (stateStore.get("app.panel.frontend.url", "").isBlank()
                || stateStore.getBoolean("app.panel.frontend.autoUrlFromTag", true)) {
            stateStore.set("app.panel.frontend.url", "https://github.com/mjt-project/mjt-panel-web/archive/refs/tags/" + clean + ".zip");
        }
        System.out.println(GREEN + "[Panel Frontend] Saved tag in core/app.properties: " + clean + RESET);
    }

    public void install() {
        installOrUpdate("install");
    }

    public void update() {
        installOrUpdate("update");
    }

    private void installOrUpdate(String action) {
        Path downloaded = getDownloadFile();
        Path extractRoot = getCacheRoot().resolve("panel-" + action + "-" + UUID.randomUUID());
        Path staticRoot = getStaticRoot();

        try {
            Files.createDirectories(downloaded.getParent());
            Files.createDirectories(extractRoot);
            Files.createDirectories(staticRoot.getParent());

            String url = getDownloadUrl();
            System.out.println(CYAN + "[Panel Frontend] Downloading:" + RESET);
            System.out.println(url);
            download(url, downloaded);

            System.out.println(CYAN + "[Panel Frontend] Extracting zip..." + RESET);
            unzip(downloaded, extractRoot);

            Path sourceRoot = findFrontendRoot(extractRoot)
                    .orElseThrow(() -> new IOException("Cannot find index.html inside downloaded panel zip."));

            if (!Files.exists(sourceRoot.resolve("assets"))) {
                System.out.println(YELLOW + "[Panel Frontend] Warning: assets folder not found beside index.html." + RESET);
            }

            replaceStaticRoot(staticRoot, sourceRoot);

            String version = readVersion(staticRoot.resolve("panel.json"));
            stateStore.set("app.panel.frontend.installed.version", version);
            stateStore.set("app.panel.frontend.installed.at", Instant.now().toString());
            stateStore.set("app.panel.frontend.installed.url", url);

            System.out.println(GREEN + "[Panel Frontend] Installed successfully." + RESET);
            System.out.println("Static root: " + staticRoot);
            System.out.println("Version    : " + version);
            logService.write("[PANEL FRONTEND " + action.toUpperCase(Locale.ROOT) + "] " + url + " -> " + staticRoot + "\n");
        } catch (Exception e) {
            System.out.println(RED + "[Panel Frontend] " + action + " failed: " + e.getMessage() + RESET);
            try {
                logService.write("[PANEL FRONTEND ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        } finally {
            try {
                deleteRecursive(extractRoot);
            } catch (IOException ignored) {
            }
        }
    }

    private String getDownloadUrl() {
        String url = stateStore.get("app.panel.frontend.url", "").trim();
        return url.isBlank() ? DEFAULT_URL : url;
    }

    private Path getStaticRoot() {
        return Paths.get(stateStore.get("panel.static.root", stateStore.getConfigDir().resolve("panel/static").toString()))
                .toAbsolutePath()
                .normalize();
    }

    private Path getDownloadFile() {
        return stateStore.getConfigDir()
                .resolve("system/downloads/panel/mjt-panel-web.zip")
                .toAbsolutePath()
                .normalize();
    }

    private Path getCacheRoot() {
        return stateStore.getConfigDir()
                .resolve("runtime/cache")
                .toAbsolutePath()
                .normalize();
    }

    private void download(String rawUrl, Path output) throws IOException {
        URL url = URI.create(rawUrl).toURL();
        for (int redirects = 0; redirects < 8; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(60000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "MJT-Panel-Installer/1.0");
            int code = connection.getResponseCode();

            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = connection.getHeaderField("Location");
                if (location == null || location.isBlank()) {
                    throw new IOException("Redirect without Location header.");
                }
                url = URI.create(url.toString()).resolve(location).toURL();
                continue;
            }

            if (code < 200 || code >= 300) {
                throw new IOException("Download HTTP " + code + " from " + url);
            }

            try (InputStream input = connection.getInputStream();
                 OutputStream fileOutput = Files.newOutputStream(output)) {
                input.transferTo(fileOutput);
            }
            return;
        }
        throw new IOException("Too many redirects while downloading panel frontend.");
    }

    private void unzip(Path zipFile, Path targetDir) throws IOException {
        Path normalizedTarget = targetDir.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path output = normalizedTarget.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedTarget)) {
                    throw new IOException("Blocked unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private Optional<Path> findFrontendRoot(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equals("index.html"))
                    .map(Path::getParent)
                    .min(Comparator.comparingInt(path -> path.getNameCount()));
        }
    }

    private void replaceStaticRoot(Path staticRoot, Path sourceRoot) throws IOException {
        guardStaticRoot(staticRoot);
        if (Files.exists(staticRoot)) {
            deleteRecursive(staticRoot);
        }
        Files.createDirectories(staticRoot);
        copyRecursive(sourceRoot, staticRoot);
    }

    private void guardStaticRoot(Path staticRoot) throws IOException {
        Path normalized = staticRoot.toAbsolutePath().normalize();
        if (normalized.getNameCount() < 3) {
            throw new IOException("Refusing to replace unsafe static root: " + normalized);
        }
    }

    private void copyRecursive(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(dir);
                Files.createDirectories(target.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path relative = source.relativize(file);
                Files.copy(file, target.resolve(relative), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteRecursive(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private String readVersion(Path panelJson) {
        if (!Files.exists(panelJson)) {
            return "unknown";
        }
        try {
            String text = Files.readString(panelJson, StandardCharsets.UTF_8);
            String marker = "\"version\"";
            int key = text.indexOf(marker);
            if (key < 0) {
                return "unknown";
            }
            int colon = text.indexOf(':', key + marker.length());
            int firstQuote = text.indexOf('"', colon + 1);
            int secondQuote = text.indexOf('"', firstQuote + 1);
            if (colon < 0 || firstQuote < 0 || secondQuote < 0) {
                return "unknown";
            }
            return text.substring(firstQuote + 1, secondQuote).trim();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
