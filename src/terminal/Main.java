package terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import terminal.command.CommandCenter;
import terminal.command.CommandContext;
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

public class Main {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";

    public static void main(String[] args) {
        try {
            LogService logService = new LogService(Paths.get("logs"));
            StateStore stateStore = new StateStore(Paths.get("mjt-config"));
            RuntimeConfig runtimeConfig = new RuntimeConfig();

            ShellRunner shellRunner = new ShellRunner(logService);
            PublicIpService publicIpService = new PublicIpService(logService);
            CommandGuard commandGuard = new CommandGuard(logService);
            TargetProcessService targetProcessService = new TargetProcessService(logService);

            CloudflareDnsService cloudflareDnsService =
                    new CloudflareDnsService(stateStore, publicIpService, logService);

            SshServerService sshServerService =
                    new SshServerService(stateStore, logService, commandGuard);

            GatewayService gatewayService = new GatewayService(logService, stateStore);

            CommandContext commandContext = new CommandContext(
                    logService,
                    stateStore,
                    runtimeConfig,
                    shellRunner,
                    publicIpService,
                    commandGuard,
                    cloudflareDnsService,
                    sshServerService,
                    gatewayService,
                    targetProcessService
            );

            CommandCenter commandCenter = new CommandCenter(commandContext);
            sshServerService.setCommandCenter(commandCenter);

            printStartupMessage(logService, runtimeConfig);
            gatewayService.start();

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

    private static void printStartupMessage(
            LogService logService,
            RuntimeConfig runtimeConfig
    ) throws IOException {
        System.out.println();
        System.out.println(GREEN + "==================================================" + RESET);
        System.out.println(GREEN + " Mini Java Terminal v2.4.0" + RESET);
        System.out.println(GREEN + "==================================================" + RESET);

        System.out.println(CYAN + " Status      : READY" + RESET);
        System.out.println(" Workdir     : " + runtimeConfig.getCurrentDir());
        System.out.println(" Log file    : " + logService.getLogFile().toAbsolutePath());

        System.out.println();
        System.out.println(YELLOW + " Quick commands:" + RESET);
        System.out.println("  .mjt help              - Show all commands");
        System.out.println("  .mjt gateway help      - Show Gateway commands");
        System.out.println("  .mjt ssh show          - Show SSH/SFTP config");
        System.out.println("  .mjt minecraft-start   - Start Minecraft as managed target");
        System.out.println("  .command <shell>       - Force run Linux shell command");
        System.out.println("  .mjt exit              - Stop Mini Java Terminal");
        System.out.println();

        logService.write("[START] Mini Java Terminal v2.4.0\n");
        logService.write("[CURRENT DIR] " + runtimeConfig.getCurrentDir() + "\n");
    }
}