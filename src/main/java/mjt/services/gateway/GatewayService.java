package main.java.mjt.services.gateway;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

public class GatewayService {
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    private final LogService logService;
    private final StateStore stateStore;
    private final ExecutorService connectionPool = Executors.newFixedThreadPool(64);

    private ServerSocket publicServerSocket;
    private volatile boolean gatewayRunning = false;

    public GatewayService(LogService logService, StateStore stateStore) {
        this.logService = logService;
        this.stateStore = stateStore;
    }

    public synchronized void start() {
        if (gatewayRunning) {
            System.out.println(YELLOW + "[Gateway] Already running." + RESET);
            return;
        }

        if (!stateStore.getBoolean("gateway.enabled", true)) {
            System.out.println(YELLOW + "[Gateway] Disabled. Use: .mjt gateway set gateway.enabled true" + RESET);
            return;
        }

        String publicTcpHost = getPublicTcpHost();
        int publicTcpPort = getPublicTcpPort();

        try {
            publicServerSocket = new ServerSocket();
            publicServerSocket.setReuseAddress(true);
            publicServerSocket.bind(new InetSocketAddress(publicTcpHost, publicTcpPort));

            gatewayRunning = true;

            printGatewayStartup(publicTcpHost, publicTcpPort);

            Thread acceptThread = new Thread(this::acceptLoop, "mjt-gateway-accept-loop");
            acceptThread.setDaemon(false);
            acceptThread.start();

            logService.write("[GATEWAY START] " + publicTcpHost + ":" + publicTcpPort + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Start error: " + e.getMessage() + RESET);

            try {
                logService.write("[GATEWAY START ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    public synchronized void stop() {
        gatewayRunning = false;

        try {
            if (publicServerSocket != null) {
                publicServerSocket.close();
            }
        } catch (IOException ignored) {
        }

        System.out.println(YELLOW + "[Gateway] Stopped." + RESET);
    }

    public void status() {
        System.out.println(CYAN + "[GATEWAY STATUS]" + RESET);
        System.out.println("Running      : " + gatewayRunning);
        System.out.println("Public host  : " + getPublicTcpHost());
        System.out.println("Public port  : " + getPublicTcpPort());
        System.out.println("HTTP route   : "
                + stateStore.get("gateway.route.http.host", stateStore.get("http.host", "127.0.0.1"))
                + ":"
                + stateStore.get("gateway.route.http.port", stateStore.get("http.port", "8080")));
        System.out.println("HTTPS route  : "
                + stateStore.get("gateway.route.https.host", stateStore.get("https.host", "127.0.0.1"))
                + ":"
                + stateStore.get("gateway.route.https.port", stateStore.get("https.port", "8443")));
        System.out.println("TCP default  : " + stateStore.get("gateway.tcp.default", "close"));
    }

    private void acceptLoop() {
        while (gatewayRunning) {
            try {
                Socket clientSocket = publicServerSocket.accept();
                connectionPool.submit(() -> handleIncomingConnection(clientSocket));

            } catch (SocketException e) {
                if (gatewayRunning) {
                    System.out.println(RED + "[Gateway] Socket error: " + e.getMessage() + RESET);
                }

            } catch (Exception e) {
                System.out.println(RED + "[Gateway] Accept error: " + e.getMessage() + RESET);
            }
        }
    }

    private void handleIncomingConnection(Socket clientSocket) {
        try {
            reloadStateConfigQuietly();

            clientSocket.setTcpNoDelay(true);
            clientSocket.setSoTimeout(1500);

            InputStream clientInput = clientSocket.getInputStream();

            byte[] firstBytes = new byte[256];
            int firstLength;

            try {
                firstLength = clientInput.read(firstBytes);

            } catch (SocketTimeoutException timeout) {
                // SSH clients can wait for server banner first.
                routeToSshService(clientSocket, new byte[0], 0);
                return;
            }

            if (firstLength <= 0) {
                closeQuietly(clientSocket);
                return;
            }

            String firstText = new String(firstBytes, 0, firstLength, StandardCharsets.ISO_8859_1);

            if (isHttpRequest(firstText)) {
                routeToHttpService(clientSocket, firstBytes, firstLength);
                return;
            }

            if (isTlsClientHello(firstBytes, firstLength)) {
                routeToHttpsService(clientSocket, firstBytes, firstLength);
                return;
            }

            if (isSshRequest(firstText)) {
                routeToSshService(clientSocket, firstBytes, firstLength);
                return;
            }

            routeToManualTcpService(clientSocket, firstBytes, firstLength);

        } catch (Exception e) {
            closeQuietly(clientSocket);

            try {
                logService.write("[GATEWAY CLIENT ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private boolean isHttpRequest(String firstText) {
        if (!stateStore.getBoolean("gateway.route.http.enabled", true)) {
            return false;
        }

        return firstText.startsWith("GET ")
                || firstText.startsWith("POST ")
                || firstText.startsWith("HEAD ")
                || firstText.startsWith("OPTIONS ")
                || firstText.startsWith("PUT ")
                || firstText.startsWith("DELETE ")
                || firstText.startsWith("PATCH ")
                || firstText.startsWith("PRI * HTTP/2.0");
    }

    private boolean isTlsClientHello(byte[] firstBytes, int firstLength) {
        if (!stateStore.getBoolean("gateway.route.https.enabled", true)) {
            return false;
        }

        if (firstLength < 3) {
            return false;
        }

        int contentType = firstBytes[0] & 0xFF;
        int majorVersion = firstBytes[1] & 0xFF;

        // TLS ClientHello starts with record type 0x16 and version 0x03xx.
        return contentType == 0x16 && majorVersion == 0x03;
    }

    private boolean isSshRequest(String firstText) {
        if (!stateStore.getBoolean("gateway.ssh.enabled", true)) {
            return false;
        }

        return firstText.startsWith("SSH-");
    }

    private void routeToHttpService(Socket clientSocket, byte[] firstBytes, int firstLength) {
        String httpHost = stateStore.get("gateway.route.http.host", stateStore.get("http.host", "127.0.0.1")).trim();
        int httpPort = stateStore.getInt("gateway.route.http.port", stateStore.getInt("http.port", 8080));

        proxyTcp(
                clientSocket,
                firstBytes,
                firstLength,
                httpHost,
                httpPort,
                "HTTP"
        );
    }

    private void routeToHttpsService(Socket clientSocket, byte[] firstBytes, int firstLength) {
        String httpsHost = stateStore.get("gateway.route.https.host", stateStore.get("https.host", "127.0.0.1")).trim();
        int httpsPort = stateStore.getInt("gateway.route.https.port", stateStore.getInt("https.port", 8443));

        proxyTcp(
                clientSocket,
                firstBytes,
                firstLength,
                httpsHost,
                httpsPort,
                "HTTPS"
        );
    }

    private void routeToSshService(Socket clientSocket, byte[] firstBytes, int firstLength) {
        String sshHost = stateStore.get("gateway.ssh.host", "127.0.0.1").trim();
        int sshPort = stateStore.getInt("gateway.ssh.port", 2022);

        proxyTcp(
                clientSocket,
                firstBytes,
                firstLength,
                sshHost,
                sshPort,
                "SSH/SFTP"
        );
    }

    private void routeToManualTcpService(Socket clientSocket, byte[] firstBytes, int firstLength) {
        if (!stateStore.getBoolean("gateway.tcp.enabled", true)) {
            sendUnknownAndClose(clientSocket, firstBytes, firstLength);
            return;
        }

        String defaultRouteName = stateStore.get("gateway.tcp.default", "close").trim();

        if (defaultRouteName.isBlank() || defaultRouteName.equalsIgnoreCase("close")) {
            sendUnknownAndClose(clientSocket, firstBytes, firstLength);
            return;
        }

        TcpRoute route = readTcpRoute(defaultRouteName);

        if (route == null || !route.enabled) {
            sendBackendNotReadyAndClose(
                    clientSocket,
                    "TCP route '" + defaultRouteName + "' is not enabled or not found"
            );
            return;
        }

        proxyTcp(
                clientSocket,
                firstBytes,
                firstLength,
                route.host,
                route.port,
                "TCP/" + route.name
        );
    }

    private TcpRoute readTcpRoute(String routeName) {
        String cleanName = routeName.trim();

        if (cleanName.isBlank()) {
            return null;
        }

        boolean enabled = stateStore.getBoolean("gateway.tcp." + cleanName + ".enabled", false);
        String host = stateStore.get("gateway.tcp." + cleanName + ".host", "127.0.0.1").trim();
        int port = stateStore.getInt("gateway.tcp." + cleanName + ".port", 0);

        if (port <= 0 || port > 65535) {
            return null;
        }

        return new TcpRoute(cleanName, enabled, host, port);
    }

    private List<TcpRoute> readAllManualTcpRoutes() {
        List<TcpRoute> routes = new ArrayList<>();

        String routeNames = stateStore.get("gateway.tcp.routes", "").trim();

        if (routeNames.isBlank()) {
            return routes;
        }

        String[] items = routeNames.split(",");

        for (String item : items) {
            TcpRoute route = readTcpRoute(item.trim());

            if (route != null) {
                routes.add(route);
            }
        }

        return routes;
    }

    private void proxyTcp(
            Socket clientSocket,
            byte[] firstBytes,
            int firstLength,
            String targetHost,
            int targetPort,
            String routeLabel
    ) {
        Socket backendSocket = null;

        try {
            clientSocket.setSoTimeout(0);
            clientSocket.setTcpNoDelay(true);
            clientSocket.setKeepAlive(true);

            backendSocket = new Socket();
            backendSocket.setTcpNoDelay(true);
            backendSocket.setKeepAlive(true);
            backendSocket.setSoTimeout(0);
            backendSocket.connect(new InetSocketAddress(targetHost, targetPort), 3000);

            if (firstLength > 0) {
                backendSocket.getOutputStream().write(firstBytes, 0, firstLength);
                backendSocket.getOutputStream().flush();
            }

            System.out.println(CYAN + "[Gateway] " + routeLabel + " -> " + targetHost + ":" + targetPort + RESET);
            logService.write("[GATEWAY PROXY] " + routeLabel + " -> " + targetHost + ":" + targetPort + "\n");

            Socket finalBackendSocket = backendSocket;

            Thread clientToBackend = new Thread(
                    () -> pipe(clientSocket, finalBackendSocket),
                    "gateway-client-to-" + safeThreadName(routeLabel)
            );

            Thread backendToClient = new Thread(
                    () -> pipe(finalBackendSocket, clientSocket),
                    "gateway-" + safeThreadName(routeLabel) + "-to-client"
            );

            clientToBackend.start();
            backendToClient.start();

        } catch (Exception e) {
            closeQuietly(backendSocket);

            if (routeLabel.equals("HTTP")) {
                sendHttpGatewayErrorAndClose(clientSocket, 502, routeLabel + " backend is not ready: " + e.getMessage());
            } else {
                sendBackendNotReadyAndClose(
                        clientSocket,
                        routeLabel + " backend is not ready: " + e.getMessage()
                );
            }

            try {
                logService.write("[GATEWAY PROXY ERROR] " + routeLabel + " - " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private void pipe(Socket fromSocket, Socket toSocket) {
        try {
            InputStream input = fromSocket.getInputStream();
            OutputStream output = toSocket.getOutputStream();

            byte[] buffer = new byte[8192];

            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                output.flush();
            }

        } catch (IOException e) {
            try {
                logService.write("[GATEWAY PIPE CLOSED] "
                        + e.getClass().getSimpleName()
                        + " - "
                        + e.getMessage()
                        + "\n");
            } catch (IOException ignored) {
            }

        } finally {
            closeQuietly(fromSocket);
            closeQuietly(toSocket);
        }
    }

    private void sendUnknownAndClose(Socket clientSocket, byte[] firstBytes, int firstLength) {
        try {
            String hex = toHex(firstBytes, Math.min(firstLength, 16));

            System.out.println(YELLOW + "[Gateway] Unknown protocol: " + hex + RESET);
            logService.write("[GATEWAY UNKNOWN] " + hex + "\n");

            String message = "Mini Java Gateway: unknown protocol\r\n";
            clientSocket.getOutputStream().write(message.getBytes(StandardCharsets.UTF_8));
            clientSocket.getOutputStream().flush();

        } catch (IOException ignored) {
        } finally {
            closeQuietly(clientSocket);
        }
    }

    private void sendHttpGatewayErrorAndClose(Socket clientSocket, int statusCode, String message) {
        try {
            byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
            String response = "HTTP/1.1 " + statusCode + " Bad Gateway\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            clientSocket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            clientSocket.getOutputStream().write(body);
            clientSocket.getOutputStream().flush();
        } catch (IOException ignored) {
        } finally {
            closeQuietly(clientSocket);
        }
    }

    private void sendBackendNotReadyAndClose(Socket clientSocket, String message) {
        try {
            String response = message + "\r\n";
            clientSocket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            clientSocket.getOutputStream().flush();

        } catch (IOException ignored) {
        } finally {
            closeQuietly(clientSocket);
        }
    }

    private void printGatewayStartup(String publicTcpHost, int publicTcpPort) throws IOException {
        reloadStateConfigQuietly();

        boolean httpRouteEnabled = stateStore.getBoolean("gateway.route.http.enabled", true);
        boolean httpsRouteEnabled = stateStore.getBoolean("gateway.route.https.enabled", true);
        boolean sshEnabled = stateStore.getBoolean("gateway.ssh.enabled", true);
        boolean tcpEnabled = stateStore.getBoolean("gateway.tcp.enabled", true);

        String httpHost = stateStore.get("gateway.route.http.host", stateStore.get("http.host", "127.0.0.1"));
        int httpPort = stateStore.getInt("gateway.route.http.port", stateStore.getInt("http.port", 8080));

        String httpsHost = stateStore.get("gateway.route.https.host", stateStore.get("https.host", "127.0.0.1"));
        int httpsPort = stateStore.getInt("gateway.route.https.port", stateStore.getInt("https.port", 8443));

        String sshHost = stateStore.get("gateway.ssh.host", "127.0.0.1");
        int sshPort = stateStore.getInt("gateway.ssh.port", 2022);

        String tcpDefault = stateStore.get("gateway.tcp.default", "close");

        System.out.println(GREEN + "==================================================" + RESET);
        System.out.println(GREEN + " Gateway Router" + RESET);
        System.out.println(GREEN + "==================================================" + RESET);

        System.out.println(CYAN + " Public TCP  : " + publicTcpHost + ":" + publicTcpPort + RESET);

        System.out.println();
        System.out.println(YELLOW + " HTTP Route" + RESET);
        System.out.println("  Status     : " + formatStatus(httpRouteEnabled));
        System.out.println("  Target     : " + httpHost + ":" + httpPort);
        System.out.println("  Role       : forward HTTP traffic only, does not serve website files");

        System.out.println();
        System.out.println(YELLOW + " HTTPS Route" + RESET);
        System.out.println("  Status     : " + formatStatus(httpsRouteEnabled));
        System.out.println("  Target     : " + httpsHost + ":" + httpsPort);
        System.out.println("  Role       : forward TLS/HTTPS traffic only, does not terminate TLS");

        System.out.println();
        System.out.println(YELLOW + " SSH / SFTP Proxy" + RESET);
        System.out.println("  Status     : " + formatStatus(sshEnabled));
        System.out.println("  Target     : " + sshHost + ":" + sshPort);

        System.out.println();
        System.out.println(YELLOW + " Manual TCP Routes" + RESET);
        System.out.println("  Status     : " + formatStatus(tcpEnabled));
        System.out.println("  Default    : " + tcpDefault);

        List<TcpRoute> routes = readAllManualTcpRoutes();

        if (routes.isEmpty()) {
            System.out.println("  Routes     : none");
        } else {
            System.out.println("  Routes     :");

            for (TcpRoute route : routes) {
                System.out.println("    - "
                        + route.name
                        + " | "
                        + formatStatus(route.enabled)
                        + " | "
                        + route.host
                        + ":"
                        + route.port);
            }
        }

        System.out.println();
    }

    private String getPublicTcpHost() {
        String host = stateStore.get("gateway.public.host", "0.0.0.0").trim();

        if (host.isBlank()) {
            return "0.0.0.0";
        }

        return host;
    }

    private int getPublicTcpPort() {
        String configured = stateStore.get("gateway.public.port", "auto").trim();

        if (!configured.equalsIgnoreCase("auto")) {
            try {
                int port = Integer.parseInt(configured.trim());

                if (port > 0 && port <= 65535) {
                    return port;
                }

            } catch (Exception ignored) {
            }

            return 4848;
        }

        String value = System.getenv().getOrDefault("SERVER_PORT", "8080");

        try {
            int port = Integer.parseInt(value.trim());

            if (port > 0 && port <= 65535) {
                return port;
            }

        } catch (Exception ignored) {
        }

        return 4848;
    }

    private void reloadStateConfigQuietly() {
        try {
            stateStore.load();
        } catch (IOException e) {
            try {
                logService.write("[GATEWAY CONFIG RELOAD ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private String safeThreadName(String value) {
        return value.toLowerCase()
                .replace("/", "-")
                .replace(" ", "-")
                .replace(":", "-");
    }

    private String toHex(byte[] data, int length) {
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; i++) {
            if (i > 0) {
                builder.append(' ');
            }

            builder.append(String.format("%02X", data[i]));
        }

        return builder.toString();
    }

    private void closeQuietly(Socket socket) {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private String formatStatus(boolean enabled) {
        return enabled ? "ON" : "OFF";
    }

    private static class TcpRoute {
        private final String name;
        private final boolean enabled;
        private final String host;
        private final int port;

        private TcpRoute(String name, boolean enabled, String host, int port) {
            this.name = name;
            this.enabled = enabled;
            this.host = host;
            this.port = port;
        }
    }
}
