package main.java.mjt.services.cloudflare.tunnel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import main.java.mjt.services.http.HttpService;
import main.java.mjt.system.BuildInfo;
import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

public class GuestWebsiteService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ID_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final StateStore stateStore;
    private final LogService logService;
    private final HttpService httpService;
    private final CloudflareTunnelService tunnelService;

    public GuestWebsiteService(
            StateStore stateStore,
            LogService logService,
            HttpService httpService,
            CloudflareTunnelService tunnelService
    ) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.httpService = httpService;
        this.tunnelService = tunnelService;
    }

    public void createGuest() {
        try {
            String guestId = generateGuestId();
            int port = findFreeGuestPort();
            Path root = getGuestRootBase().resolve(guestId).resolve("main").toAbsolutePath().normalize();
            Files.createDirectories(root);
            createDefaultIndex(root, guestId);

            String localUrl = "http://127.0.0.1:" + port;

            addGuestId(guestId);
            stateStore.set(guestKey(guestId, "type"), "guest");
            stateStore.set(guestKey(guestId, "root"), root.toString());
            stateStore.set(guestKey(guestId, "host"), "127.0.0.1");
            stateStore.set(guestKey(guestId, "port"), String.valueOf(port));
            stateStore.set(guestKey(guestId, "local"), localUrl);
            stateStore.set(guestKey(guestId, "publicUrl"), "pending");
            stateStore.set(guestKey(guestId, "status"), "created");

            httpService.addSite(guestId, "127.0.0.1", String.valueOf(port), root.toString());
            httpService.startSite(guestId);
            tunnelService.startQuickTunnel(guestId, localUrl);
            stateStore.set(guestKey(guestId, "status"), "running");

            System.out.println();
            System.out.println(GREEN + "Guest website created" + RESET);
            System.out.println("ID      : " + guestId);
            System.out.println("Root    : " + root);
            System.out.println("Local   : " + localUrl);
            System.out.println("Public  : pending - wait for cloudflared to print trycloudflare.com URL");
            System.out.println("Mode    : Cloudflare Quick Tunnel");
            System.out.println(YELLOW + "Hint    : Use .mjt website guest show " + guestId + " after a few seconds." + RESET);
            System.out.println();

            logService.write("[GUEST CREATE] " + guestId + " " + localUrl + " " + root + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[Guest] Create error: " + e.getMessage() + RESET);
            tryLog("[GUEST CREATE ERROR] " + e.getMessage() + "\n");
        }
    }

    public void listGuests() {
        System.out.println(CYAN + "[GUEST WEBSITES]" + RESET);

        List<String> guests = getGuestIds();
        if (guests.isEmpty()) {
            System.out.println("No guest websites found.");
            return;
        }

        for (String guestId : guests) {
            System.out.println("  " + guestId
                    + " | status=" + stateStore.get(guestKey(guestId, "status"), "unknown")
                    + " | local=" + stateStore.get(guestKey(guestId, "local"), "")
                    + " | public=" + stateStore.get(guestKey(guestId, "publicUrl"), ""));
        }
    }

    public void showGuest(String rawGuestId) {
        String guestId = normalizeGuestId(rawGuestId);

        if (guestId.isBlank()) {
            System.out.println(RED + "[Guest] Invalid guest id." + RESET);
            return;
        }

        System.out.println(CYAN + "[GUEST WEBSITE] " + guestId + RESET);
        System.out.println("Status  : " + stateStore.get(guestKey(guestId, "status"), "unknown"));
        System.out.println("Tunnel  : " + stateStore.get(guestKey(guestId, "tunnel"), tunnelService.isQuickTunnelRunning(guestId) ? "running" : "stopped"));
        System.out.println("Root    : " + stateStore.get(guestKey(guestId, "root"), ""));
        System.out.println("Local   : " + stateStore.get(guestKey(guestId, "local"), ""));
        System.out.println("Public  : " + stateStore.get(guestKey(guestId, "publicUrl"), "pending"));
    }

    public void stopGuest(String rawGuestId) {
        String guestId = normalizeGuestId(rawGuestId);

        if (guestId.isBlank()) {
            System.out.println(RED + "[Guest] Invalid guest id." + RESET);
            return;
        }

        tunnelService.stopQuickTunnel(guestId);
        httpService.stopSite(guestId);
        trySet(guestKey(guestId, "status"), "stopped");

        System.out.println(YELLOW + "[Guest] Stopped: " + guestId + RESET);
    }

    public void restartGuest(String rawGuestId) {
        String guestId = normalizeGuestId(rawGuestId);

        if (guestId.isBlank()) {
            System.out.println(RED + "[Guest] Invalid guest id." + RESET);
            return;
        }

        String localUrl = stateStore.get(guestKey(guestId, "local"), "").trim();

        if (localUrl.isBlank()) {
            System.out.println(RED + "[Guest] Missing local URL for: " + guestId + RESET);
            return;
        }

        tunnelService.stopQuickTunnel(guestId);
        httpService.restartSite(guestId);

        try {
            stateStore.set(guestKey(guestId, "publicUrl"), "pending");
            tunnelService.startQuickTunnel(guestId, localUrl);
            stateStore.set(guestKey(guestId, "status"), "running");
            System.out.println(GREEN + "[Guest] Restarted: " + guestId + RESET);
        } catch (Exception e) {
            System.out.println(RED + "[Guest] Restart error: " + e.getMessage() + RESET);
        }
    }

    public void removeGuest(String rawGuestId) {
        String guestId = normalizeGuestId(rawGuestId);

        if (guestId.isBlank()) {
            System.out.println(RED + "[Guest] Invalid guest id." + RESET);
            return;
        }

        stopGuest(guestId);

        try {
            httpService.removeSite(guestId);
            removeGuestId(guestId);
            stateStore.remove(guestKey(guestId, "type"));
            stateStore.remove(guestKey(guestId, "root"));
            stateStore.remove(guestKey(guestId, "host"));
            stateStore.remove(guestKey(guestId, "port"));
            stateStore.remove(guestKey(guestId, "local"));
            stateStore.remove(guestKey(guestId, "publicUrl"));
            stateStore.remove(guestKey(guestId, "status"));
            stateStore.remove(guestKey(guestId, "tunnel"));

            System.out.println(GREEN + "[Guest] Removed config: " + guestId + RESET);
            System.out.println(YELLOW + "[Guest] Website folder is kept for safety. Delete manually if needed." + RESET);
            logService.write("[GUEST REMOVE] " + guestId + "\n");
        } catch (Exception e) {
            System.out.println(RED + "[Guest] Remove error: " + e.getMessage() + RESET);
        }
    }

    private Path getGuestRootBase() {
        return Paths.get(stateStore.get("website.guest.rootBase", "/home/container/server/website/www/guest"))
                .toAbsolutePath()
                .normalize();
    }

    private int findFreeGuestPort() throws IOException {
        int start = stateStore.getInt("website.guest.nextPort", 8091);
        if (start < 1024) {
            start = 8091;
        }

        for (int port = start; port <= 65535; port++) {
            if (isPortFree(port)) {
                stateStore.set("website.guest.nextPort", String.valueOf(port + 1));
                return port;
            }
        }

        throw new IOException("No free guest HTTP port found.");
    }

    private boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void createDefaultIndex(Path root, String guestId) throws IOException {
        Path index = root.resolve("index.html");

        if (Files.exists(index)) {
            return;
        }

        String html = """
                <!DOCTYPE html>
                <html lang=\"en\">
                <head>
                    <meta charset=\"UTF-8\">
                    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">
                    <title>MJT Guest Site</title>
                </head>
                <body>
                    <h1>MJT Guest Website</h1>
                    <p>This guest site is served by Mini Java Terminal.</p>
                    <p>ID: <b>%s</b></p>
                    <p>Version: <b>%s</b></p>
                </body>
                </html>
                """.formatted(guestId, BuildInfo.VERSION);

        Files.writeString(index, html, StandardCharsets.UTF_8);
    }

    private String generateGuestId() {
        for (int attempt = 0; attempt < 1000; attempt++) {
            String candidate = "guest-" + randomText(5);
            if (!getGuestIds().contains(candidate)) {
                return candidate;
            }
        }

        return "guest-" + System.currentTimeMillis();
    }

    private String randomText(int length) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; i++) {
            builder.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }

        return builder.toString();
    }

    private List<String> getGuestIds() {
        String raw = stateStore.get("website.guests", "").trim();
        List<String> guests = new ArrayList<>();

        if (raw.isBlank()) {
            return guests;
        }

        for (String item : raw.split(",")) {
            String clean = normalizeGuestId(item);
            if (!clean.isBlank() && !guests.contains(clean)) {
                guests.add(clean);
            }
        }

        return guests;
    }

    private void addGuestId(String guestId) throws IOException {
        List<String> guests = getGuestIds();
        if (!guests.contains(guestId)) {
            guests.add(guestId);
            stateStore.set("website.guests", String.join(",", guests));
        }
    }

    private void removeGuestId(String guestId) throws IOException {
        List<String> guests = getGuestIds();
        guests.remove(guestId);
        stateStore.set("website.guests", String.join(",", guests));
    }

    private String guestKey(String guestId, String key) {
        return "website.guest." + normalizeGuestId(guestId) + "." + key;
    }

    private String normalizeGuestId(String value) {
        if (value == null) {
            return "";
        }

        String clean = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        clean = clean.replaceAll("-+", "-");

        if (clean.equals("-") || clean.equals("_")) {
            return "";
        }

        return clean;
    }

    private void trySet(String key, String value) {
        try {
            stateStore.set(key, value);
        } catch (IOException ignored) {
        }
    }

    private void tryLog(String text) {
        try {
            logService.write(text);
        } catch (IOException ignored) {
        }
    }
}
