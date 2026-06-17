package main.java.mjt.command;

import main.java.mjt.services.cloudflare.CloudflareDnsService;
import main.java.mjt.services.gateway.GatewayService;
import main.java.mjt.services.sshd.SshServerService;

import main.java.mjt.system.CommandGuard;
import main.java.mjt.system.KeepAliveBotService;
import main.java.mjt.system.LogService;
import main.java.mjt.system.PublicIpService;
import main.java.mjt.system.RuntimeConfig;
import main.java.mjt.system.ShellRunner;
import main.java.mjt.system.StateStore;
import main.java.mjt.system.TargetProcessService;

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
        TargetProcessService targetProcessService,
        KeepAliveBotService keepAliveBotService
) {
}
