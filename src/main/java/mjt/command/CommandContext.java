package main.java.mjt.command;

import main.java.mjt.services.cloudflare.CloudflareDnsService;
import main.java.mjt.services.gateway.GatewayService;
import main.java.mjt.services.http.HttpService;
import main.java.mjt.services.https.HttpsService;
import main.java.mjt.services.panel.PanelService;
import main.java.mjt.services.panel.PanelFrontendInstallerService;
import main.java.mjt.services.minecraft.MinecraftInstallerService;
import main.java.mjt.services.minecraft.MinecraftProcessManagerService;
import main.java.mjt.services.workspace.WorkspaceRegistryService;
import main.java.mjt.services.workspace.WorkspaceFileService;
import main.java.mjt.services.sshd.SshServerService;
import main.java.mjt.services.cloudflare.tunnel.CloudflareTunnelService;
import main.java.mjt.services.cloudflare.tunnel.GuestWebsiteService;

import main.java.mjt.system.CommandGuard;
import main.java.mjt.system.KeepAliveBotService;
import main.java.mjt.system.LogService;
import main.java.mjt.system.PublicIpService;
import main.java.mjt.system.RuntimeConfig;
import main.java.mjt.system.ShellRunner;
import main.java.mjt.system.download.SystemDownloadService;
import main.java.mjt.system.StateStore;
import main.java.mjt.system.TargetProcessService;

public record CommandContext(
        LogService logService,
        StateStore stateStore,
        RuntimeConfig runtimeConfig,
        ShellRunner shellRunner,
        PublicIpService publicIpService,
        CommandGuard commandGuard,
        SystemDownloadService systemDownloadService,
        CloudflareDnsService cloudflareDnsService,
        CloudflareTunnelService cloudflareTunnelService,
        GuestWebsiteService guestWebsiteService,
        SshServerService sshServerService,
        GatewayService gatewayService,
        HttpService httpService,
        HttpsService httpsService,
        PanelService panelService,
        PanelFrontendInstallerService panelFrontendInstallerService,
        MinecraftProcessManagerService minecraftProcessManagerService,
        MinecraftInstallerService minecraftInstallerService,
        TargetProcessService targetProcessService,
        KeepAliveBotService keepAliveBotService,
        WorkspaceRegistryService workspaceRegistryService,
        WorkspaceFileService workspaceFileService
) {
}
