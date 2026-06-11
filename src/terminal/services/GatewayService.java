package terminal.services;

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

import terminal.system.LogService;
import terminal.system.StateStore;

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

    public void start() {
        if (gatewayRunning) {
            System.out.println(YELLOW + "[Gateway] Đang chạy rồi." + RESET);
            return;
        }

        int publicTcpPort = getPublicTcpPort();

        try {
            publicServerSocket = new ServerSocket();
            publicServerSocket.setReuseAddress(true);
            publicServerSocket.bind(new InetSocketAddress("0.0.0.0", publicTcpPort));

            gatewayRunning = true;

            printGatewayStartup(publicTcpPort);

            Thread acceptThread = new Thread(this::acceptLoop, "mjt-gateway-accept-loop");
            acceptThread.setDaemon(false);
            acceptThread.start();

            logService.write("[GATEWAY START] 0.0.0.0:" + publicTcpPort + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Start lỗi: " + e.getMessage() + RESET);

            try {
                logService.write("[GATEWAY START ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    public void stop() {
        gatewayRunning = false;

        try {
            if (publicServerSocket != null) {
                publicServerSocket.close();
            }
        } catch (IOException ignored) {
        }

        System.out.println(YELLOW + "[Gateway] Stopped." + RESET);
    }

    private void acceptLoop() {
        while (gatewayRunning) {
            try {
                Socket clientSocket = publicServerSocket.accept();
                connectionPool.submit(() -> handleIncomingConnection(clientSocket));

            } catch (SocketException e) {
                if (gatewayRunning) {
                    System.out.println(RED + "[Gateway] Socket lỗi: " + e.getMessage() + RESET);
                }

            } catch (Exception e) {
                System.out.println(RED + "[Gateway] Accept lỗi: " + e.getMessage() + RESET);
            }
        }
    }

    private void handleIncomingConnection(Socket clientSocket) {
        try {
            reloadStateConfigQuietly();

            clientSocket.setTcpNoDelay(true);
            clientSocket.setSoTimeout(1500);

            InputStream clientInput = clientSocket.getInputStream();
            OutputStream clientOutput = clientSocket.getOutputStream();

            byte[] firstBytes = new byte[64];
            int firstLength;

            try {
                firstLength = clientInput.read(firstBytes);

            } catch (SocketTimeoutException timeout) {
                // SSH client đôi khi chờ server gửi banner trước.
                routeToSshService(clientSocket, new byte[0], 0);
                return;
            }

            if (firstLength <= 0) {
                closeQuietly(clientSocket);
                return;
            }

            String firstText = new String(firstBytes, 0, firstLength, StandardCharsets.ISO_8859_1);

            if (isHttpRequest(firstText)) {
                handleHttpService(firstText, clientInput, clientOutput);
                closeQuietly(clientSocket);
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
        if (!getConfigBoolean("gateway.http.enabled", true)) {
            return false;
        }

        return firstText.startsWith("GET ")
                || firstText.startsWith("POST ")
                || firstText.startsWith("HEAD ")
                || firstText.startsWith("OPTIONS ")
                || firstText.startsWith("PUT ")
                || firstText.startsWith("DELETE ")
                || firstText.startsWith("PATCH ");
    }

    private boolean isSshRequest(String firstText) {
        if (!getConfigBoolean("gateway.ssh.enabled", true)) {
            return false;
        }

        return firstText.startsWith("SSH-");
    }

    private void handleHttpService(
            String firstText,
            InputStream clientInput,
            OutputStream clientOutput
    ) throws IOException {
        String requestLine = firstText.split("\\R", 2)[0];

        String body = """
                <!doctype html>
                <html>
                <head>
                    <meta charset="utf-8">
                    <title>Mini Java Gateway</title>
                    <style>
                        body {
                            font-family: Arial, sans-serif;
                            background: #f6f8fb;
                            padding: 40px;
                        }
                        .card {
                            background: #ffffff;
                            border-radius: 16px;
                            padding: 24px;
                            max-width: 820px;
                            box-shadow: 0 4px 18px rgba(0,0,0,.08);
                        }
                        h1 {
                            margin-top: 0;
                            color: #154cc3;
                        }
                        code {
                            background: #eef2ff;
                            padding: 3px 6px;
                            border-radius: 6px;
                        }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <h1>Mini Java Gateway OK</h1>
                        <p><b>HTTP</b> đang chạy riêng trong Gateway.</p>
                        <p><b>SSH/SFTP</b> route qua <code>gateway.ssh.host</code> và <code>gateway.ssh.port</code>.</p>
                        <p><b>TCP service</b> route thủ công qua <code>gateway.tcp.routes</code> và <code>gateway.tcp.default</code>.</p>
                    </div>
                </body>
                </html>
                """;

        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        String header = ""
                + "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bodyBytes.length + "\r\n"
                + "Connection: close\r\n"
                + "\r\n";

        clientOutput.write(header.getBytes(StandardCharsets.UTF_8));
        clientOutput.write(bodyBytes);
        clientOutput.flush();

        System.out.println(CYAN + "[HTTP] " + requestLine + RESET);
        logService.write("[HTTP] " + requestLine + "\n");
    }

    private void routeToSshService(Socket clientSocket, byte[] firstBytes, int firstLength) {
        String sshHost = getConfigString("gateway.ssh.host", "127.0.0.1");
        int sshPort = getConfigInt("gateway.ssh.port", 2022);

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
        if (!getConfigBoolean("gateway.tcp.enabled", true)) {
            sendUnknownAndClose(clientSocket, firstBytes, firstLength);
            return;
        }

        String defaultRouteName = getConfigString("gateway.tcp.default", "close");

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

        boolean enabled = getConfigBoolean("gateway.tcp." + cleanName + ".enabled", false);
        String host = getConfigString("gateway.tcp." + cleanName + ".host", "127.0.0.1");
        int port = getConfigInt("gateway.tcp." + cleanName + ".port", 0);

        if (port <= 0 || port > 65535) {
            return null;
        }

        return new TcpRoute(cleanName, enabled, host, port);
    }

    private List<TcpRoute> readAllManualTcpRoutes() {
        List<TcpRoute> routes = new ArrayList<>();

        String routeNames = getConfigString("gateway.tcp.routes", "");

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

            sendBackendNotReadyAndClose(
                    clientSocket,
                    routeLabel + " backend is not ready: " + e.getMessage()
            );

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

        } catch (SocketTimeoutException e) {
            try {
                logService.write("[GATEWAY PIPE TIMEOUT] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
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

    private void printGatewayStartup(int publicTcpPort) throws IOException {
        reloadStateConfigQuietly();

        System.out.println(GREEN + "[Gateway] Started." + RESET);
        System.out.println("Public TCP : 0.0.0.0:" + publicTcpPort);
        System.out.println("HTTP       : " + getConfigBoolean("gateway.http.enabled", true));
        System.out.println("SSH/SFTP   : "
                + getConfigString("gateway.ssh.host", "127.0.0.1")
                + ":"
                + getConfigInt("gateway.ssh.port", 2022));
        System.out.println("TCP        : " + getConfigBoolean("gateway.tcp.enabled", true));
        System.out.println("TCP default: " + getConfigString("gateway.tcp.default", "close"));

        List<TcpRoute> routes = readAllManualTcpRoutes();

        if (routes.isEmpty()) {
            System.out.println("TCP routes : none");
        } else {
            System.out.println("TCP routes :");

            for (TcpRoute route : routes) {
                System.out.println("  - "
                        + route.name
                        + " enabled="
                        + route.enabled
                        + " target="
                        + route.host
                        + ":"
                        + route.port);
            }
        }

        System.out.println();
    }

    private int getPublicTcpPort() {
        String value = System.getenv().getOrDefault("SERVER_PORT", "4848");

        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return 4848;
        }
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

    private String getConfigString(String key, String defaultValue) {
        return stateStore.get(key, defaultValue).trim();
    }

    private int getConfigInt(String key, int defaultValue) {
        return stateStore.getInt(key, defaultValue);
    }

    private boolean getConfigBoolean(String key, boolean defaultValue) {
        return stateStore.getBoolean(key, defaultValue);
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