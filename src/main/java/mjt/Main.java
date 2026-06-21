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
import main.java.mjt.services.code.OpenVscodeService;
import main.java.mjt.services.cloudflare.tunnel.CloudflareTunnelService;
import main.java.mjt.services.cloudflare.tunnel.GuestWebsiteService;
import main.java.mjt.services.gateway.GatewayService;
import main.java.mjt.services.http.HttpService;
import main.java.mjt.services.https.HttpsService;
import main.java.mjt.services.minecraft.MinecraftInstallerService;
import main.java.mjt.services.minecraft.MinecraftProcessManagerService;
import main.java.mjt.services.panel.PanelApiV1Service;
import main.java.mjt.services.panel.PanelFrontendInstallerService;
import main.java.mjt.services.panel.PanelService;
import main.java.mjt.services.proot.ProotService;
import main.java.mjt.services.service.GuestServiceManager;
import main.java.mjt.services.sshd.SshServerService;
import main.java.mjt.services.workspace.WorkspaceFileService;
import main.java.mjt.services.workspace.WorkspaceRegistryService;
import main.java.mjt.system.BuildInfo;
import main.java.mjt.system.CommandGuard;
import main.java.mjt.system.KeepAliveBotService;
import main.java.mjt.system.LogService;
import main.java.mjt.system.PublicIpService;
import main.java.mjt.system.RuntimeConfig;
import main.java.mjt.system.ShellRunner;
import main.java.mjt.system.StateStore;
import main.java.mjt.system.TargetProcessService;
import main.java.mjt.system.download.SystemDownloadService;

/** Main MJT control-plane process. */
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
            ProotService prootService = new ProotService(stateStore, logService);
            MinecraftProcessManagerService minecraftProcessManagerService = new MinecraftProcessManagerService(stateStore, logService, prootService);
            MinecraftInstallerService minecraftInstallerService = new MinecraftInstallerService(stateStore, logService);
            WorkspaceRegistryService workspaceRegistryService = new WorkspaceRegistryService(stateStore);
            WorkspaceFileService workspaceFileService = new WorkspaceFileService(stateStore, workspaceRegistryService);
            PanelService panelService = new PanelService(
                    stateStore,
                    logService,
                    minecraftProcessManagerService,
                    minecraftInstallerService,
                    workspaceRegistryService,
                    workspaceFileService
            );
            PanelFrontendInstallerService panelFrontendInstallerService = new PanelFrontendInstallerService(stateStore, logService);
            KeepAliveBotService keepAliveBotService = new KeepAliveBotService(stateStore, logService);
            CloudflareDnsService cloudflareDnsService = new CloudflareDnsService(stateStore, publicIpService, logService);
            CloudflareTunnelService cloudflareTunnelService = new CloudflareTunnelService(stateStore, logService);
            SshServerService sshServerService = new SshServerService(stateStore, logService, commandGuard);
            HttpService httpService = new HttpService(stateStore, logService);
            HttpsService httpsService = new HttpsService(stateStore, logService);
            GatewayService gatewayService = new GatewayService(logService, stateStore);
            GuestWebsiteService guestWebsiteService = new GuestWebsiteService(
                    stateStore,
                    logService,
                    httpService,
                    cloudflareTunnelService
            );
            OpenVscodeService openVscodeService = new OpenVscodeService(stateStore, logService, prootService);
            GuestServiceManager guestServiceManager = new GuestServiceManager(
                    stateStore,
                    logService,
                    prootService,
                    cloudflareTunnelService
            );
            // v1 is a dedicated loopback control API while the existing
            // PanelService remains available during frontend migration.
            PanelApiV1Service panelApiV1Service = new PanelApiV1Service(
                    stateStore,
                    logService,
                    minecraftProcessManagerService,
                    guestServiceManager
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
                    panelService,
                    panelFrontendInstallerService,
                    minecraftProcessManagerService,
                    minecraftInstallerService,
                    targetProcessService,
                    keepAliveBotService,
                    workspaceRegistryService,
                    workspaceFileService,
                    prootService,
                    openVscodeService,
                    guestServiceManager,
                    panelApiV1Service
            );

            AtomicBoolean shutdownStarted = new AtomicBoolean(false);
            Runtime.getRuntime().addShutdownHook(new Thread(
                    () -> shutdownServices(
                            shutdownStarted,
                            logService,
                            cloudflareTunnelService,
                            httpService,
                            httpsService,
                            panelService,
                            panelApiV1Service,
                            minecraftProcessManagerService,
                            openVscodeService,
                            guestServiceManager,
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
            if (stateStore.getBoolean("panel.enabled", false) && stateStore.getBoolean("panel.autoStart", true)) {
                panelService.start();
            }
            if (stateStore.getBoolean("panel.api.enabled", true) && stateStore.getBoolean("panel.api.autoStart", true)) {
                panelApiV1Service.start();
            }
            if (stateStore.getBoolean("tunnel.enabled", false) && stateStore.getBoolean("tunnel.autoStart", false)) {
                cloudflareTunnelService.start();
            }
            // Guest workloads only start after the Core services and shutdown hook
            // are ready. Each service still needs an explicit autostart=true.
            guestServiceManager.startAutoStartServices();

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
            PanelService panelService,
            PanelApiV1Service panelApiV1Service,
            MinecraftProcessManagerService minecraftProcessManagerService,
            OpenVscodeService openVscodeService,
            GuestServiceManager guestServiceManager,
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
        try { cloudflareTunnelService.stopAll(); } catch (Exception ignored) { }
        try { minecraftProcessManagerService.stopAll(); } catch (Exception ignored) { }
        try { openVscodeService.stop(); } catch (Exception ignored) { }
        try { guestServiceManager.stopAll(); } catch (Exception ignored) { }
        try { keepAliveBotService.stop(); } catch (Exception ignored) { }
        try { sshServerService.stop(); } catch (Exception ignored) { }
        try { panelApiV1Service.stop(); } catch (Exception ignored) { }
        try { panelService.stop(); } catch (Exception ignored) { }
        try { gatewayService.stop(); } catch (Exception ignored) { }
        try { httpsService.stop(); } catch (Exception ignored) { }
        try { httpService.stop(); } catch (Exception ignored) { }
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
        System.out.println(CYAN + " Status : READY" + RESET);
        System.out.println(" Workdir : " + runtimeConfig.getCurrentDir());
        System.out.println(" Config dir : " + stateStore.getConfigDir());
        System.out.println(" Server dir : /home/container/server");
        System.out.println(" Guest rootfs : " + stateStore.get("proot.rootfs", stateStore.getConfigDir().resolve("system/rootfs").toString()));
        System.out.println(" Log file : " + logService.getLogFile().toAbsolutePath());
        System.out.println();
        System.out.println(YELLOW + " Quick commands:" + RESET);
        System.out.println(" .mjt help - Show all commands");
        System.out.println(" .mjt proot show - Show PRoot guest runtime config");
        System.out.println(" .mjt proot test - Verify APT inside PRootFS");
        System.out.println(" .mjt code start - Start OpenVSCode Server inside PRootFS");
        System.out.println(" .mjt service list - List generic PRoot guest services");
        System.out.println(" .mjt website list - Show HTTP websites");
        System.out.println(" .mjt panel start - Start legacy local panel server");
        System.out.println(" .mjt panel api show - Show v1 panel control API status");
        System.out.println(" .mjt system install cloudflared - Auto install cloudflared binary");
        System.out.println(" .mjt tunnel show - Show Cloudflare Tunnel config");
        System.out.println(" .mjt gateway show - Show Gateway router config");
        System.out.println(" .mjt minecraft start - Start Minecraft managed target");
        System.out.println(" .mjt ssh show - Show SSH/SFTP config");
        System.out.println(" .mjt exit - Stop Mini Java Terminal");
        System.out.println();
        logService.write("[START] " + BuildInfo.displayVersion() + " - " + BuildInfo.RELEASE + "\n");
        logService.write("[CURRENT DIR] " + runtimeConfig.getCurrentDir() + "\n");
        logService.write("[CONFIG DIR] " + stateStore.getConfigDir() + "\n");
        logService.write("[SERVER DIR] /home/container/server\n");
    }
}
