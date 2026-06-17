package main.java.mjt.services.cloudflare;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import main.java.mjt.system.LogService;
import main.java.mjt.system.PublicIpService;
import main.java.mjt.system.StateStore;

public class CloudflareDnsService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final StateStore stateStore;
    private final PublicIpService publicIpService;
    private final LogService logService;
    private final HttpClient httpClient;

    private Thread loopThread;
    private volatile boolean running = false;
    private volatile String lastUpdatedIp = "";

    public CloudflareDnsService(
            StateStore stateStore,
            PublicIpService publicIpService,
            LogService logService
    ) {
        this.stateStore = stateStore;
        this.publicIpService = publicIpService;
        this.logService = logService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public void setConfig(String key, String value) throws IOException {
        String realKey = normalizeKey(key);

        if (realKey == null) {
            System.out.println(RED + "[cloudflare] Invalid key: " + key + RESET);
            printSetHelp();
            return;
        }

        if (realKey.equals("cloudflare.proxied")) {
            value = normalizeBoolean(value);
        }

        if (realKey.equals("cloudflare.ttl") || realKey.equals("cloudflare.intervalSeconds")) {
            if (!isPositiveNumber(value)) {
                System.out.println(RED + "[cloudflare] Value must be a positive number." + RESET);
                return;
            }
        }

        stateStore.set(realKey, value);

        if (realKey.equals("cloudflare.apiToken")) {
            System.out.println(GREEN + "[cloudflare] Saved token: " + stateStore.maskSecret(value) + RESET);
        } else {
            System.out.println(GREEN + "[cloudflare] Saved " + realKey + " = " + value + RESET);
        }

        logService.write("[cloudflare SET] " + realKey + "\n");
    }

    public void showConfig() {
        System.out.println(CYAN + "[CLOUDFLARE CONFIG]" + RESET);
        System.out.println("State file       : " + stateStore.getStateFile());

        printValue("cloudflare.apiToken", true);
        printValue("cloudflare.zoneId", false);
        printValue("cloudflare.recordId", false);
        printValue("cloudflare.recordName", false);
        printValue("cloudflare.proxied", false);
        printValue("cloudflare.ttl", false);
        printValue("cloudflare.intervalSeconds", false);
        printValue("cloudflare.lastIp", false);
    }

    public void updateOnce() {
        try {
            validateConfig();

            String apiToken = stateStore.get("cloudflare.apiToken").trim();
            String zoneId = stateStore.get("cloudflare.zoneId").trim();
            String recordName = stateStore.get("cloudflare.recordName").trim();
            String proxied = stateStore.get("cloudflare.proxied", "false").         trim();
            String ttl = stateStore.get("cloudflare.ttl", "120").trim();            

            String currentIp = publicIpService.getPublicIpv4();         

            String recordId = stateStore.get("cloudflare.recordId").trim();         

            if (recordId.isBlank()) {
                recordId = ensureDnsRecordId(apiToken, zoneId, recordName, currentIp, proxied, ttl);
            }           

            String lastIp = stateStore.get("cloudflare.lastIp", "").trim();

            System.out.println(CYAN + "[cloudflare-DDNS]" + RESET);
            System.out.println("Current IPv4 : " + currentIp);
            System.out.println("Last IPv4    : " + (lastIp.isBlank() ? "none" : lastIp));
            System.out.println("Record       : " + recordName);

            if (currentIp.equals(lastIp)) {
                System.out.println(GREEN + "[cloudflare-DDNS] IP unchanged, skipping update." + RESET);
                logService.write("[cloudflare-DDNS] IP unchanged: " + currentIp + "\n");
                return;
            }

            String json = "{"
                    + "\"type\":\"A\","
                    + "\"name\":\"" + jsonEscape(recordName) + "\","
                    + "\"content\":\"" + jsonEscape(currentIp) + "\","
                    + "\"ttl\":" + ttl + ","
                    + "\"proxied\":" + proxied
                    + "}";

            String endpoint = "https://api.cloudflare.com/client/v4/zones/"
                    + zoneId
                    + "/dns_records/"
                    + recordId;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + apiToken)
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() >= 200
                    && response.statusCode() < 300
                    && response.body().contains("\"success\":true")) {

                stateStore.set("cloudflare.lastIp", currentIp);
                lastUpdatedIp = currentIp;

                System.out.println(GREEN + "[cloudflare-DDNS] DNS updated successfully." + RESET);
                System.out.println(GREEN + recordName + " -> " + currentIp + RESET);

                logService.write("[cloudflare-DDNS SUCCESS] " + recordName + " -> " + currentIp + "\n");

            } else {
                System.out.println(RED + "[cloudflare-DDNS] Update failed. HTTP "
                        + response.statusCode()
                        + RESET);

                System.out.println(YELLOW + response.body() + RESET);

                logService.write("[cloudflare-DDNS ERROR] HTTP " + response.statusCode() + "\n");
                logService.write("[cloudflare-DDNS RESPONSE] " + response.body() + "\n");
            }

        } catch (Exception e) {
            System.out.println(RED + "[cloudflare-DDNS] Error: " + e.getMessage() + RESET);

            try {
                logService.write("[cloudflare-DDNS ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    public void startLoop() {
        if (running) {
            System.out.println(YELLOW + "[cloudflare-DDNS] Auto DDNS is already running." + RESET);
            return;
        }

        running = true;

        loopThread = new Thread(() -> {
            System.out.println(GREEN + "[cloudflare-DDNS] Auto DDNS started." + RESET);

            while (running) {
                try {
                    updateOnce();

                    int interval = stateStore.getInt("cloudflare.intervalSeconds", 300);

                    if (interval < 60) {
                        interval = 60;
                    }

                    Thread.sleep(interval * 1000L);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;

                } catch (Exception e) {
                    System.out.println(RED + "[cloudflare-DDNS LOOP] " + e.getMessage() + RESET);

                    try {
                        logService.write("[cloudflare-DDNS LOOP ERROR] " + e.getMessage() + "\n");
                    } catch (IOException ignored) {
                    }

                    try {
                        Thread.sleep(300_000);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            System.out.println(YELLOW + "[cloudflare-DDNS] Auto DDNS stopped." + RESET);
        });

        loopThread.setDaemon(true);
        loopThread.start();
    }

    public void stopLoop() {
        running = false;

        if (loopThread != null) {
            loopThread.interrupt();
        }

        System.out.println(YELLOW + "[cloudflare-DDNS] Stop requested." + RESET);
    }

    public void printStatus() {
        System.out.println(CYAN + "[cloudflare-DDNS STATUS]" + RESET);
        System.out.println("Running      : " + running);
        System.out.println("Record name  : " + valueOrEmpty("cloudflare.recordName"));
        System.out.println("Last state IP: " + valueOrEmpty("cloudflare.lastIp"));
        System.out.println("Last update  : " + (lastUpdatedIp.isBlank() ? "none" : lastUpdatedIp));
        System.out.println("Interval     : " + stateStore.get("cloudflare.intervalSeconds", "300") + " seconds");
    }

    private void validateConfig() throws IOException {
        if (stateStore.get("cloudflare.apiToken").isBlank()) {
            throw new IOException("Missing cloudflare.apiToken. Use: cloudflare-set token <token>");
        }

        if (stateStore.get("cloudflare.zoneId").isBlank()) {
            throw new IOException("Missing cloudflare.zoneId. Use: cloudflare-set zone <zone_id>");
        }

        if (stateStore.get("cloudflare.recordName").isBlank()) {
            throw new IOException("Missing cloudflare.recordName. Use: cloudflare-set name <domain>");
        }

        if (stateStore.get("cloudflare.proxied").isBlank()) {
            stateStore.set("cloudflare.proxied", "false");
        }

        if (stateStore.get("cloudflare.ttl").isBlank()) {
            stateStore.set("cloudflare.ttl", "120");
        }

        if (stateStore.get("cloudflare.intervalSeconds").isBlank()) {
            stateStore.set("cloudflare.intervalSeconds", "300");
        }
    }

    private String normalizeKey(String key) {
        String lower = key.toLowerCase().trim();

        switch (lower) {
            case "token":
            case "apitoken":
            case "api-token":
            case "cloudflare.apitoken":
                return "cloudflare.apiToken";

            case "zone":
            case "zoneid":
            case "zone-id":
            case "cloudflare.zoneid":
                return "cloudflare.zoneId";

            case "record":
            case "recordid":
            case "record-id":
            case "cloudflare.recordid":
                return "cloudflare.recordId";

            case "name":
            case "domain":
            case "recordname":
            case "record-name":
            case "cloudflare.recordname":
                return "cloudflare.recordName";

            case "proxied":
            case "proxy":
            case "cloudflare.proxied":
                return "cloudflare.proxied";

            case "ttl":
            case "cloudflare.ttl":
                return "cloudflare.ttl";

            case "interval":
            case "intervalseconds":
            case "interval-seconds":
            case "cloudflare.intervalseconds":
                return "cloudflare.intervalSeconds";

            default:
                return null;
        }
    }

    private String normalizeBoolean(String value) {
        if (value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("on")) {
            return "true";
        }

        return "false";
    }

    private boolean isPositiveNumber(String value) {
        try {
            int number = Integer.parseInt(value.trim());
            return number > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void printValue(String key, boolean secret) {
        String value = stateStore.get(key, "");

        if (secret) {
            value = stateStore.maskSecret(value);
        }

        if (value == null || value.isBlank()) {
            value = "(empty)";
        }

        System.out.println(key + " = " + value);
    }

    private String valueOrEmpty(String key) {
        String value = stateStore.get(key, "");

        if (value == null || value.isBlank()) {
            return "none";
        }

        return value;
    }

    private void printSetHelp() {
        System.out.println(YELLOW + "Valid keys:" + RESET);
        System.out.println("cloudflare-set token <token>");
        System.out.println("cloudflare-set zone <zone_id>");
        System.out.println("cloudflare-set record <record_id>");
        System.out.println("cloudflare-set name <record_name>");
        System.out.println("cloudflare-set proxied false");
        System.out.println("cloudflare-set ttl 120");
        System.out.println("cloudflare-set interval 300");
    }

private String ensureDnsRecordId(
        String apiToken,
        String zoneId,
        String recordName,
        String currentIp,
        String proxied,
        String ttl
) throws IOException, InterruptedException {

    String existingRecordId = findDnsRecordId(apiToken, zoneId, recordName);

    if (!existingRecordId.isBlank()) {
        stateStore.set("cloudflare.recordId", existingRecordId);

        System.out.println(GREEN + "[cloudflare-DDNS] Found recordId: "
                + existingRecordId + RESET);

        logService.write("[cloudflare-DDNS] Found recordId: " + existingRecordId + "\n");

        return existingRecordId;
    }

    String createdRecordId = createDnsRecord(
            apiToken,
            zoneId,
            recordName,
            currentIp,
            proxied,
            ttl
    );

    stateStore.set("cloudflare.recordId", createdRecordId);

    System.out.println(GREEN + "[cloudflare-DDNS] Created new DNS record." + RESET);
    System.out.println(GREEN + "[cloudflare-DDNS] recordId: " + createdRecordId + RESET);

    logService.write("[cloudflare-DDNS] Created recordId: " + createdRecordId + "\n");

    return createdRecordId;
}

private String findDnsRecordId(
        String apiToken,
        String zoneId,
        String recordName
) throws IOException, InterruptedException {

    String encodedName = URLEncoder.encode(recordName, StandardCharsets.UTF_8);

    String endpoint = "https://api.cloudflare.com/client/v4/zones/"
            + zoneId
            + "/dns_records?type=A&name="
            + encodedName;

    HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer " + apiToken)
            .header("Content-Type", "application/json")
            .GET()
            .build();

    HttpResponse<String> response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofString()
    );

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IOException("Cannot list DNS records. HTTP " + response.statusCode());
    }

    String body = response.body();

    if (!body.contains("\"success\":true")) {
        throw new IOException("Cloudflare list DNS records failed: " + body);
    }

    return extractFirstRecordId(body);
}

    private String createDnsRecord(
            String apiToken,
            String zoneId,
            String recordName,
            String currentIp,
            String proxied,
            String ttl
    ) throws IOException, InterruptedException {
    
        String json = "{"
                + "\"type\":\"A\","
                + "\"name\":\"" + jsonEscape(recordName) + "\","
                + "\"content\":\"" + jsonEscape(currentIp) + "\","
                + "\"ttl\":" + ttl + ","
                + "\"proxied\":" + proxied
                + "}";
    
        String endpoint = "https://api.cloudflare.com/client/v4/zones/"
                + zoneId
                + "/dns_records";
    
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + apiToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    
        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );
    
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Cannot create DNS record. HTTP "
                    + response.statusCode()
                    + " | "
                    + response.body());
        }
    
        String body = response.body();
    
        if (!body.contains("\"success\":true")) {
            throw new IOException("Cloudflare create DNS record failed: " + body);
        }
    
        String recordId = extractFirstRecordId(body);
    
        if (recordId.isBlank()) {
            throw new IOException("DNS record created successfully but could not retrieve recordId.");
        }
    
        return recordId;
    }
    
    private String extractFirstRecordId(String json) {
        Pattern pattern = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"");
        Matcher matcher = pattern.matcher(json);
    
        if (matcher.find()) {
            return matcher.group(1);
        }
    
        return "";
    }

    private String jsonEscape(String text) {
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}