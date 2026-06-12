package terminal.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CommandCenter {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final CommandContext context;

    public CommandCenter(CommandContext context) {
        this.context = context;
    }

    public void handle(String command) throws IOException {
        context.logService().write("\n========================================\n");
        context.logService().write("[INPUT] " + command + "\n");

        // Set . is a default prefix
        if (command.startsWith(".")) {
            String mjtCommand =
                    command.substring(1)
                           .trim();
        
            if (handleInternalCommand(mjtCommand)) {
                return;
            }
        
            System.out.println(
                    "Unknown MJT command"
            );
            return;
        }

        if (context.commandGuard().isBlocked(command)) {
            return;
        }

        context.shellRunner().run(
                command,
                context.runtimeConfig().getCurrentDir(),
                context.runtimeConfig().getCommandTimeoutSeconds()
        );
    }

    private boolean handleInternalCommand(String command) throws IOException {
        if (command.equalsIgnoreCase("help")) {
            printHelp();
            return true;
        }

        if (command.equalsIgnoreCase("public-ip")) {
            context.publicIpService().printPublicIpv4();
            return true;
        }

        if (command.equalsIgnoreCase("timeout")) {
            showTimeout();
            return true;
        }

        if (command.startsWith("timeout ")) {
            setTimeout(command);
            return true;
        }

        if (command.equalsIgnoreCase("pwd")) {
            System.out.println(context.runtimeConfig().getCurrentDir());
            context.logService().write("[PWD] " + context.runtimeConfig().getCurrentDir() + "\n");
            return true;
        }

        if (command.equalsIgnoreCase("clear") || command.equalsIgnoreCase("cls")) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            return true;
        }

        if (command.equalsIgnoreCase("cd")) {
            changeDirectory(System.getProperty("user.home"));
            return true;
        }

        if (command.startsWith("cd ")) {
            changeDirectory(command.substring(3).trim());
            return true;
        }

        if (command.equalsIgnoreCase("exit")) {
            System.out.println(YELLOW + "The exit command has been blocked to prevent the server from going offline." + RESET);
            System.out.println(YELLOW + "To shut down Mini Java Terminal, type: .mjt-exit" + RESET);
            context.logService().write("[BLOCKED EXIT]\n");
            return true;
        }       

        if (command.equalsIgnoreCase("mjt-exit")) {
            System.out.println(RED + "Shutting down Mini Java Terminal..." + RESET);
            context.logService().write("[MJT EXIT]\n");
            System.exit(0);
            return true;
        }

        // Cloudflare DDNS
        if (command.equalsIgnoreCase("cloudflare-show")) {
            context.cloudflareDnsService().showConfig();
            return true;
        }

        if (command.startsWith("cloudflare-set ")) {
            handleCloudflareSet(command);
            return true;
        }

        if (command.equalsIgnoreCase("cloudflare-ddns-once")) {
            context.cloudflareDnsService().updateOnce();
            return true;
        }

        if (command.equalsIgnoreCase("cloudflare-ddns-start")) {
            context.cloudflareDnsService().startLoop();
            return true;
        }

        if (command.equalsIgnoreCase("cloudflare-ddns-stop")) {
            context.cloudflareDnsService().stopLoop();
            return true;
        }

        if (command.equalsIgnoreCase("cloudflare-ddns-status")) {
            context.cloudflareDnsService().printStatus();
            return true;
        }

        // SSH + SFTP server
        if (command.equalsIgnoreCase("ssh-show")) {
            context.sshServerService().showConfig();
            return true;
        }

        if (command.startsWith("ssh-set ")) {
            handleSshSet(command);
            return true;
        }

        if (command.equalsIgnoreCase("ssh-start")) {
            context.sshServerService().start();
            return true;
        }

        if (command.equalsIgnoreCase("ssh-stop")) {
            context.sshServerService().stop();
            return true;
        }

        if (command.equalsIgnoreCase("ssh-status")) {
            context.sshServerService().status();
            return true;
        }

        // Backward-compatible aliases for old SFTP commands
        if (command.equalsIgnoreCase("sftp-show")) {
            context.sshServerService().showConfig();
            return true;
        }

        if (command.startsWith("sftp-set ")) {
            handleSftpAliasSet(command);
            return true;
        }

        if (command.equalsIgnoreCase("sftp-start")) {
            context.sshServerService().start();
            return true;
        }

        if (command.equalsIgnoreCase("sftp-stop")) {
            context.sshServerService().stop();
            return true;
        }

        if (command.equalsIgnoreCase("sftp-status")) {
            context.sshServerService().status();
            return true;
        }

                // Gateway
        if (command.equalsIgnoreCase("gateway-help")) {
            printGatewayHelp();
            return true;
        }

        if (command.equalsIgnoreCase("gateway-show")) {
            showGatewayConfig();
            return true;
        }

        if (command.startsWith("gateway-set ")) {
            handleGatewaySet(command);
            return true;
        }

        if (command.startsWith("gateway-default ")) {
            handleGatewayDefault(command);
            return true;
        }

        if (command.startsWith("gateway-route-add ")) {
            handleGatewayRouteAdd(command);
            return true;
        }

        if (command.startsWith("gateway-route-remove ")) {
            handleGatewayRouteRemove(command);
            return true;
        }

        if (command.startsWith("gateway-route-enable ")) {
            handleGatewayRouteToggle(command, true);
            return true;
        }

        if (command.startsWith("gateway-route-disable ")) {
            handleGatewayRouteToggle(command, false);
            return true;
        }

        return false;
    }

     private void handlePrefixSet(String command) {
        String raw = command.substring("prefix-set".length()).trim();
        handleSshSetRaw(raw);
    }

    private void changeDirectory(String target) throws IOException {
        Path currentDir = context.runtimeConfig().getCurrentDir();
        Path newDir;

        if (target.equals("~")) {
            newDir = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        } else {
            newDir = currentDir.resolve(target).normalize();
        }

        if (Files.exists(newDir) && Files.isDirectory(newDir)) {
            context.runtimeConfig().setCurrentDir(newDir);
            System.out.println(CYAN + "Current dir: " + context.runtimeConfig().getCurrentDir() + RESET);
            context.logService().write("[CD] " + context.runtimeConfig().getCurrentDir() + "\n");
        } else {
            System.out.println(RED + "Directory not found: " + newDir + RESET);
            context.logService().write("[CD ERROR] Directory not found: " + newDir + "\n");
        }
    }

    private void setTimeout(String command) {
        String value = command.substring("timeout ".length()).trim();

        try {
            int seconds = Integer.parseInt(value);

            context.runtimeConfig().setCommandTimeoutSeconds(seconds);

            if (seconds == 0) {
                System.out.println(GREEN + "Command timeout: unlimited." + RESET);
            } else {
                System.out.println(GREEN + "Command timeout: " + seconds + " seconds." + RESET);
            }

        } catch (NumberFormatException e) {
            System.out.println(RED + "Usage: timeout <seconds>" + RESET);
            System.out.println("Example: timeout 0 or timeout 60");
        } catch (IllegalArgumentException e) {
            System.out.println(RED + e.getMessage() + RESET);
        }
    }

    private void showTimeout() {
        int seconds = context.runtimeConfig().getCommandTimeoutSeconds();

        if (seconds == 0) {
            System.out.println(GREEN + "Current command timeout: unlimited." + RESET);
        } else {
            System.out.println(GREEN + "Current command timeout: " + seconds + " seconds." + RESET);
        }
    }

    private void handleCloudflareSet(String command) {
        String raw = command.substring("cloudflare-set ".length()).trim();

        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Usage: .cloudflare-set <key> <value>" + RESET);
            System.out.println("Example: .cloudflare-set name document.io.vn");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        try {
            context.cloudflareDnsService().setConfig(key, value);
        } catch (Exception e) {
            System.out.println(RED + "[Cloudflare] Error saving config: " + e.getMessage() + RESET);
        }
    }

    private void handleSshSet(String command) {
        String raw = command.substring("ssh-set ".length()).trim();
        handleSshSetRaw(raw);
    }

    private void handleSftpAliasSet(String command) {
        String raw = command.substring("sftp-set ".length()).trim();
        handleSshSetRaw(raw);
    }

    private void handleSshSetRaw(String raw) {
        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Usage: .ssh-set <key> <value>" + RESET);
            System.out.println("Example: .ssh-set port 40078");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        try {
            context.sshServerService().setConfig(key, value);
        } catch (Exception e) {
            System.out.println(RED + "[SSH] Error saving config: " + e.getMessage() + RESET);
        }
    }

        private void handleGatewaySet(String command) {
        String raw = command.substring("gateway-set ".length()).trim();

        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Usage: .gateway-set <key> <value>" + RESET);
            System.out.println("Example: .gateway-set gateway.http.enabled true");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        if (!key.startsWith("gateway.")) {
            System.out.println(RED + "Gateway key must start with gateway." + RESET);
            return;
        }

        try {
            context.stateStore().set(key, value);
            System.out.println(GREEN + "[Gateway] Saved " + key + " = " + value + RESET);
            context.logService().write("[GATEWAY SET] " + key + " = " + value + "\n");
        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Error saving config: " + e.getMessage() + RESET);
        }
    }

    private void handleGatewayDefault(String command) {
        String routeName = command.substring("gateway-default ".length()).trim();

        if (routeName.isBlank()) {
            System.out.println(RED + "Usage: .gateway-default <route|close>" + RESET);
            return;
        }

        if (!routeName.equalsIgnoreCase("close") && !isValidGatewayRouteName(routeName)) {
            System.out.println(RED + "Route name is invalid. Use only letters, numbers, -, _" + RESET);
            return;
        }

        try {
            context.stateStore().set("gateway.tcp.default", routeName);
            System.out.println(GREEN + "[Gateway] Default TCP route = " + routeName + RESET);
            context.logService().write("[GATEWAY DEFAULT] " + routeName + "\n");
        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Error: " + e.getMessage() + RESET);
        }
    }

    private void handleGatewayRouteAdd(String command) {
        String raw = command.substring("gateway-route-add ".length()).trim();
        String[] parts = raw.split("\\s+");

        if (parts.length < 3) {
            System.out.println(RED + "Usage: .gateway-route-add <name> <host> <port>" + RESET);
            System.out.println("Example: .gateway-route-add mc 127.0.0.1 25565");
            return;
        }

        String name = parts[0].trim();
        String host = parts[1].trim();
        String portText = parts[2].trim();

        if (!isValidGatewayRouteName(name)) {
            System.out.println(RED + "Route name is invalid. Use only letters, numbers, -, _" + RESET);
            return;
        }

        int port;

        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            System.out.println(RED + "Port must be a number." + RESET);
            return;
        }

        if (port <= 0 || port > 65535) {
            System.out.println(RED + "Invalid port." + RESET);
            return;
        }

        try {
            List<String> routes = getGatewayRouteNames();

            if (!routes.contains(name)) {
                routes.add(name);
            }

            saveGatewayRouteNames(routes);

            context.stateStore().set("gateway.tcp." + name + ".enabled", "true");
            context.stateStore().set("gateway.tcp." + name + ".host", host);
            context.stateStore().set("gateway.tcp." + name + ".port", String.valueOf(port));
            context.stateStore().set("gateway.tcp.enabled", "true");

            System.out.println(GREEN + "[Gateway] Added route: " + name + " -> " + host + ":" + port + RESET);
            context.logService().write("[GATEWAY ROUTE ADD] " + name + " -> " + host + ":" + port + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Error adding route: " + e.getMessage() + RESET);
        }
    }

    private void handleGatewayRouteRemove(String command) {
        String name = command.substring("gateway-route-remove ".length()).trim();

        if (!isValidGatewayRouteName(name)) {
            System.out.println(RED + "Route name is invalid." + RESET);
            return;
        }

        try {
            List<String> routes = getGatewayRouteNames();
            routes.remove(name);
            saveGatewayRouteNames(routes);

            context.stateStore().remove("gateway.tcp." + name + ".enabled");
            context.stateStore().remove("gateway.tcp." + name + ".host");
            context.stateStore().remove("gateway.tcp." + name + ".port");

            String currentDefault = context.stateStore().get("gateway.tcp.default", "close");

            if (currentDefault.equalsIgnoreCase(name)) {
                context.stateStore().set("gateway.tcp.default", "close");
            }

            System.out.println(GREEN + "[Gateway] Removed route: " + name + RESET);
            context.logService().write("[GATEWAY ROUTE REMOVE] " + name + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Error removing route: " + e.getMessage() + RESET);
        }
    }

    private void handleGatewayRouteToggle(String command, boolean enabled) {
        String prefix = enabled ? "gateway-route-enable " : "gateway-route-disable ";
        String name = command.substring(prefix.length()).trim();

        if (!isValidGatewayRouteName(name)) {
            System.out.println(RED + "Route name is invalid." + RESET);
            return;
        }

        try {
            context.stateStore().set("gateway.tcp." + name + ".enabled", String.valueOf(enabled));

            if (enabled) {
                System.out.println(GREEN + "[Gateway] Enabled route: " + name + RESET);
            } else {
                System.out.println(YELLOW + "[Gateway] Disabled route: " + name + RESET);
            }

            context.logService().write("[GATEWAY ROUTE TOGGLE] " + name + " enabled=" + enabled + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Error: " + e.getMessage() + RESET);
        }
    }

    private void showGatewayConfig() throws IOException {
        context.stateStore().load();

        System.out.println(CYAN + "[GATEWAY CONFIG]" + RESET);
        System.out.println(".gateway.http.enabled = " + context.stateStore().get("gateway.http.enabled", "true"));
        System.out.println(".gateway.http.root    = " + context.stateStore().get("gateway.http.root", "www"));
        System.out.println(".gateway.http.index   = " + context.stateStore().get("gateway.http.index", "index.html"));
        System.out.println(".gateway.http.spa     = " + context.stateStore().get("gateway.http.spa", "false"));
        System.out.println();

        System.out.println(YELLOW + "SSH/SFTP:" + RESET);
        System.out.println(".gateway.ssh.enabled  = " + context.stateStore().get("gateway.ssh.enabled", "true"));
        System.out.println(".gateway.ssh.host     = " + context.stateStore().get("gateway.ssh.host", "127.0.0.1"));
        System.out.println(".gateway.ssh.port     = " + context.stateStore().get("gateway.ssh.port", "2022"));
        System.out.println();

        System.out.println(YELLOW + "TCP routes:" + RESET);
        System.out.println(".gateway.tcp.enabled  = " + context.stateStore().get("gateway.tcp.enabled", "true"));
        System.out.println(".gateway.tcp.default  = " + context.stateStore().get("gateway.tcp.default", "close"));
        System.out.println(".gateway.tcp.routes   = " + context.stateStore().get("gateway.tcp.routes", ""));

        List<String> routes = getGatewayRouteNames();

        if (routes.isEmpty()) {
            System.out.println("  No TCP routes found.");
        } else {
            for (String name : routes) {
                System.out.println();
                System.out.println("  [" + name + "]");
                System.out.println("  enabled = " + context.stateStore().get("gateway.tcp." + name + ".enabled", "false"));
                System.out.println("  host    = " + context.stateStore().get("gateway.tcp." + name + ".host", ""));
                System.out.println("  port    = " + context.stateStore().get("gateway.tcp." + name + ".port", ""));
            }
        }

        context.logService().write("[GATEWAY SHOW]\n");
    }

private void printGatewayHelp() {
    printTitle("Gateway Commands");

    printSection("1. Gateway Core");
    printCommand(".gateway-help", "View Gateway help");
    printCommand(".gateway-show", "View full Gateway configuration");
    printCommand(".gateway-set <key> <value>", "Set Gateway config manually");
    printCommand(".gateway-default <route|close>", "Choose default TCP route or close TCP fallback");

    printSection("2. HTTP Static File Service");
    printCommand(".gateway-set gateway.http.enabled true", "Enable HTTP service");
    printCommand(".gateway-set gateway.http.enabled false", "Disable HTTP service");
    printCommand(".gateway-set gateway.http.root /home/container/www", "Set HTML/CSS/JS root folder");
    printCommand(".gateway-set gateway.http.index index.html", "Set default index file");
    printCommand(".gateway-set gateway.http.spa true", "Enable SPA fallback to index.html");
    printCommand(".gateway-set gateway.http.spa false", "Disable SPA fallback");

    printSection("3. SSH / SFTP Gateway Proxy");
    printCommand(".gateway-set gateway.ssh.enabled true", "Enable SSH/SFTP route via Gateway");
    printCommand(".gateway-set gateway.ssh.enabled false", "Disable SSH/SFTP route via Gateway");
    printCommand(".gateway-set gateway.ssh.host 127.0.0.1", "Set internal SSH/SFTP host");
    printCommand(".gateway-set gateway.ssh.port 2022", "Set internal SSH/SFTP port");

    printSection("4. Manual TCP Routes");
    printCommand(".gateway-route-add <name> <host> <port>", "Add or update TCP route");
    printCommand(".gateway-route-remove <name>", "Remove TCP route");
    printCommand(".gateway-route-enable <name>", "Enable TCP route");
    printCommand(".gateway-route-disable <name>", "Disable TCP route");
    printCommand(".gateway-default <name>", "Select route as default TCP fallback");
    printCommand(".gateway-default close", "Close TCP fallback when not HTTP/SSH");

    printSection(" ---   Examples   ---");
    printCommand(".gateway-route-add mc 127.0.0.1 25565", "Add Minecraft Java route");
    printCommand(".gateway-default mc", "Set TCP fallback to route mc");
    printCommand(".gateway-route-add velocity 127.0.0.1 25577", "Add Velocity route");
    printCommand(".gateway-default velocity", "Set TCP fallback to route velocity");
    printCommand(".gateway-default close", "Disable TCP fallback");

    System.out.println();
}

    private List<String> getGatewayRouteNames() {
        String raw = context.stateStore().get("gateway.tcp.routes", "").trim();

        if (raw.isBlank()) {
            return new ArrayList<>();
        }

        List<String> result = new ArrayList<>();

        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .filter(this::isValidGatewayRouteName)
                .forEach(item -> {
                    if (!result.contains(item)) {
                        result.add(item);
                    }
                });

        return result;
    }

    private void saveGatewayRouteNames(List<String> routes) throws IOException {
        context.stateStore().set("gateway.tcp.routes", String.join(",", routes));
    }

    private boolean isValidGatewayRouteName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]+");
    }

    private void printTitle(String title) {
        System.out.println();
        System.out.println(GREEN + "==================================================" + RESET);
        System.out.println(GREEN + title + RESET);
        System.out.println(GREEN + "==================================================" + RESET);
    }
    
    private void printSection(String title) {
        System.out.println();
        System.out.println(YELLOW + "== " + title + " ==" + RESET);
    }
    
    private void printCommand(String command, String description) {
        System.out.printf("  " + CYAN + "%-48s" + RESET + " %s%n", command, description);
    }

    private void printHelp() throws IOException {
        printTitle("Mini Java Terminal Commands");

        printSection("1. Terminal Runtime");
        printCommand(".help", "View general help");
        printCommand(".pwd", "Show current directory");
        printCommand(".cd <folder>", "Change directory");
        printCommand(".clear / .cls", "Clear the console screen");
        printCommand(".public-ip", "Check the panel host public IPv4");
        printCommand(".timeout", "View current timeout");
        printCommand(".timeout <seconds>", "Set timeout, 0 = unlimited");

        printSection("2. Cloudflare DDNS");
        printCommand(".cloudflare-show", "View Cloudflare config");
        printCommand(".cloudflare-set token <token>", "Save Cloudflare API token");
        printCommand(".cloudflare-set zone <zone_id>", "Save Cloudflare Zone ID");
        printCommand(".cloudflare-set name <domain>", "Save DNS record name");
        printCommand(".cloudflare-set proxied false", "Set DNS only");
        printCommand(".cloudflare-set ttl 120", "Set TTL");
        printCommand(".cloudflare-set interval 300", "Set IP check interval");
        printCommand(".cloudflare-ddns-once", "Update DNS once");
        printCommand(".cloudflare-ddns-start", "Start auto DDNS");
        printCommand(".cloudflare-ddns-stop", "Stop auto DDNS");
        printCommand(".cloudflare-ddns-status", "View DDNS status");

        printSection("3. SSH / SFTP Server");
        printCommand(".ssh-show", "View SSH/SFTP config");
        printCommand(".ssh-set host <host>", "Bind host, e.g. 127.0.0.1 or 0.0.0.0");
        printCommand(".ssh-set port <port>", "Set SSH/SFTP port");
        printCommand(".ssh-set user <username>", "Set SSH/SFTP username");
        printCommand(".ssh-set pass <password>", "Set SSH/SFTP password");
        printCommand(".ssh-set mode <real-tty|basic>", "Set SSH terminal mode");        
        printCommand(".ssh-set root <folder>", "Set SSH/SFTP root folder");
        printCommand(".ssh-start", "Start SSH/SFTP server");
        printCommand(".ssh-stop", "Stop SSH/SFTP server");
        printCommand(".ssh-status", "View SSH/SFTP status");


        printSection("4. SFTP Compatibility Aliases");
        printCommand(".sftp-show", "Alias for .ssh-show");
        printCommand(".sftp-set <key> <value>", "Alias for .ssh-set");
        printCommand(".sftp-start", "Alias for .ssh-start");
        printCommand(".sftp-stop", "Alias for .ssh-stop");
        printCommand(".sftp-status", "Alias for .ssh-status");

        printSection("5. Gateway");
        printCommand(".gateway-help", "View full Gateway help");
        printCommand(".gateway-show", "View Gateway configuration");
        printCommand(".gateway-set <key> <value>", "Set Gateway config manually");
        printCommand(".gateway-default <route|close>", "Choose default TCP route");
        printCommand(".gateway-route-add <name> <host> <port>", "Add/update TCP route");
        printCommand(".gateway-route-remove <name>", "Remove TCP route");
        printCommand(".gateway-route-enable <name>", "Enable TCP route");
        printCommand(".gateway-route-disable <name>", "Disable TCP route");

        printSection("6. Safety");
        printCommand(".mjt-exit", "Shutdown Mini Java Terminal");
        printCommand(".exit", "Blocked to prevent server offline");

        printSection("Commands not recommended");
        System.out.println("  " + RED + "su, sudo, nano, vim, vi, top, htop" + RESET);

        System.out.println();
        System.out.println(YELLOW + "Tip:" + RESET + " Type " + CYAN + "gateway-help" + RESET + " to view just the Gateway section.");
        System.out.println();

        context.logService().write("[HELP]\n");
    }
}
