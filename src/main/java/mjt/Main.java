package main.java.mjt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

import main.java.mjt.command.CommandCenter;
import main.java.mjt.command.CommandContext;
import main.java.mjt.services.cloudflare.CloudflareDnsService;
import main.java.mjt.services.gateway.GatewayService;
import main.java.mjt.services.sshd.SshServerService;
import main.java.mjt.system.CommandGuard;
import main.java.mjt.system.LogService;
import main.java.mjt.system.PublicIpService;
import main.java.mjt.system.RuntimeConfig;
import main.java.mjt.system.ShellRunner;
import main.java.mjt.system.StateStore;
import main.java.mjt.system.TargetProcessService;

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
        System.out.println(GREEN + " Mini Java Terminal v2.4.6" + RESET);
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