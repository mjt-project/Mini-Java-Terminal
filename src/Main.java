package main.java.mjt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/** Main MJT control-plane process. */
public class Main {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";

    public static void main(String[] args) {
        try {
            LogService logService = new LogService(Paths.get("MJT", "logs"));
                logService.write("[START] " + BuildInfo.displayVersion() + " - " + BuildInfo.RELEASE + "\n"
            );

            CommandContext commandContext = new CommandContext(
                    logService,
                    new RuntimeConfig(),
                    new StateStore(Paths.get("MJT", "config"))
            );
        }
        catch (IOException e) {
            System.err.println("Failed to initialize MJT: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
