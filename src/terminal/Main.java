package terminal;

import terminal.command.CommandCenter;
import terminal.command.CommandContext;
import terminal.services.CloudflareDnsService;
import terminal.services.SshServerService;
import terminal.system.CommandGuard;
import terminal.system.LogService;
import terminal.system.PublicIpService;
import terminal.system.RuntimeConfig;
import terminal.system.ShellRunner;
import terminal.system.StateStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

public class Main {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";

    public static void main(String[] args) {
        try {
            LogService logService = new LogService(Paths.get("logs"));
            StateStore stateStore = new StateStore(Paths.get("terminal-state.properties"));
            RuntimeConfig runtimeConfig = new RuntimeConfig();

            ShellRunner shellRunner = new ShellRunner(logService);
            PublicIpService publicIpService = new PublicIpService(logService);
            CommandGuard commandGuard = new CommandGuard(logService);

            CloudflareDnsService cloudflareDnsService =
                    new CloudflareDnsService(stateStore, publicIpService, logService);

            SshServerService sshServerService =
                    new SshServerService(stateStore, logService, commandGuard);

            CommandContext commandContext = new CommandContext(
                    logService,
                    stateStore,
                    runtimeConfig,
                    shellRunner,
                    publicIpService,
                    commandGuard,
                    cloudflareDnsService,
                    sshServerService
            );

            CommandCenter commandCenter = new CommandCenter(commandContext);
            sshServerService.setCommandCenter(commandCenter);

            printStartupMessage(logService, runtimeConfig);

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
        System.out.println(GREEN + "Mini Java Terminal started." + RESET);
        System.out.println(CYAN + "Java terminal panel is ready." + RESET);
        System.out.println(YELLOW + "Gõ help để xem lệnh hỗ trợ." + RESET);
        System.out.println("Current dir: " + runtimeConfig.getCurrentDir());
        System.out.println("Log file   : " + logService.getLogFile().toAbsolutePath());
        System.out.println();

        logService.write("[START] Mini Java Terminal\n");
        logService.write("[CURRENT DIR] " + runtimeConfig.getCurrentDir() + "\n");
    }
}