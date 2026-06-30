package main.java.mjt.system;

import java.io.IOException;
import java.net.InetSocketAddress;

import net.kyori.adventure.text.Component;

import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;

public class KeepAliveBotService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final StateStore stateStore;
    private final LogService logService;

    private volatile boolean running = false;
    private volatile boolean connecting = false;
    private volatile boolean connected = false;
    private volatile boolean stopRequested = false;

    private Thread workerThread;
    private volatile ClientSession client;

    public KeepAliveBotService(
            StateStore stateStore,
            LogService logService
    ) {
        this.stateStore = stateStore;
        this.logService = logService;
    }

    public synchronized void start() {
        if (running) {
            System.out.println(YELLOW + "[BOT] KeepAlive bot is already running." + RESET);
            return;
        }

        running = true;
        stopRequested = false;

        workerThread = new Thread(this::runLoop, "mjt-keepalive-bot");
        workerThread.setDaemon(true);
        workerThread.start();

        System.out.println(GREEN + "[BOT] KeepAlive bot loop started." + RESET);

        try {
            logService.write("[BOT START REQUEST]\n");
        } catch (IOException ignored) {
        }
    }

    public synchronized void stop() {
        if (!running && !connected && client == null) {
            return;
        }

        stopRequested = true;
        running = false;

        ClientSession localClient = client;

        if (localClient != null) {
            try {
                localClient.disconnect(Component.text("MJT bot stopped"));
            } catch (Exception ignored) {
            }
        }

        if (workerThread != null) {
            workerThread.interrupt();
        }

        connecting = false;
        connected = false;
        client = null;

        System.out.println(YELLOW + "[BOT] KeepAlive bot stopped." + RESET);

        try {
            logService.write("[BOT STOP]\n");
        } catch (IOException ignored) {
        }
    }

    public void printStatus() {
        System.out.println(CYAN + "[BOT STATUS]" + RESET);
        System.out.println("Loop running : " + running);
        System.out.println("Connecting   : " + connecting);
        System.out.println("Connected    : " + connected);
        System.out.println("Enabled      : " + stateStore.get("bot.enabled", "false"));
        System.out.println("Host         : " + stateStore.get("bot.host", "127.0.0.1"));
        System.out.println("Port         : " + stateStore.get("bot.port", "25565"));
        System.out.println("Username     : " + stateStore.get("bot.username", "MJT_Renew"));
        System.out.println("Reconnect    : " + stateStore.get("bot.reconnectSeconds", "30") + " seconds");
        System.out.println("Auto start   : " + stateStore.get("bot.autoStartWithMinecraft", "true"));
        System.out.println("Auto stop    : " + stateStore.get("bot.autoStopWithMinecraft", "true"));
    }

    public void setConfig(String key, String value) throws IOException {
        String realKey = normalizeKey(key);

        if (realKey == null) {
            System.out.println(RED + "[BOT] Invalid key: " + key + RESET);
            printSetHelp();
            return;
        }

        value = normalizeValue(realKey, value);
        stateStore.set(realKey, value);

        System.out.println(GREEN + "[BOT] Saved " + realKey + " = " + value + RESET);
        logService.write("[BOT SET] " + realKey + " = " + value + "\n");
    }

    private void runLoop() {
        while (!stopRequested) {
            ClientSession session = null;

            try {
                stateStore.load();

                String host = stateStore.get("bot.host", "127.0.0.1").trim();
                int port = stateStore.getInt("bot.port", 25565);
                String username = stateStore.get("bot.username", "MJT_Renew").trim();

                if (username.isBlank()) {
                    username = "MJT_Renew";
                }

                System.out.println(CYAN + "[BOT] Connecting to " + host + ":" + port + " as " + username + RESET);
                logService.write("[BOT CONNECTING] " + host + ":" + port + " user=" + username + "\n");

                session = connectOnce(host, port, username);
                waitForSessionEnd(session);

            } catch (Exception e) {
                connecting = false;
                connected = false;

                try {
                    logService.write("[BOT CONNECT ERROR] " + e.getClass().getSimpleName() + " - " + e.getMessage() + "\n");
                } catch (IOException ignored) {
                }

                if (!stopRequested) {
                    System.out.println(YELLOW + "[BOT] Connect failed: " + e.getMessage() + RESET);
                }

            } finally {
                if (client == session) {
                    client = null;
                }

                connecting = false;
                connected = false;
            }

            if (!stopRequested) {
                int reconnectSeconds = stateStore.getInt("bot.reconnectSeconds", 30);

                if (reconnectSeconds < 5) {
                    reconnectSeconds = 5;
                }

                System.out.println(YELLOW + "[BOT] Reconnect in " + reconnectSeconds + " seconds..." + RESET);
                sleepQuietly(reconnectSeconds * 1000L);
            }
        }

        running = false;
        connecting = false;
        connected = false;
        client = null;
    }

    private ClientSession connectOnce(
            String host,
            int port,
            String username
    ) {
        MinecraftProtocol protocol = new MinecraftProtocol(username);

        final ClientSession localClient = ClientNetworkSessionFactory.factory()
                .setRemoteSocketAddress(new InetSocketAddress(host, port))
                .setProtocol(protocol)
                .create();

        localClient.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());

        localClient.addListener(new SessionAdapter() {
            @Override
            public void packetReceived(Session session, Packet packet) {
                if (packet instanceof ClientboundLoginPacket) {
                    connecting = false;
                    connected = true;

                    System.out.println(GREEN + "[BOT] Joined Minecraft server as " + username + RESET);

                    try {
                        logService.write("[BOT JOINED] " + username + "\n");
                    } catch (IOException ignored) {
                    }
                }
            }

            @Override
            public void disconnected(DisconnectedEvent event) {
                connecting = false;
                connected = false;

                if (client == localClient) {
                    client = null;
                }

                if (!stopRequested) {
                    System.out.println(YELLOW + "[BOT] Disconnected: " + event.getReason() + RESET);
                }

                try {
                    logService.write("[BOT DISCONNECTED] " + event.getReason() + "\n");
                } catch (IOException ignored) {
                }
            }
        });

        connecting = true;
        connected = false;
        client = localClient;
        localClient.connect();

        return localClient;
    }

    private void waitForSessionEnd(ClientSession session) {
        long startedAt = System.currentTimeMillis();
        long connectTimeoutMillis = 30_000L;

        while (!stopRequested && client == session) {
            if (!connected
                    && !session.isConnected()
                    && System.currentTimeMillis() - startedAt > connectTimeoutMillis) {

                System.out.println(YELLOW + "[BOT] Connect timeout. Reconnecting..." + RESET);

                try {
                    logService.write("[BOT CONNECT TIMEOUT]\n");
                } catch (IOException ignored) {
                }

                try {
                    session.disconnect(Component.text("MJT bot connect timeout"));
                } catch (Exception ignored) {
                }

                if (client == session) {
                    client = null;
                }

                break;
            }

            sleepQuietly(1000L);
        }
    }

    private String normalizeKey(String key) {
        if (key == null) {
            return null;
        }

        String lower = key.toLowerCase().trim();

        switch (lower) {
            case "enabled":
            case "enable":
            case "bot.enabled":
                return "bot.enabled";

            case "host":
            case "ip":
            case "address":
            case "bot.host":
                return "bot.host";

            case "port":
            case "bot.port":
                return "bot.port";

            case "name":
            case "user":
            case "username":
            case "bot.username":
                return "bot.username";

            case "reconnect":
            case "reconnect-seconds":
            case "reconnectseconds":
            case "delay":
            case "bot.reconnectseconds":
                return "bot.reconnectSeconds";

            case "auto-start":
            case "autostart":
            case "auto-start-with-minecraft":
            case "bot.autostartwithminecraft":
                return "bot.autoStartWithMinecraft";

            case "auto-stop":
            case "autostop":
            case "auto-stop-with-minecraft":
            case "bot.autostopwithminecraft":
                return "bot.autoStopWithMinecraft";

            default:
                return null;
        }
    }

    private String normalizeValue(
            String realKey,
            String value
    ) {
        String clean = value == null ? "" : value.trim();

        if (realKey.equals("bot.enabled")
                || realKey.equals("bot.autoStartWithMinecraft")
                || realKey.equals("bot.autoStopWithMinecraft")) {
            return normalizeBoolean(clean);
        }

        if (realKey.equals("bot.port")) {
            int port = parseNumber(clean, 25565);

            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("Invalid bot port.");
            }

            return String.valueOf(port);
        }

        if (realKey.equals("bot.reconnectSeconds")) {
            int seconds = parseNumber(clean, 30);

            if (seconds < 5) {
                seconds = 5;
            }

            return String.valueOf(seconds);
        }

        if (realKey.equals("bot.username")) {
            clean = clean.replaceAll("[^A-Za-z0-9_]", "_");

            if (clean.isBlank()) {
                clean = "MJT_Renew";
            }

            if (clean.length() > 16) {
                clean = clean.substring(0, 16);
            }
        }

        return clean;
    }

    private String normalizeBoolean(String value) {
        if (value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("1")
                || value.equalsIgnoreCase("on")
                || value.equalsIgnoreCase("enable")
                || value.equalsIgnoreCase("enabled")) {
            return "true";
        }

        return "false";
    }

    private int parseNumber(
            String value,
            int defaultValue
    ) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopRequested = true;
        }
    }

    private void printSetHelp() {
        System.out.println(YELLOW + "Valid bot keys:" + RESET);
        System.out.println(".mjt bot set enabled true");
        System.out.println(".mjt bot set host 127.0.0.1");
        System.out.println(".mjt bot set port 25565");
        System.out.println(".mjt bot set username MJT_Renew");
        System.out.println(".mjt bot set reconnect 30");
        System.out.println(".mjt bot set auto-start true");
        System.out.println(".mjt bot set auto-stop true");
    }
}
