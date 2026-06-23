package main.java.mjt.system.download;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

public class SystemDownloadService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static final String CLOUDFLARED_LATEST_DOWNLOAD_BASE =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/";

    private final StateStore stateStore;
    private final LogService logService;
    private final HttpClient httpClient;
    private final ProotDistroInstaller prootDistroToolInstaller;
    private final PortablePythonInstaller portablePythonInstaller;
    

    public SystemDownloadService(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.portablePythonInstaller = new PortablePythonInstaller(stateStore, logService);
        this.prootDistroToolInstaller = new ProotDistroInstaller(
                stateStore,
                logService,
                portablePythonInstaller
        );
    }

    public void showCloudflared() {
        System.out.println(CYAN + "[SYSTEM DOWNLOAD - CLOUDFLARED]" + RESET);
        System.out.println("OS              : " + System.getProperty("os.name", "unknown"));
        System.out.println("Arch            : " + System.getProperty("os.arch", "unknown"));
        System.out.println("Install dir     : " + getCloudflaredInstallDir());
        System.out.println("Configured path : " + stateStore.get("tunnel.cloudflared.path", "cloudflared"));
        System.out.println("Last asset      : " + stateStore.get("system.download.cloudflared.asset", ""));
        System.out.println("Last URL        : " + stateStore.get("system.download.cloudflared.url", ""));
        System.out.println("Last version    : " + stateStore.get("system.download.cloudflared.version", ""));
        System.out.println("Last status     : " + stateStore.get("system.download.cloudflared.status", "never"));
        System.out.println();
        checkCloudflared(false);
    }

    public boolean checkCloudflared(boolean quiet) {
        List<Path> candidates = new ArrayList<>();

        String configured = stateStore.get("tunnel.cloudflared.path", "cloudflared").trim();
        if (!configured.isBlank() && !configured.equals("cloudflared")) {
            candidates.add(Paths.get(configured));
        }

        candidates.add(getCloudflaredBinaryPath());

        for (Path candidate : candidates) {
            if (candidate == null || !Files.exists(candidate)) {
                continue;
            }

            try {
                String version = runVersionCheck(candidate.toString());
                if (!quiet) {
                    System.out.println(GREEN + "[cloudflared] OK: " + candidate + RESET);
                    System.out.println(version);
                }
                return true;
            } catch (Exception e) {
                if (!quiet) {
                    System.out.println(YELLOW + "[cloudflared] Check failed: " + candidate + " - " + e.getMessage() + RESET);
                }
            }
        }

        try {
            String version = runVersionCheck("cloudflared");
            if (!quiet) {
                System.out.println(GREEN + "[cloudflared] OK from PATH" + RESET);
                System.out.println(version);
            }
            return true;
        } catch (Exception e) {
            if (!quiet) {
                System.out.println(RED + "[cloudflared] Not installed or not executable." + RESET);
                System.out.println(YELLOW + "Run: .mjt system install cloudflared" + RESET);
            }
            return false;
        }
    }

    public void installCloudflared() {
        try {
            PlatformTarget target = detectCloudflaredTarget();
            Path installDir = getCloudflaredInstallDir();
            Path binaryPath = getCloudflaredBinaryPath(target);
            Path tempPath = installDir.resolve(binaryPath.getFileName().toString() + ".download");
            String customUrl = stateStore.get("system.download.cloudflared.url.override", "").trim();
            String downloadUrl = customUrl.isBlank()
                    ? CLOUDFLARED_LATEST_DOWNLOAD_BASE + target.assetName
                    : customUrl;

            Files.createDirectories(installDir);

            System.out.println(CYAN + "[cloudflared installer]" + RESET);
            System.out.println("Detected OS   : " + target.osLabel);
            System.out.println("Detected Arch : " + target.archLabel);
            System.out.println("Asset         : " + target.assetName);
            System.out.println("Download URL  : " + downloadUrl);
            System.out.println("Install dir   : " + installDir);
            System.out.println();

            downloadToFile(downloadUrl, tempPath);
            Files.move(tempPath, binaryPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            if (!target.windows) {
                boolean executable = binaryPath.toFile().setExecutable(true, false);
                if (!executable) {
                    System.out.println(YELLOW + "[cloudflared installer] Cannot set executable flag via Java API." + RESET);
                    System.out.println(YELLOW + "Try manually: chmod +x " + binaryPath + RESET);
                }
            }

            String version = runVersionCheck(binaryPath.toString());

            stateStore.set("tunnel.cloudflared.path", binaryPath.toString());
            stateStore.set("system.download.cloudflared.asset", target.assetName);
            stateStore.set("system.download.cloudflared.url", downloadUrl);
            stateStore.set("system.download.cloudflared.os", target.osLabel);
            stateStore.set("system.download.cloudflared.arch", target.archLabel);
            stateStore.set("system.download.cloudflared.path", binaryPath.toString());
            stateStore.set("system.download.cloudflared.version", firstLine(version));
            stateStore.set("system.download.cloudflared.status", "installed");

            System.out.println(GREEN + "[cloudflared installer] Installation successful." + RESET);
            System.out.println(GREEN + "Binary : " + binaryPath + RESET);
            System.out.println(GREEN + "Version: " + firstLine(version) + RESET);
            System.out.println(YELLOW + "MJT saved tunnel.cloudflared.path automatically." + RESET);

            logService.write("[CLOUDFLARED INSTALL OK] " + binaryPath + " | " + firstLine(version) + "\n");

        } catch (Exception e) {
            trySet("system.download.cloudflared.status", "failed");
            System.out.println(RED + "[cloudflared installer] Failed: " + e.getMessage() + RESET);
            System.out.println(YELLOW + "You can set binary manually: .mjt tunnel set cloudflared <path>" + RESET);

            try {
                logService.write("[CLOUDFLARED INSTALL ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }



    /** Downloads MJT-managed native PRoot only. */
    public void installProot() {
        try {
            System.out.println(CYAN + "[MJT SYSTEM - PROOT]" + RESET);
            Path path = prootDistroToolInstaller.installProot(System.out::println);
            System.out.println(GREEN + "[MJT SYSTEM] PRoot ready: " + path + RESET);
        } catch (Exception e) {
            trySet("system.download.proot.status", "failed");
            System.out.println(RED + "[MJT SYSTEM] PRoot install failed: " + safeMessage(e) + RESET);
        }
    }

    /** Downloads MJT-managed portable Python only. */
    public void installPortablePython() {
        try {
            System.out.println(CYAN + "[MJT SYSTEM - PYTHON]" + RESET);
            Path path = portablePythonInstaller.install(System.out::println);
            System.out.println(GREEN + "[MJT SYSTEM] Portable Python ready: " + path + RESET);
        } catch (Exception e) {
            trySet("system.download.python.status", "failed");
            System.out.println(RED + "[MJT SYSTEM] Python install failed: " + safeMessage(e) + RESET);
        }
    }

    /** Prints only the MJT-managed portable Python status. */
    public void showPortablePython() {
        portablePythonInstaller.printStatus();
    }

    /** Checks only the MJT-managed portable Python runtime. */
    public boolean checkPortablePython(boolean quiet) {
        return portablePythonInstaller.check(quiet);
    }

    /**
     * Installs the complete MJT environment engine. Dependencies are resolved
     * in order: native PRoot, portable Python, then the pinned upstream
     * proot-distro package. No host package manager and no Docker are used.
     */
    public void installProotDistro() {
        try {
            System.out.println(CYAN + "[MJT SYSTEM - PROOT-DISTRO]" + RESET);
            ProotDistroInstaller.EnginePaths paths =
                    prootDistroToolInstaller.install(
                            ProotDistroInstaller.DEFAULT_PROOT_DISTRO_VERSION,
                            System.out::println
                    );
            System.out.println(GREEN + "[MJT SYSTEM] Environment engine ready." + RESET);
            System.out.println("PRoot        : " + paths.proot());
            System.out.println("Python       : " + paths.python());
            System.out.println("proot-distro : " + paths.prootDistro() + " | " + paths.prootDistroVersion());
        } catch (Exception e) {
            trySet("system.download.proot-distro.status", "failed");
            System.out.println(RED + "[MJT SYSTEM] proot-distro install failed: " + safeMessage(e) + RESET);
        }
    }

    public boolean checkProotDistro(boolean quiet) {
        return prootDistroToolInstaller.check(quiet);
    }

    public void showProotDistro() {
        prootDistroToolInstaller.printStatus();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }
    private void downloadToFile(String url, Path outputFile) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "Mini-Java-Terminal/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download failed. HTTP " + response.statusCode());
        }

        try (InputStream input = response.body()) {
            Files.copy(input, outputFile, StandardCopyOption.REPLACE_EXISTING);
        }

        long size = Files.size(outputFile);
        if (size < 1024 * 1024) {
            throw new IOException("Downloaded file is too small: " + size + " bytes");
        }

        System.out.println(GREEN + "[cloudflared installer] Downloaded " + size + " bytes." + RESET);
    }

    private String runVersionCheck(String binary) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(binary, "--version")
                .redirectErrorStream(true)
                .start();

        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            } catch (IOException ignored) {
            }
        }, "mjt-cloudflared-version-reader");
        reader.setDaemon(true);
        reader.start();

        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("cloudflared --version timed out");
        }

        try {
            reader.join(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        if (process.exitValue() != 0) {
            throw new IOException("cloudflared --version exited with code " + process.exitValue()
                    + ": " + output);
        }

        String text = output.toString().trim();
        if (text.isBlank()) {
            throw new IOException("cloudflared --version produced empty output");
        }

        return text;
    }

    private PlatformTarget detectCloudflaredTarget() throws IOException {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String archName = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        boolean windows = osName.contains("win");
        boolean linux = osName.contains("linux");
        boolean mac = osName.contains("mac") || osName.contains("darwin");

        String arch;
        if (archName.equals("x86_64") || archName.equals("amd64")) {
            arch = "amd64";
        } else if (archName.equals("aarch64") || archName.equals("arm64")) {
            arch = "arm64";
        } else if (archName.startsWith("armv7") || archName.equals("arm")) {
            arch = "arm";
        } else if (archName.startsWith("armv6") || archName.contains("armhf")) {
            arch = "armhf";
        } else if (archName.equals("x86") || archName.equals("i386") || archName.equals("i686")) {
            arch = "386";
        } else {
            throw new IOException("Unsupported CPU architecture: " + archName);
        }

        if (linux) {
            if (arch.equals("amd64")) return new PlatformTarget("linux", arch, "cloudflared-linux-amd64", false);
            if (arch.equals("arm64")) return new PlatformTarget("linux", arch, "cloudflared-linux-arm64", false);
            if (arch.equals("arm")) return new PlatformTarget("linux", arch, "cloudflared-linux-arm", false);
            if (arch.equals("armhf")) return new PlatformTarget("linux", arch, "cloudflared-linux-armhf", false);
            if (arch.equals("386")) return new PlatformTarget("linux", arch, "cloudflared-linux-386", false);
        }

        if (windows) {
            if (arch.equals("amd64")) return new PlatformTarget("windows", arch, "cloudflared-windows-amd64.exe", true);
            if (arch.equals("386")) return new PlatformTarget("windows", arch, "cloudflared-windows-386.exe", true);
            throw new IOException("Unsupported Windows architecture for standalone cloudflared: " + archName);
        }

        if (mac) {
            throw new IOException("macOS assets are .tgz archives. Auto extraction is not enabled in this server build yet.");
        }

        throw new IOException("Unsupported operating system: " + osName);
    }

    private Path getCloudflaredInstallDir() {
        String configured = stateStore.get(
                "system.download.cloudflared.dir",
                "MJT/system/downloads/cloudflared"
        ).trim();

        if (configured.isBlank()) {
            configured = "MJT/system/downloads/cloudflared";
        }

        return Paths.get(configured).toAbsolutePath().normalize();
    }

    private Path getCloudflaredBinaryPath() {
        try {
            return getCloudflaredBinaryPath(detectCloudflaredTarget());
        } catch (Exception ignored) {
            return getCloudflaredInstallDir().resolve("cloudflared").toAbsolutePath().normalize();
        }
    }

    private Path getCloudflaredBinaryPath(PlatformTarget target) {
        String fileName = target.windows ? "cloudflared.exe" : "cloudflared";
        return getCloudflaredInstallDir().resolve(fileName).toAbsolutePath().normalize();
    }

    private String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] lines = text.trim().split("\\R", 2);
        return lines.length == 0 ? text.trim() : lines[0].trim();
    }

    private void trySet(String key, String value) {
        try {
            stateStore.set(key, value);
        } catch (IOException ignored) {
        }
    }

    private static final class PlatformTarget {
        final String osLabel;
        final String archLabel;
        final String assetName;
        final boolean windows;

        PlatformTarget(String osLabel, String archLabel, String assetName, boolean windows) {
            this.osLabel = osLabel;
            this.archLabel = archLabel;
            this.assetName = assetName;
            this.windows = windows;
        }
    }
}
