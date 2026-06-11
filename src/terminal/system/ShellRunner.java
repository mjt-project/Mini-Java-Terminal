package terminal.system;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;

public class ShellRunner {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String RED = "\u001B[31m";

    private final LogService logService;

    public ShellRunner(LogService logService) {
        this.logService = logService;
    }

    public void run(String command, Path currentDir, int timeoutSeconds) {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            System.out.println(CYAN + currentDir + " $ " + command + RESET);

            logService.write("[COMMAND] " + command + "\n");
            logService.write("[DIR] " + currentDir + "\n");

            ProcessBuilder processBuilder = createProcessBuilder(command);
            processBuilder.directory(currentDir.toFile());
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            Future<?> outputFuture = executor.submit(() -> readProcessOutput(process));

            waitForProcess(process, timeoutSeconds);

            outputFuture.get(3, TimeUnit.SECONDS);

            int exitCode = process.exitValue();

            if (exitCode == 0) {
                System.out.println(GREEN + "Exit code: " + exitCode + RESET);
            } else {
                System.out.println(RED + "Exit code: " + exitCode + RESET);
            }

            logService.write("[EXIT CODE] " + exitCode + "\n");

        } catch (TimeoutException e) {
            System.out.println(RED + "Output reader timeout." + RESET);

            try {
                logService.write("[OUTPUT TIMEOUT]\n");
            } catch (IOException ignored) {
            }

        } catch (Exception e) {
            System.out.println(RED + "Lỗi khi chạy command: " + e.getMessage() + RESET);

            try {
                logService.write("[ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }

        } finally {
            executor.shutdownNow();
        }
    }

    private ProcessBuilder createProcessBuilder(String command) {
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        }

        return new ProcessBuilder("bash", "-lc", command);
    }

    private void readProcessOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                logService.write(line + "\n");
            }

        } catch (IOException e) {
            try {
                logService.write("[OUTPUT ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private void waitForProcess(Process process, int timeoutSeconds)
            throws InterruptedException, IOException {

        if (timeoutSeconds <= 0) {
            process.waitFor();
            return;
        }

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

        if (!finished) {
            process.destroyForcibly();

            System.out.println(RED + "Command bị dừng vì chạy quá "
                    + timeoutSeconds + " giây." + RESET);

            logService.write("[TIMEOUT] Command killed after "
                    + timeoutSeconds + " seconds\n");
        }
    }
}