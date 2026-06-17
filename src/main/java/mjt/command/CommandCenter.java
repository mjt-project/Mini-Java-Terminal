package main.java.mjt.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import main.java.mjt.system.BuildInfo;
import main.java.mjt.help.HelpCenter;

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

            if (context.stateStore().getBoolean("bot.autoStopWithMinecraft", true)) {
                context.keepAliveBotService().stop();
            }

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

        if (command.startsWith(".")) {
            String legacyCommand = normalizeMjtCommand(command.substring(1).trim());

            if (handleInternalCommand(legacyCommand)) {
                return;
            }

            System.out.println(RED + "Unknown MJT command: " + command + RESET);
            System.out.println(YELLOW + "Type: .help or .mjt help" + RESET);
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

        System.out.println(YELLOW + "[MJT] Shell command must use: .command <shell-command>" + RESET);
        System.out.println(YELLOW + "[MJT] Example: .command ls" + RESET);
        System.out.println(YELLOW + "[MJT] To start Minecraft: .mjt minecraft start" + RESET);
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
                System.out.println(YELLOW + "Start it with: .mjt minecraft start" + RESET);
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

        if (isManagedTargetStartCommand(raw)) {
            System.out.println(RED + "[MJT] Do not start Minecraft/server through .command." + RESET);
            System.out.println(YELLOW + "Use: .mjt minecraft start" + RESET);
            return;
        }

        runShellCommand(raw);
    }

    private boolean isManagedTargetStartCommand(String raw) {
        String lower = raw.toLowerCase().trim();

        return lower.equals("bash start-minecraft.sh")
                || lower.equals("sh start-minecraft.sh")
                || lower.equals("./start-minecraft.sh")
                || lower.contains("start-minecraft.sh")
                || lower.startsWith("java -jar minecraft_server.jar")
                || lower.startsWith("java -jar server.jar")
                || lower.contains("unix_args.txt");
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
        String raw = command == null ? "" : command.trim().replaceAll("\\s+", " ");

        if (raw.isBlank()) {
            return "help";
        }

        if (raw.toLowerCase().startsWith("mjt ")) {
            raw = raw.substring(4).trim();
        }

        String lower = raw.toLowerCase();

        if (lower.equals("mjt")) {
            return "help";
        }

        if (lower.startsWith("help ")) {
            return "help-topic " + raw.substring("help ".length()).trim();
        }

        if (lower.equals("system help")) return "help-topic system";
        if (lower.equals("system download help")
                || lower.equals("system cloudflared help")
                || lower.equals("cloudflared help")
                || lower.equals("tunnel binary help")) {
            return "help-topic download";
        }

        if (lower.equals("system install cloudflared")
                || lower.equals("system download cloudflared")
                || lower.equals("system cloudflared install")
                || lower.equals("cloudflared install")
                || lower.equals("tunnel install")
                || lower.equals("tunnel binary install")) {
            return "system-cloudflared-install";
        }

        if (lower.equals("system cloudflared check")
                || lower.equals("cloudflared check")
                || lower.equals("tunnel binary check")) {
            return "system-cloudflared-check";
        }

        if (lower.equals("system cloudflared show")
                || lower.equals("cloudflared show")
                || lower.equals("tunnel binary show")) {
            return "system-cloudflared-show";
        }

        if (lower.equals("exit")) {
            return "mjt-exit";
        }

        if (lower.equals("--version") || lower.equals("-v") || lower.equals("version")) {
            return "version";
        }

        if (lower.equals("mc start")) return "minecraft-start";
        if (lower.equals("mc stop")) return "minecraft-stop";
        if (lower.equals("mc kill")) return "minecraft-kill";
        if (lower.equals("mc status")) return "minecraft-status";

        if (lower.equals("minecraft start")) return "minecraft-start";
        if (lower.startsWith("minecraft start ")) return "minecraft-start " + raw.substring("minecraft start ".length()).trim();
        if (lower.equals("minecraft stop")) return "minecraft-stop";
        if (lower.equals("minecraft kill")) return "minecraft-kill";
        if (lower.equals("minecraft status")) return "minecraft-status";

        if (lower.startsWith("target start ")) return "target-start " + raw.substring("target start ".length()).trim();
        if (lower.equals("target stop")) return "target-stop";
        if (lower.equals("target kill")) return "target-kill";
        if (lower.equals("target status")) return "target-status";

        if (lower.startsWith("bot set ")) return "bot-set " + raw.substring("bot set ".length()).trim();
        if (lower.equals("bot show") || lower.equals("bot status")) return "bot-status";
        if (lower.equals("bot start")) return "bot-start";
        if (lower.equals("bot stop")) return "bot-stop";

        if (lower.equals("http help")) return "http-help";
        if (lower.equals("http show") || lower.equals("http status")) return "http-status";
        if (lower.equals("http start")) return "http-start";
        if (lower.equals("http stop")) return "http-stop";
        if (lower.startsWith("http set ")) return "http-set " + raw.substring("http set ".length()).trim();
        if (lower.equals("http site list")) return "http-site-list";
        if (lower.startsWith("http site show ")) return "http-site-show " + raw.substring("http site show ".length()).trim();
        if (lower.startsWith("http site add ")) return "http-site-add " + raw.substring("http site add ".length()).trim();
        if (lower.startsWith("http site remove ")) return "http-site-remove " + raw.substring("http site remove ".length()).trim();
        if (lower.startsWith("http site start ")) return "http-site-start " + raw.substring("http site start ".length()).trim();
        if (lower.startsWith("http site stop ")) return "http-site-stop " + raw.substring("http site stop ".length()).trim();
        if (lower.startsWith("http site restart ")) return "http-site-restart " + raw.substring("http site restart ".length()).trim();
        if (lower.startsWith("http site set ")) return "http-site-set " + raw.substring("http site set ".length()).trim();

        if (lower.equals("website help")) return "website-help";
        if (lower.equals("website list")) return "website-list";
        if (lower.startsWith("website show ")) return "website-show " + raw.substring("website show ".length()).trim();
        if (lower.startsWith("website add ")) return "http-site-add " + raw.substring("website add ".length()).trim();
        if (lower.startsWith("website remove ")) return "http-site-remove " + raw.substring("website remove ".length()).trim();
        if (lower.startsWith("website start ")) return "http-site-start " + raw.substring("website start ".length()).trim();
        if (lower.startsWith("website stop ")) return "http-site-stop " + raw.substring("website stop ".length()).trim();
        if (lower.startsWith("website restart ")) return "http-site-restart " + raw.substring("website restart ".length()).trim();
        if (lower.startsWith("website set ")) return "http-site-set " + raw.substring("website set ".length()).trim();
        if (lower.equals("website guest list")) return "website-guest-list";
        if (lower.equals("website guest create")) return "website-guest-create";
        if (lower.startsWith("website guest show ")) return "website-guest-show " + raw.substring("website guest show ".length()).trim();
        if (lower.startsWith("website guest stop ")) return "website-guest-stop " + raw.substring("website guest stop ".length()).trim();
        if (lower.startsWith("website guest restart ")) return "website-guest-restart " + raw.substring("website guest restart ".length()).trim();
        if (lower.startsWith("website guest remove ")) return "website-guest-remove " + raw.substring("website guest remove ".length()).trim();

        if (lower.equals("https help")) return "https-help";
        if (lower.equals("https show") || lower.equals("https status")) return "https-status";
        if (lower.equals("https start")) return "https-start";
        if (lower.equals("https stop")) return "https-stop";
        if (lower.startsWith("https set ")) return "https-set " + raw.substring("https set ".length()).trim();
        if (lower.equals("https cert self-signed") || lower.equals("https cert selfsigned")) return "https-cert-self-signed";

        if (lower.startsWith("ssh set ")) return "ssh-set " + raw.substring("ssh set ".length()).trim();
        if (lower.startsWith("sftp set ")) return "sftp-set " + raw.substring("sftp set ".length()).trim();

        if (lower.equals("ssh show") || lower.equals("sftp show")
                || lower.equals("ssh start") || lower.equals("sftp start")
                || lower.equals("ssh stop") || lower.equals("sftp stop")
                || lower.equals("ssh status") || lower.equals("sftp status")) {
            return lower.replace(' ', '-');
        }

        if (lower.startsWith("cloudflare set ")) return "cloudflare-set " + raw.substring("cloudflare set ".length()).trim();
        if (lower.equals("cloudflare show")) return "cloudflare-show";
        if (lower.startsWith("cloudflare ddns ")) return "cloudflare-ddns-" + raw.substring("cloudflare ddns ".length()).trim().replace(' ', '-');

        if (lower.equals("tunnel help")) return "tunnel-help";
        if (lower.equals("tunnel show") || lower.equals("tunnel status")) return "tunnel-status";
        if (lower.equals("tunnel start")) return "tunnel-start";
        if (lower.equals("tunnel stop")) return "tunnel-stop";
        if (lower.startsWith("tunnel set ")) return "tunnel-set " + raw.substring("tunnel set ".length()).trim();
        if (lower.equals("tunnel route list")) return "tunnel-route-list";
        if (lower.startsWith("tunnel route add ")) return "tunnel-route-add " + raw.substring("tunnel route add ".length()).trim();
        if (lower.startsWith("tunnel route remove ")) return "tunnel-route-remove " + raw.substring("tunnel route remove ".length()).trim();
        if (lower.startsWith("tunnel route enable ")) return "tunnel-route-enable " + raw.substring("tunnel route enable ".length()).trim();
        if (lower.startsWith("tunnel route disable ")) return "tunnel-route-disable " + raw.substring("tunnel route disable ".length()).trim();
        if (lower.equals("tunnel config generate")) return "tunnel-config-generate";

        if (lower.equals("gateway help")) return "gateway-help";
        if (lower.equals("gateway show")) return "gateway-show";
        if (lower.equals("gateway start")) return "gateway-start";
        if (lower.equals("gateway stop")) return "gateway-stop";
        if (lower.equals("gateway status")) return "gateway-status";
        if (lower.startsWith("gateway set ")) return "gateway-set " + raw.substring("gateway set ".length()).trim();
        if (lower.startsWith("gateway default ")) return "gateway-default " + raw.substring("gateway default ".length()).trim();
        if (lower.startsWith("gateway route add ")) return "gateway-route-add " + raw.substring("gateway route add ".length()).trim();
        if (lower.startsWith("gateway route-add ")) return "gateway-route-add " + raw.substring("gateway route-add ".length()).trim();
        if (lower.startsWith("gateway route remove ")) return "gateway-route-remove " + raw.substring("gateway route remove ".length()).trim();
        if (lower.startsWith("gateway route-remove ")) return "gateway-route-remove " + raw.substring("gateway route-remove ".length()).trim();
        if (lower.startsWith("gateway route enable ")) return "gateway-route-enable " + raw.substring("gateway route enable ".length()).trim();
        if (lower.startsWith("gateway route-enable ")) return "gateway-route-enable " + raw.substring("gateway route-enable ".length()).trim();
        if (lower.startsWith("gateway route disable ")) return "gateway-route-disable " + raw.substring("gateway route disable ".length()).trim();
        if (lower.startsWith("gateway route-disable ")) return "gateway-route-disable " + raw.substring("gateway route-disable ".length()).trim();

        return raw;
    }

    private boolean handleInternalCommand(String command) throws IOException {
        if (command.equalsIgnoreCase("help")) {
            printHelp();
            return true;
        }

        if (command.startsWith("help-topic ")) {
            HelpCenter.printTopic(command.substring("help-topic ".length()).trim());
            return true;
        }

        if (command.equalsIgnoreCase("system-cloudflared-install")) {
            context.systemDownloadService().installCloudflared();
            return true;
        }

        if (command.equalsIgnoreCase("system-cloudflared-check")) {
            context.systemDownloadService().checkCloudflared(false);
            return true;
        }

        if (command.equalsIgnoreCase("system-cloudflared-show")) {
            context.systemDownloadService().showCloudflared();
            return true;
        }

        if (command.equalsIgnoreCase("version")) {
            printVersion();
            return true;
        }

        if (command.equalsIgnoreCase("http-help")) {
            printHttpHelp();
            return true;
        }

        if (command.equalsIgnoreCase("http-show") || command.equalsIgnoreCase("http-status")) {
            context.httpService().showConfig();
            return true;
        }

        if (command.equalsIgnoreCase("http-start")) {
            context.httpService().start();
            return true;
        }

        if (command.equalsIgnoreCase("http-stop")) {
            context.httpService().stop();
            return true;
        }

        if (command.startsWith("http-set ")) {
            handleHttpSet(command);
            return true;
        }

        if (command.equalsIgnoreCase("http-site-list")) {
            context.httpService().listSites();
            return true;
        }

        if (command.startsWith("http-site-show ")) {
            context.httpService().showSite(command.substring("http-site-show ".length()).trim());
            return true;
        }

        if (command.startsWith("http-site-add ")) {
            context.httpService().addSite(command.substring("http-site-add ".length()).trim());
            return true;
        }

        if (command.startsWith("http-site-remove ")) {
            context.httpService().removeSite(command.substring("http-site-remove ".length()).trim());
            return true;
        }

        if (command.startsWith("http-site-start ")) {
            context.httpService().startSite(command.substring("http-site-start ".length()).trim());
            return true;
        }

        if (command.startsWith("http-site-stop ")) {
            context.httpService().stopSite(command.substring("http-site-stop ".length()).trim());
            return true;
        }

        if (command.startsWith("http-site-restart ")) {
            context.httpService().restartSite(command.substring("http-site-restart ".length()).trim());
            return true;
        }

        if (command.startsWith("http-site-set ")) {
            context.httpService().setSiteConfig(command.substring("http-site-set ".length()).trim());
            return true;
        }

        if (command.equalsIgnoreCase("website-help")) {
            printWebsiteHelp();
            return true;
        }

        if (command.equalsIgnoreCase("website-list")) {
            context.httpService().listSites();
            return true;
        }

        if (command.startsWith("website-show ")) {
            context.httpService().showSite(command.substring("website-show ".length()).trim());
            return true;
        }

        if (command.equalsIgnoreCase("website-guest-list")) {
            context.guestWebsiteService().listGuests();
            return true;
        }

        if (command.equalsIgnoreCase("website-guest-create")) {
            context.guestWebsiteService().createGuest();
            return true;
        }

        if (command.startsWith("website-guest-show ")) {
            context.guestWebsiteService().showGuest(command.substring("website-guest-show ".length()).trim());
            return true;
        }

        if (command.startsWith("website-guest-stop ")) {
            context.guestWebsiteService().stopGuest(command.substring("website-guest-stop ".length()).trim());
            return true;
        }

        if (command.startsWith("website-guest-restart ")) {
            context.guestWebsiteService().restartGuest(command.substring("website-guest-restart ".length()).trim());
            return true;
        }

        if (command.startsWith("website-guest-remove ")) {
            context.guestWebsiteService().removeGuest(command.substring("website-guest-remove ".length()).trim());
            return true;
        }

        if (command.equalsIgnoreCase("https-help")) {
            printHttpsHelp();
            return true;
        }

        if (command.equalsIgnoreCase("https-show") || command.equalsIgnoreCase("https-status")) {
            context.httpsService().showConfig();
            return true;
        }

        if (command.equalsIgnoreCase("https-start")) {
            context.httpsService().start();
            return true;
        }

        if (command.equalsIgnoreCase("https-stop")) {
            context.httpsService().stop();
            return true;
        }

        if (command.startsWith("https-set ")) {
            handleHttpsSet(command);
            return true;
        }

        if (command.equalsIgnoreCase("https-cert-self-signed")) {
            context.httpsService().generateSelfSignedCertificate();
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
            autoStartBotIfEnabled();
            return true;
        }

        if (command.equalsIgnoreCase("minecraft-stop") || command.equalsIgnoreCase("mc-stop")) {
            autoStopBotIfEnabled();
            context.targetProcessService().stopGracefully("stop");
            return true;
        }

        if (command.equalsIgnoreCase("minecraft-kill") || command.equalsIgnoreCase("mc-kill")) {
            autoStopBotIfEnabled();
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

        if (command.equalsIgnoreCase("bot-show") || command.equalsIgnoreCase("bot-status")) {
            context.keepAliveBotService().printStatus();
            return true;
        }

        if (command.equalsIgnoreCase("bot-start")) {
            context.keepAliveBotService().start();
            return true;
        }

        if (command.equalsIgnoreCase("bot-stop")) {
            context.keepAliveBotService().stop();
            return true;
        }

        if (command.startsWith("bot-set ")) {
            handleBotSet(command);
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
            autoStopBotIfEnabled();
            System.out.println(RED + "Shutting down Mini Java Terminal..." + RESET);
            context.logService().write("[MJT EXIT]\n");
            System.exit(0);
            return true;
        }

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

        if (command.equalsIgnoreCase("tunnel-help")) {
            printTunnelHelp();
            return true;
        }

        if (command.equalsIgnoreCase("tunnel-show") || command.equalsIgnoreCase("tunnel-status")) {
            context.cloudflareTunnelService().showConfig();
            return true;
        }

        if (command.equalsIgnoreCase("tunnel-start")) {
            context.cloudflareTunnelService().start();
            return true;
        }

        if (command.equalsIgnoreCase("tunnel-stop")) {
            context.cloudflareTunnelService().stop();
            return true;
        }

        if (command.startsWith("tunnel-set ")) {
            handleTunnelSet(command);
            return true;
        }

        if (command.equalsIgnoreCase("tunnel-route-list")) {
            context.cloudflareTunnelService().listRoutes();
            return true;
        }

        if (command.startsWith("tunnel-route-add ")) {
            context.cloudflareTunnelService().addRoute(command.substring("tunnel-route-add ".length()).trim());
            return true;
        }

        if (command.startsWith("tunnel-route-remove ")) {
            context.cloudflareTunnelService().removeRoute(command.substring("tunnel-route-remove ".length()).trim());
            return true;
        }

        if (command.startsWith("tunnel-route-enable ")) {
            context.cloudflareTunnelService().setRouteEnabled(command.substring("tunnel-route-enable ".length()).trim(), true);
            return true;
        }

        if (command.startsWith("tunnel-route-disable ")) {
            context.cloudflareTunnelService().setRouteEnabled(command.substring("tunnel-route-disable ".length()).trim(), false);
            return true;
        }

        if (command.equalsIgnoreCase("tunnel-config-generate")) {
            context.cloudflareTunnelService().generateConfig();
            return true;
        }

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

        if (command.equalsIgnoreCase("gateway-help")) {
            printGatewayHelp();
            return true;
        }

        if (command.equalsIgnoreCase("gateway-show")) {
            showGatewayConfig();
            return true;
        }

        if (command.equalsIgnoreCase("gateway-start")) {
            context.gatewayService().start();
            return true;
        }

        if (command.equalsIgnoreCase("gateway-stop")) {
            context.gatewayService().stop();
            return true;
        }

        if (command.equalsIgnoreCase("gateway-status")) {
            context.gatewayService().status();
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
        autoStartBotIfEnabled();
    }

    private void startTarget(String name, String command) throws IOException {
        context.targetProcessService().start(
                name,
                command,
                context.runtimeConfig().getCurrentDir()
        );
    }

    private void shutdownServices() {
        try { context.cloudflareTunnelService().stopAll(); } catch (Exception ignored) {}
        try { context.keepAliveBotService().stop(); } catch (Exception ignored) {}
        try { context.sshServerService().stop(); } catch (Exception ignored) {}
        try { context.gatewayService().stop(); } catch (Exception ignored) {}
        try { context.httpsService().stop(); } catch (Exception ignored) {}
        try { context.httpService().stop(); } catch (Exception ignored) {}
    }

    private void autoStartBotIfEnabled() {
        if (!context.stateStore().getBoolean("bot.enabled", false)) {
            return;
        }

        if (!context.stateStore().getBoolean("bot.autoStartWithMinecraft", true)) {
            return;
        }

        context.keepAliveBotService().start();
    }

    private void autoStopBotIfEnabled() {
        if (context.stateStore().getBoolean("bot.autoStopWithMinecraft", true)) {
            context.keepAliveBotService().stop();
        }
    }

    private void printMode() {
        System.out.println(CYAN + "[MJT MODE]" + RESET);
        System.out.println("Route mode : " + routeMode);
        System.out.println("Target run : " + context.targetProcessService().isRunning());
        System.out.println("Target name: " + context.targetProcessService().getTargetName());
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

    private void handleBotSet(String command) {
        String raw = command.substring("bot-set ".length()).trim();

        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Usage: .mjt bot set <key> <value>" + RESET);
            System.out.println("Example: .mjt bot set enabled true");
            System.out.println("Example: .mjt bot set username MJT_Renew");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        try {
            context.keepAliveBotService().setConfig(key, value);
        } catch (Exception e) {
            System.out.println(RED + "[BOT] Error saving config: " + e.getMessage() + RESET);
        }
    }


    private void handleHttpSet(String command) {
        String raw = command.substring("http-set ".length()).trim();

        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Usage: .mjt http set <key> <value>" + RESET);
            System.out.println("Example: .mjt http set host 127.0.0.1");
            System.out.println("Example: .mjt http set port 8080");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        try {
            context.httpService().setConfig(key, value);
        } catch (Exception e) {
            System.out.println(RED + "[HTTP] Error saving config: " + e.getMessage() + RESET);
        }
    }

    private void handleHttpsSet(String command) {
        String raw = command.substring("https-set ".length()).trim();

        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Usage: .mjt https set <key> <value>" + RESET);
            System.out.println("Example: .mjt https set enabled true");
            System.out.println("Example: .mjt https set port 8443");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        try {
            context.httpsService().setConfig(key, value);
        } catch (Exception e) {
            System.out.println(RED + "[HTTPS] Error saving config: " + e.getMessage() + RESET);
        }
    }

    private void handleCloudflareSet(String command) {
        String raw = command.substring("cloudflare-set ".length()).trim();

        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Usage: .mjt cloudflare set <key> <value>" + RESET);
            System.out.println("Example: .mjt cloudflare set name document.io.vn");
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

    private void handleTunnelSet(String command) {
        String raw = command.substring("tunnel-set ".length()).trim();

        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Usage: .mjt tunnel set <key> <value>" + RESET);
            System.out.println("Example: .mjt tunnel set cloudflared /home/container/MJT/system/downloads/cloudflared/cloudflared");
            System.out.println("Installer: .mjt system install cloudflared");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        try {
            context.cloudflareTunnelService().setConfig(key, value);
        } catch (Exception e) {
            System.out.println(RED + "[Tunnel] Error saving config: " + e.getMessage() + RESET);
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
            System.out.println(RED + "Usage: .mjt ssh set <key> <value>" + RESET);
            System.out.println("Example: .mjt ssh set port 40078");
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
            System.out.println("Example: .mjt gateway set gateway.public.port auto");
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
            System.out.println(RED + "Usage: .mjt gateway route add <name> <host> <port>" + RESET);
            System.out.println("Example: .mjt gateway route add mc 127.0.0.1 25565");
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

        System.out.println(CYAN + "[GATEWAY ROUTER CONFIG]" + RESET);
        System.out.println();
        System.out.println("gateway.enabled            = " + context.stateStore().get("gateway.enabled", "true"));
        System.out.println("gateway.public.host        = " + context.stateStore().get("gateway.public.host", "0.0.0.0"));
        System.out.println("gateway.public.port        = " + context.stateStore().get("gateway.public.port", "auto"));
        System.out.println();

        System.out.println(YELLOW + "HTTP route:" + RESET);
        System.out.println("gateway.route.http.enabled  = " + context.stateStore().get("gateway.route.http.enabled", "true"));
        System.out.println("gateway.route.http.host     = " + context.stateStore().get("gateway.route.http.host", context.stateStore().get("http.host", "127.0.0.1")));
        System.out.println("gateway.route.http.port     = " + context.stateStore().get("gateway.route.http.port", context.stateStore().get("http.port", "8080")));
        System.out.println("Role                        = forward HTTP traffic to local HTTP Service");
        System.out.println();

        System.out.println(YELLOW + "HTTPS route:" + RESET);
        System.out.println("gateway.route.https.enabled = " + context.stateStore().get("gateway.route.https.enabled", "true"));
        System.out.println("gateway.route.https.host    = " + context.stateStore().get("gateway.route.https.host", context.stateStore().get("https.host", "127.0.0.1")));
        System.out.println("gateway.route.https.port    = " + context.stateStore().get("gateway.route.https.port", context.stateStore().get("https.port", "8443")));
        System.out.println("Role                        = forward TLS/HTTPS traffic to local HTTPS Service");
        System.out.println();

        System.out.println(YELLOW + "SSH/SFTP route:" + RESET);
        System.out.println("gateway.ssh.enabled        = " + context.stateStore().get("gateway.ssh.enabled", "true"));
        System.out.println("gateway.ssh.host           = " + context.stateStore().get("gateway.ssh.host", "127.0.0.1"));
        System.out.println("gateway.ssh.port           = " + context.stateStore().get("gateway.ssh.port", "2022"));
        System.out.println();

        System.out.println(YELLOW + "Manual TCP routes:" + RESET);
        System.out.println("gateway.tcp.enabled        = " + context.stateStore().get("gateway.tcp.enabled", "true"));
        System.out.println("gateway.tcp.default        = " + context.stateStore().get("gateway.tcp.default", "close"));
        System.out.println("gateway.tcp.routes         = " + context.stateStore().get("gateway.tcp.routes", ""));

        List<String> routes = getGatewayRouteNames();

        if (routes.isEmpty()) {
            System.out.println("  No manual TCP routes found.");
        } else {
            for (String name : routes) {
                System.out.println();
                System.out.println("  [" + name + "]");
                System.out.println("  enabled = " + context.stateStore().get("gateway.tcp." + name + ".enabled", "false"));
                System.out.println("  host    = " + context.stateStore().get("gateway.tcp." + name + ".host", ""));
                System.out.println("  port    = " + context.stateStore().get("gateway.tcp." + name + ".port", ""));
            }
        }

        System.out.println();
        System.out.println(YELLOW + "HTTP/HTTPS services are separate. Use: .mjt http show or .mjt https show" + RESET);
        context.logService().write("[GATEWAY SHOW]\n");
    }

    private void printTunnelHelp() {
        printTitle("Cloudflare Tunnel Commands");

        printSection("1. Tunnel Role");
        System.out.println("  Cloudflare Tunnel is used for public HTTPS web access.");
        System.out.println("  HTTP sites stay local on 127.0.0.1 ports.");
        System.out.println("  Gateway remains a TCP router for Minecraft/SSH/manual TCP.");

        printSection("2. Tunnel Core");
        printCommand(".mjt tunnel show", "View Cloudflare Tunnel config/status");
        printCommand(".mjt tunnel start", "Start cloudflared");
        printCommand(".mjt tunnel stop", "Stop cloudflared");
        printCommand(".mjt tunnel set enabled true", "Enable tunnel service");
        printCommand(".mjt tunnel set mode token", "Use Cloudflare dashboard token mode");
        printCommand(".mjt tunnel set token <token>", "Save tunnel token");
        printCommand(".mjt tunnel set auto-start true", "Start tunnel on MJT startup");
        printCommand(".mjt tunnel set cloudflared cloudflared", "Set cloudflared binary path");
        printCommand(".mjt tunnel set mode quick", "Use one manual Quick Tunnel URL");
        printCommand(".mjt tunnel set local http://127.0.0.1:8081", "Set manual Quick Tunnel local origin");

        printSection("3. Guest Quick Tunnel");
        printCommand(".mjt website guest create", "Create guest website with trycloudflare.com URL");
        printCommand(".mjt website guest show <id>", "Show guest public URL");
        printCommand(".mjt website guest stop <id>", "Stop guest quick tunnel");

        printSection("4. Route Config");
        printCommand(".mjt tunnel route list", "List local route config");
        printCommand(".mjt tunnel route add main main.example.com http://127.0.0.1:8081", "Add route metadata");
        printCommand(".mjt tunnel route add docs docs.example.com http://127.0.0.1:8082", "Add docs route");
        printCommand(".mjt tunnel route remove docs", "Remove route metadata");
        printCommand(".mjt tunnel config generate", "Generate config.yml for config mode");

        printSection("Notes");
        System.out.println("  In token mode, public hostnames are usually configured in Cloudflare Zero Trust dashboard.");
        System.out.println("  In config mode, MJT generates ingress rules from tunnel routes.");
        System.out.println();
    }

    private void printWebsiteHelp() {
        printTitle("Website Commands");

        printSection("1. Local Websites");
        System.out.println("  Websites are served locally from /home/container/server/website/www.");
        System.out.println("  Public HTTPS should use Cloudflare Tunnel, not the Gateway game port.");
        printCommand(".mjt website list", "List configured websites");
        printCommand(".mjt website show main", "Show one website");
        printCommand(".mjt website add docs 127.0.0.1 8082 /home/container/server/website/www/docs", "Add website");
        printCommand(".mjt website start docs", "Start one website");
        printCommand(".mjt website stop docs", "Stop one website");
        printCommand(".mjt website restart docs", "Restart one website");
        printCommand(".mjt website set docs spa true", "Set website option");

        printSection("2. Guest Quick Tunnel");
        System.out.println("  Guest sites use cloudflared quick tunnel and receive a temporary trycloudflare.com URL.");
        printCommand(".mjt website guest create", "Create guest website and quick tunnel");
        printCommand(".mjt website guest list", "List guest websites");
        printCommand(".mjt website guest show <id>", "Show guest public URL");
        printCommand(".mjt website guest stop <id>", "Stop guest HTTP site and quick tunnel");
        printCommand(".mjt website guest restart <id>", "Restart guest HTTP site and quick tunnel");
        printCommand(".mjt website guest remove <id>", "Remove guest config, keep files for safety");
        System.out.println();
    }

    private void printGatewayHelp() {
        printTitle("Gateway Router Commands");

        printSection("1. Gateway Role");
        System.out.println("  Gateway is a public router / forwarder only.");
        System.out.println("  Gateway does not serve website files anymore.");
        System.out.println("  HTTP Service is separate and runs locally, usually 127.0.0.1:8080.");
        System.out.println("  HTTPS Service is separate and runs locally, usually 127.0.0.1:8443.");
        System.out.println("  HTTP/HTTPS/SSH are detected before falling back to default TCP route such as Minecraft.");

        printSection("2. Gateway Core");
        printCommand(".mjt gateway help", "View Gateway help");
        printCommand(".mjt gateway show", "View Gateway router configuration");
        printCommand(".mjt gateway start", "Start Gateway router");
        printCommand(".mjt gateway stop", "Stop Gateway router");
        printCommand(".mjt gateway status", "Show Gateway status");
        printCommand(".mjt gateway set gateway.public.host 0.0.0.0", "Set public bind host");
        printCommand(".mjt gateway set gateway.public.port auto", "Use SERVER_PORT as public port");

        printSection("3. HTTP / HTTPS Forward Routes");
        printCommand(".mjt gateway set gateway.route.http.enabled true", "Forward HTTP traffic to HTTP Service");
        printCommand(".mjt gateway set gateway.route.http.host 127.0.0.1", "Set HTTP backend host");
        printCommand(".mjt gateway set gateway.route.http.port 8080", "Set HTTP backend port");
        printCommand(".mjt gateway set gateway.route.https.enabled true", "Forward TLS/HTTPS traffic to HTTPS Service");
        printCommand(".mjt gateway set gateway.route.https.host 127.0.0.1", "Set HTTPS backend host");
        printCommand(".mjt gateway set gateway.route.https.port 8443", "Set HTTPS backend port");

        printSection("4. SSH / SFTP Gateway Proxy");
        printCommand(".mjt gateway set gateway.ssh.enabled true", "Enable SSH/SFTP route via Gateway");
        printCommand(".mjt gateway set gateway.ssh.host 127.0.0.1", "Set internal SSH/SFTP host");
        printCommand(".mjt gateway set gateway.ssh.port 2022", "Set internal SSH/SFTP port");

        printSection("5. Manual TCP Routes");
        printCommand(".mjt gateway route add <name> <host> <port>", "Add/update TCP route");
        printCommand(".mjt gateway route remove <name>", "Remove TCP route");
        printCommand(".mjt gateway route enable <name>", "Enable TCP route");
        printCommand(".mjt gateway route disable <name>", "Disable TCP route");
        printCommand(".mjt gateway default <name>", "Select route as default TCP fallback");
        printCommand(".mjt gateway default close", "Close TCP fallback when protocol is unknown");

        printSection("Examples");
        printCommand(".mjt http set port 8080", "HTTP Service local port");
        printCommand(".mjt https set port 8443", "HTTPS Service local port");
        printCommand(".mjt https cert self-signed", "Create a test self-signed HTTPS certificate");
        printCommand(".mjt gateway route add mc 127.0.0.1 25565", "Add Minecraft Java route");
        printCommand(".mjt gateway default mc", "Send unknown TCP fallback to Minecraft");

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


    private void printVersion() {
        System.out.println(CYAN + BuildInfo.displayVersion() + RESET);
        System.out.println("Release : " + BuildInfo.RELEASE);
        System.out.println("Java    : " + System.getProperty("java.version"));
        System.out.println("Workdir : " + context.runtimeConfig().getCurrentDir());
    }

    private void printHttpHelp() {
        printTitle("HTTP Service Commands");
        System.out.println("HTTP Service is a local website service.");
        System.out.println("It is separate from Gateway Router.");
        System.out.println();
        printCommand(".mjt http show", "View HTTP service config/status");
        printCommand(".mjt http start", "Start local HTTP service");
        printCommand(".mjt http stop", "Stop local HTTP service");
        printCommand(".mjt http set host 127.0.0.1", "Bind HTTP locally");
        printCommand(".mjt http set port 8080", "Set HTTP local port");
        printCommand(".mjt http set root /home/container/server/website/www/main", "Set web root");
        printCommand(".mjt http set index index.html", "Set index file");
        printCommand(".mjt http set spa true", "Enable SPA fallback");
        printCommand(".mjt http set auto-https true", "Mark HTTPS as handled by outer proxy/tunnel");
        System.out.println();
    }

    private void printHttpsHelp() {
        printTitle("HTTPS Service Commands");
        System.out.println("HTTPS Service is a local TLS website service.");
        System.out.println("Gateway detects TLS ClientHello and forwards HTTPS traffic to this service.");
        System.out.println();
        printCommand(".mjt https show", "View HTTPS service config/status");
        printCommand(".mjt https start", "Start local HTTPS service");
        printCommand(".mjt https stop", "Stop local HTTPS service");
        printCommand(".mjt https set enabled true", "Enable HTTPS service");
        printCommand(".mjt https set host 127.0.0.1", "Bind HTTPS locally");
        printCommand(".mjt https set port 8443", "Set HTTPS local port");
        printCommand(".mjt https set root /home/container/server/website/www/main", "Set web root");
        printCommand(".mjt https set keystore /home/container/MJT/services/https/https.p12", "Set PKCS12 keystore path");
        printCommand(".mjt https set password change-me", "Set keystore password");
        printCommand(".mjt https set cn localhost", "Set self-signed certificate CN");
        printCommand(".mjt https cert self-signed", "Generate a self-signed PKCS12 cert using keytool");
        System.out.println();
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
        HelpCenter.printIndex();
        context.logService().write("[HELP INDEX]\n");
    }
}
