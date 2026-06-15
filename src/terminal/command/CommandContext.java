package terminal.command;

import terminal.services.cloudflare.CloudflareDnsService;
import terminal.services.gateway.GatewayService;
import terminal.services.sshd.SshServerService;

import terminal.system.CommandGuard;
import terminal.system.LogService;
import terminal.system.PublicIpService;
import terminal.system.RuntimeConfig;
import terminal.system.ShellRunner;
import terminal.system.StateStore;
import terminal.system.TargetProcessService;

public record CommandContext(
        LogService logService,
        StateStore stateStore,
        RuntimeConfig runtimeConfig,
        ShellRunner shellRunner,
        PublicIpService publicIpService,
        CommandGuard commandGuard,
        CloudflareDnsService cloudflareDnsService,
        SshServerService sshServerService,
        GatewayService gatewayService,
        TargetProcessService targetProcessService
) {
}