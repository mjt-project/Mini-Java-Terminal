package terminal.system;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class PublicIpService {
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    private final LogService logService;
    private final HttpClient httpClient;

    public PublicIpService(LogService logService) {
        this.logService = logService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String getPublicIpv4() throws IOException, InterruptedException {
        String[] services = {
                "https://api.ipify.org",
                "https://ifconfig.me/ip",
                "https://checkip.amazonaws.com"
        };

        for (String service : services) {
            try {
                String ip = requestIp(service);

                if (isValidIpv4(ip)) {
                    logService.write("[PUBLIC IP] " + ip + "\n");
                    logService.write("[PUBLIC IP SOURCE] " + service + "\n");
                    return ip;
                }

            } catch (Exception e) {
                logService.write("[PUBLIC IP SERVICE FAILED] "
                        + service
                        + " -> "
                        + e.getMessage()
                        + "\n");
            }
        }

        throw new IOException("Cannot detect public IPv4.");
    }

    public void printPublicIpv4() {
        System.out.println(CYAN + "[PUBLIC IP CHECK]" + RESET);

        try {
            String ip = getPublicIpv4();
            System.out.println(GREEN + "Public IPv4: " + ip + RESET);

        } catch (Exception e) {
            System.out.println(RED + "Không check được public IP: "
                    + e.getMessage()
                    + RESET);

            try {
                logService.write("[PUBLIC IP ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private String requestIp(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return response.body().trim();
    }

    private boolean isValidIpv4(String ip) {
        if (ip == null || ip.isBlank()) {
            return false;
        }

        String[] parts = ip.trim().split("\\.");

        if (parts.length != 4) {
            return false;
        }

        for (String part : parts) {
            try {
                int number = Integer.parseInt(part);

                if (number < 0 || number > 255) {
                    return false;
                }

            } catch (NumberFormatException e) {
                return false;
            }
        }

        return true;
    }
}