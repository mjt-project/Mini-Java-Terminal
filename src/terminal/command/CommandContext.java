package terminal.command;

import terminal.services.CloudflareDnsService;
import terminal.services.GatewayService;
import terminal.services.SshServerService;
import terminal.system.CommandGuard;
import terminal.system.LogService;
import terminal.system.PublicIpService;
import terminal.system.RuntimeConfig;
import terminal.system.ShellRunner;
import terminal.system.StateStore;

public record CommandContext(
        LogService logService,
        StateStore stateStore,
        RuntimeConfig runtimeConfig,
        ShellRunner shellRunner,
        PublicIpService publicIpService,
        CommandGuard commandGuard,
        CloudflareDnsService cloudflareDnsService,
        SshServerService sshServerService,
        GatewayService gatewayService
) {
}