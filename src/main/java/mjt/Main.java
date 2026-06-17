package main.java.mjt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

import main.java.mjt.command.CommandCenter;
import main.java.mjt.command.CommandContext;
import main.java.mjt.services.cloudflare.CloudflareDnsService;
import main.java.mjt.services.gateway.GatewayService;
import main.java.mjt.services.http.HttpService;
import main.java.mjt.services.https.HttpsService;
import main.java.mjt.services.sshd.SshServerService;
import main.java.mjt.services.cloudflare.tunnel.CloudflareTunnelService;
import main.java.mjt.services.cloudflare.tunnel.GuestWebsiteService;
import main.java.mjt.system.BuildInfo;
import main.java.mjt.system.CommandGuard;
import main.java.mjt.system.KeepAliveBotService;
import main.java.mjt.system.LogService;
import main.java.mjt.system.PublicIpService;
import main.java.mjt.system.RuntimeConfig;
import main.java.mjt.system.ShellRunner;
import main.java.mjt.system.download.SystemDownloadService;
import main.java.mjt.system.StateStore;
import main.java.mjt.system.TargetProcessService;

public class Main {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";

    public static void main(String[] args) {
        try {
            LogService logService = new LogService(Paths.get("MJT", "logs"));
            StateStore stateStore = new StateStore(Paths.get("MJT"));
            RuntimeConfig runtimeConfig = new RuntimeConfig();

            ShellRunner shellRunner = new ShellRunner(logService);
            PublicIpService publicIpService = new PublicIpService(logService);
            CommandGuard commandGuard = new CommandGuard(logService);
            SystemDownloadService systemDownloadService = new SystemDownloadService(stateStore, logService);
            TargetProcessService targetProcessService = new TargetProcessService(logService);
            KeepAliveBotService keepAliveBotService = new KeepAliveBotService(stateStore, logService);

            CloudflareDnsService cloudflareDnsService =
                    new CloudflareDnsService(stateStore, publicIpService, logService);

            CloudflareTunnelService cloudflareTunnelService =
                    new CloudflareTunnelService(stateStore, logService);

            SshServerService sshServerService =
                    new SshServerService(stateStore, logService, commandGuard);

            HttpService httpService = new HttpService(stateStore, logService);
            HttpsService httpsService = new HttpsService(stateStore, logService);
            GatewayService gatewayService = new GatewayService(logService, stateStore);
            GuestWebsiteService guestWebsiteService = new GuestWebsiteService(
                    stateStore,
                    logService,
                    httpService,
                    cloudflareTunnelService
            );

            CommandContext commandContext = new CommandContext(
                    logService,
                    stateStore,
                    runtimeConfig,
                    shellRunner,
                    publicIpService,
                    commandGuard,
                    systemDownloadService,
                    cloudflareDnsService,
                    cloudflareTunnelService,
                    guestWebsiteService,
                    sshServerService,
                    gatewayService,
                    httpService,
                    httpsService,
                    targetProcessService,
                    keepAliveBotService
            );

            AtomicBoolean shutdownStarted = new AtomicBoolean(false);
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> shutdownServices(
                            shutdownStarted,
                            logService,
                            cloudflareTunnelService,
                            httpService,
                            httpsService,
                            gatewayService,
                            sshServerService,
                            keepAliveBotService
                    ),
                    "mjt-shutdown-hook"
            ));

            CommandCenter commandCenter = new CommandCenter(commandContext);
            sshServerService.setCommandCenter(commandCenter);

            printStartupMessage(logService, runtimeConfig, stateStore);
            httpService.start();
            if (stateStore.getBoolean("https.enabled", false)) {
                httpsService.start();
            }
            gatewayService.start();
            if (stateStore.getBoolean("tunnel.enabled", false)
                    && stateStore.getBoolean("tunnel.autoStart", false)) {
                cloudflareTunnelService.start();
            }

            BufferedReader console = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );

            String input;

            while ((input = console.readLine()) != null) {
                input = input.trim();

                if (input.isEmpty()) {
                    continue;
                }

                commandCenter.handle(input);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void shutdownServices(
            AtomicBoolean shutdownStarted,
            LogService logService,
            CloudflareTunnelService cloudflareTunnelService,
            HttpService httpService,
            HttpsService httpsService,
            GatewayService gatewayService,
            SshServerService sshServerService,
            KeepAliveBotService keepAliveBotService
    ) {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return;
        }

        try {
            logService.write("[SHUTDOWN] stopping services\n");
        } catch (IOException ignored) {
        }

        try { cloudflareTunnelService.stopAll(); } catch (Exception ignored) {}
        try { keepAliveBotService.stop(); } catch (Exception ignored) {}
        try { sshServerService.stop(); } catch (Exception ignored) {}
        try { gatewayService.stop(); } catch (Exception ignored) {}
        try { httpsService.stop(); } catch (Exception ignored) {}
        try { httpService.stop(); } catch (Exception ignored) {}
    }

    private static void printStartupMessage(
            LogService logService,
            RuntimeConfig runtimeConfig,
            StateStore stateStore
    ) throws IOException {
        System.out.println();
        System.out.println(GREEN + "==================================================" + RESET);
        System.out.println(GREEN + " " + BuildInfo.displayVersion() + RESET);
        System.out.println(GREEN + " " + BuildInfo.RELEASE + RESET);
        System.out.println(GREEN + "==================================================" + RESET);

        System.out.println(CYAN + " Status      : READY" + RESET);
        System.out.println(" Workdir     : " + runtimeConfig.getCurrentDir());
        System.out.println(" Config dir  : " + stateStore.getConfigDir());
        System.out.println(" Server dir  : /home/container/server");
        System.out.println(" Web root    : /home/container/server/website/www");
        System.out.println(" Log file    : " + logService.getLogFile().toAbsolutePath());

        System.out.println();
        System.out.println(YELLOW + " Quick commands:" + RESET);
        System.out.println("  .mjt help                 - Show all commands");
        System.out.println("  .mjt website list         - Show HTTP websites");
        System.out.println("  .mjt website guest create - Create guest site with trycloudflare URL");
        System.out.println("  .mjt system install cloudflared - Auto install cloudflared binary");
        System.out.println("  .mjt tunnel show          - Show Cloudflare Tunnel config");
        System.out.println("  .mjt gateway show         - Show Gateway router config");
        System.out.println("  .mjt minecraft start      - Start Minecraft managed target");
        System.out.println("  .mjt bot show             - Show KeepAlive bot status");
        System.out.println("  .mjt ssh show             - Show SSH/SFTP config");
        System.out.println("  .mjt exit                 - Stop Mini Java Terminal");
        System.out.println();

        logService.write("[START] " + BuildInfo.displayVersion() + " - " + BuildInfo.RELEASE + "\n");
        logService.write("[CURRENT DIR] " + runtimeConfig.getCurrentDir() + "\n");
        logService.write("[CONFIG DIR] " + stateStore.getConfigDir() + "\n");
        logService.write("[SERVER DIR] /home/container/server\n");
    }
}
