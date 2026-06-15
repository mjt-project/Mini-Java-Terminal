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

    private enum RouteMode {
        TERMINAL,
        MINECRAFT
    }

    private final CommandContext context;
    private volatile RouteMode routeMode = RouteMode.TERMINAL;

    public CommandCenter(CommandContext context) {
        this.context = context;
        this.context.targetProcessService().setOnExit(() -> {
            routeMode = RouteMode.TERMINAL;
            System.out.println(YELLOW + "[MJT] Target stopped. Switched to TERMINAL mode." + RESET);
        });
    }

    public void handle(String command) throws IOException {
        command = command == null ? "" : command.trim();

        if (command.isEmpty()) {
            return;
        }

        context.logService().write("\n========================================\n");
        context.logService().write("[INPUT] " + command + "\n");

        if (isMjtNamespace(command)) {
            handleMjtNamespace(command);
            return;
        }

        if (isCommandNamespace(command)) {
            handleCommandNamespace(command);
            return;
        }

        // Backward compatibility for old commands such as .ssh-start, .gateway-show, .help.
        // New recommended form is .mjt ssh start, .mjt gateway show, .mjt help.
        if (command.startsWith(".")) {
            String legacyMjtCommand = command.substring(1).trim();

            if (handleInternalCommand(normalizeMjtCommand(legacyMjtCommand))) {
                return;
            }

            System.out.println(RED + "Unknown prefixed command." + RESET);
            System.out.println(YELLOW + "Use: .mjt help  or  .command <shell-command>" + RESET);
            return;
        }

        if (routeMode == RouteMode.MINECRAFT) {
            if (context.targetProcessService().isRunning()) {
                context.targetProcessService().sendLine(command);
                return;
            }

            routeMode = RouteMode.TERMINAL;
            System.out.println(YELLOW + "[MJT] Minecraft is not running. Switched to TERMINAL mode." + RESET);
        }

        runShellCommand(command);
    }

    private boolean isMjtNamespace(String command) {
        return command.equalsIgnoreCase(".mjt")
                || command.toLowerCase().startsWith(".mjt ");
    }

    private boolean isCommandNamespace(String command) {
        return command.equalsIgnoreCase(".command")
                || command.toLowerCase().startsWith(".command ");
    }

    private void handleMjtNamespace(String command) throws IOException {
        String raw = command.length() <= 4 ? "help" : command.substring(4).trim();

        if (raw.isBlank()) {
            raw = "help";
        }

        String mjtCommand = normalizeMjtCommand(raw);

        if (handleInternalCommand(mjtCommand)) {
            return;
        }

        System.out.println(RED + "Unknown MJT command: " + raw + RESET);
        System.out.println(YELLOW + "Type: .mjt help" + RESET);
    }

    private void handleCommandNamespace(String command) throws IOException {
        String raw = command.length() <= 8 ? "" : command.substring(8).trim();

        if (raw.isBlank()) {
            System.out.println(YELLOW + "Usage: .command <shell-command>" + RESET);
            System.out.println("Example: .command ls");
            System.out.println("Example: .command terminal");
            System.out.println("Example: .command minecraft");
            return;
        }

        if (raw.equalsIgnoreCase("terminal")) {
            routeMode = RouteMode.TERMINAL;
            System.out.println(GREEN + "[MJT] Switched to TERMINAL mode." + RESET);
            return;
        }

        if (raw.equalsIgnoreCase("minecraft") || raw.equalsIgnoreCase("mc")) {
            if (!context.targetProcessService().isRunning()) {
                System.out.println(YELLOW + "[MJT] Minecraft target is not running." + RESET);
                System.out.println(YELLOW + "Start it with: .mjt minecraft-start" + RESET);
                return;
            }

            routeMode = RouteMode.MINECRAFT;
            System.out.println(GREEN + "[MJT] Switched to MINECRAFT mode." + RESET);
            return;
        }

        if (raw.toLowerCase().startsWith(".mjt")) {
            System.out.println(RED + "[MJT] Do not run MJT commands through .command." + RESET);
            System.out.println(YELLOW + "Run it directly, example: .mjt ssh start" + RESET);
            return;
        }

        runShellCommand(raw);
    }

    private void runShellCommand(String shellCommand) throws IOException {
        if (context.commandGuard().isBlocked(shellCommand)) {
            return;
        }

        context.shellRunner().run(
                shellCommand,
                context.runtimeConfig().getCurrentDir(),
                context.runtimeConfig().getCommandTimeoutSeconds()
        );
    }

    private String normalizeMjtCommand(String command) {
        String raw = command.trim().replaceAll("\\s+", " ");

        if (raw.isBlank()) {
            return "help";
        }

        if (raw.equalsIgnoreCase("exit")) {
            return "mjt-exit";
        }

        if (raw.equalsIgnoreCase("mc start")) {
            return "minecraft-start";
        }

        if (raw.equalsIgnoreCase("mc stop")) {
            return "minecraft-stop";
        }

        if (raw.equalsIgnoreCase("mc kill")) {
            return "minecraft-kill";
        }

        if (raw.equalsIgnoreCase("mc status")) {
            return "minecraft-status";
        }

        int firstSpace = raw.indexOf(' ');

        if (firstSpace > 0) {
            String first = raw.substring(0, firstSpace).trim().toLowerCase();

            if (first.equals("ssh")
                    || first.equals("sftp")
                    || first.equals("gateway")
                    || first.equals("cloudflare")
                    || first.equals("minecraft")
                    || first.equals("target")
                    || first.equals("timeout")
                    || first.equals("cd")) {
                return first + "-" + raw.substring(firstSpace + 1).trim();
            }
        }

        return raw;
    }

    private boolean handleInternalCommand(String command) throws IOException {
        if (command.equalsIgnoreCase("help")) {
            printHelp();
            return true;
        }

        if (command.equalsIgnoreCase("mode")) {
            printMode();
            return true;
        }

        if (command.equalsIgnoreCase("minecraft-start") || command.equalsIgnoreCase("mc-start")) {
            startMinecraftTarget();
            return true;
        }

        if (command.startsWith("minecraft-start ") || command.startsWith("mc-start ")) {
            String customCommand = command.substring(command.indexOf(' ') + 1).trim();
            startTarget("minecraft", customCommand);
            routeMode = RouteMode.MINECRAFT;
            return true;
        }

        if (command.equalsIgnoreCase("minecraft-stop") || command.equalsIgnoreCase("mc-stop")) {
            context.targetProcessService().stopGracefully("stop");
            return true;
        }

        if (command.equalsIgnoreCase("minecraft-kill") || command.equalsIgnoreCase("mc-kill")) {
            context.targetProcessService().kill();
            routeMode = RouteMode.TERMINAL;
            return true;
        }

        if (command.equalsIgnoreCase("minecraft-status") || command.equalsIgnoreCase("mc-status")) {
            context.targetProcessService().printStatus();
            printMode();
            return true;
        }

        if (command.startsWith("target-start ")) {
            String targetCommand = command.substring("target-start ".length()).trim();
            startTarget("target", targetCommand);
            return true;
        }

        if (command.equalsIgnoreCase("target-stop")) {
            context.targetProcessService().stopGracefully();
            return true;
        }

        if (command.equalsIgnoreCase("target-kill")) {
            context.targetProcessService().kill();
            routeMode = RouteMode.TERMINAL;
            return true;
        }

        if (command.equalsIgnoreCase("target-status")) {
            context.targetProcessService().printStatus();
            printMode();
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
            System.out.println(YELLOW + "To shut down Mini Java Terminal, type: .mjt exit" + RESET);
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

    private void startMinecraftTarget() throws IOException {
        String startCommand = context.stateStore().get("minecraft.start-command", "bash start-minecraft.sh").trim();

        if (startCommand.isBlank()) {
            startCommand = "bash start-minecraft.sh";
        }

        startTarget("minecraft", startCommand);
        routeMode = RouteMode.MINECRAFT;
    }

    private void startTarget(String name, String command) throws IOException {
        context.targetProcessService().start(
                name,
                command,
                context.runtimeConfig().getCurrentDir()
        );
    }

    private void printMode() {
        System.out.println(CYAN + "[MJT MODE]" + RESET);
        System.out.println("Route mode : " + routeMode);
        System.out.println("Target run : " + context.targetProcessService().isRunning());
        System.out.println("Target name: " + context.targetProcessService().getTargetName());
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
            System.out.println(RED + "Usage: .mjt gateway set <key> <value>" + RESET);
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
            System.out.println(RED + "Usage: .mjt gateway default <route|close>" + RESET);
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
            System.out.println(RED + "Usage: .mjt gateway route-add <name> <host> <port>" + RESET);
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
        System.out.println();
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
    printCommand(".mjt gateway help", "View Gateway help");
    printCommand(".mjt gateway show", "View full Gateway configuration");
    printCommand(".mjt gateway set <key> <value>", "Set Gateway config manually");
    printCommand(".mjt gateway default <route|close>", "Choose default TCP route or close TCP fallback");
    printCommand(".gateway-set gateway.public.host <ipv4>", "Bind local-only, Default is '127.0.0.1");
    printCommand(".gateway-set gateway.public.port <port number>>", "Set http port number, Default is 'auto'");

    printSection("2. HTTP Static File Service");
    printCommand(".gateway-set gateway.http.enabled true", "Enable HTTP service");
    printCommand(".gateway-set gateway.http.enabled false", "Disable HTTP service");
    printCommand(".gateway-set gateway.http.root /path/to/dir", "Set HTML/CSS/JS root folder, Default '/home/container/www'");
    printCommand(".gateway-set gateway.http.index index.html", "Set default index file");
    printCommand(".gateway-set gateway.http.spa true|false", "Enable or Disable SPA fallback to index.html");

    printSection("3. SSH / SFTP Gateway Proxy");
    printCommand(".gateway-set gateway.ssh.enabled true", "Enable SSH/SFTP route via Gateway");
    printCommand(".gateway-set gateway.ssh.enabled false", "Disable SSH/SFTP route via Gateway");
    printCommand(".gateway-set gateway.ssh.host 127.0.0.1", "Set internal SSH/SFTP host");
    printCommand(".gateway-set gateway.ssh.port 2022", "Set internal SSH/SFTP port");

    printSection("4. Manual TCP Routes");
    printCommand(".mjt gateway route-add <name> <host> <port>", "Add or update TCP route");
    printCommand(".mjt gateway route-remove <name>", "Remove TCP route");
    printCommand(".mjt gateway route-enable <name>", "Enable TCP route");
    printCommand(".mjt gateway route-disable <name>", "Disable TCP route");
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
        printCommand(".mjt help", "View general help");
        printCommand(".mjt pwd", "Show current directory");
        printCommand(".mjt cd <folder>", "Change directory");
        printCommand(".mjt clear / .mjt cls", "Clear the console screen");
        printCommand(".mjt public-ip", "Check the panel host public IPv4");
        printCommand(".mjt timeout", "View current timeout");
        printCommand(".mjt timeout <seconds>", "Set timeout, 0 = unlimited");

        printSection("2. Cloudflare DDNS");
        printCommand(".mjt cloudflare show", "View Cloudflare config");
        printCommand(".mjt cloudflare set token <token>", "Save Cloudflare API token");
        printCommand(".mjt cloudflare set zone <zone_id>", "Save Cloudflare Zone ID");
        printCommand(".mjt cloudflare set name <domain>", "Save DNS record name");
        printCommand(".mjt cloudflare set proxied false", "Set DNS only");
        printCommand(".mjt cloudflare set ttl 120", "Set TTL");
        printCommand(".mjt cloudflare set interval 300", "Set IP check interval");
        printCommand(".mjt cloudflare ddns-once", "Update DNS once");
        printCommand(".mjt cloudflare ddns-start", "Start auto DDNS");
        printCommand(".mjt cloudflare ddns-stop", "Stop auto DDNS");
        printCommand(".mjt cloudflare ddns-status", "View DDNS status");

        printSection("3. SSH / SFTP Server");
        printCommand(".mjt ssh show", "View SSH/SFTP config");
        printCommand(".mjt ssh set host <host>", "Bind host, e.g. 127.0.0.1 or 0.0.0.0");
        printCommand(".mjt ssh set port <port>", "Set SSH/SFTP port");
        printCommand(".mjt ssh set user <username>", "Set SSH/SFTP username");
        printCommand(".mjt ssh set pass <password>", "Set SSH/SFTP password");
        printCommand(".mjt ssh set mode <real-tty|basic>", "Set SSH terminal mode");        
        printCommand(".mjt ssh set root <folder>", "Set SSH/SFTP root folder");
        printCommand(".mjt ssh start", "Start SSH/SFTP server");
        printCommand(".mjt ssh stop", "Stop SSH/SFTP server");
        printCommand(".mjt ssh status", "View SSH/SFTP status");


        printSection("4. SFTP Compatibility Aliases");
        printCommand(".mjt sftp show", "Alias for .mjt ssh show");
        printCommand(".mjt sftp set <key> <value>", "Alias for .ssh-set");
        printCommand(".mjt sftp start", "Alias for .mjt ssh start");
        printCommand(".mjt sftp stop", "Alias for .mjt ssh stop");
        printCommand(".mjt sftp status", "Alias for .mjt ssh status");

        printSection("5. Gateway");
        printCommand(".mjt gateway help", "View full Gateway help");
        printCommand(".mjt gateway show", "View Gateway configuration");
        printCommand(".mjt gateway set <key> <value>", "Set Gateway config manually");
        printCommand(".mjt gateway default <route|close>", "Choose default TCP route");
        printCommand(".mjt gateway route-add <name> <host> <port>", "Add/update TCP route");
        printCommand(".mjt gateway route-remove <name>", "Remove TCP route");
        printCommand(".mjt gateway route-enable <name>", "Enable TCP route");
        printCommand(".mjt gateway route-disable <name>", "Disable TCP route");

        printSection("6. Managed Target / Minecraft");
        printCommand(".mjt minecraft-start", "Start bash start-minecraft.sh as managed target");
        printCommand(".mjt minecraft-start <cmd>", "Start Minecraft target with custom command");
        printCommand(".mjt minecraft-stop", "Send stop to Minecraft target");
        printCommand(".mjt minecraft-kill", "Force kill Minecraft target");
        printCommand(".mjt minecraft-status", "Show target and route mode");
        printCommand(".command minecraft", "Route no-prefix input to Minecraft target");
        printCommand(".command terminal", "Route no-prefix input to shell terminal");
        printCommand(".command <shell>", "Force run a shell command while Minecraft is running");

        printSection("7. Safety");
        printCommand(".mjt exit", "Shutdown Mini Java Terminal");
        printCommand(".exit", "Legacy alias: blocked to prevent server offline");

        printSection("Commands not recommended");
        System.out.println("  " + RED + "su, sudo, nano, vim, vi, top, htop" + RESET);

        System.out.println();
        System.out.println(YELLOW + "Tip:" + RESET + " Type " + CYAN + ".mjt gateway help" + RESET + " to view just the Gateway section.");
        System.out.println();

        context.logService().write("[HELP]\n");
    }
}
