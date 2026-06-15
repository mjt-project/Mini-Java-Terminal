package terminal.system;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

public class TargetProcessService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final LogService logService;

    private Process process;
    private BufferedWriter processInput;
    private String targetName = "none";
    private String startCommand = "";
    private Path workingDirectory;
    private Runnable onExit;

    public TargetProcessService(LogService logService) {
        this.logService = logService;
    }

    public synchronized void setOnExit(Runnable onExit) {
        this.onExit = onExit;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized String getTargetName() {
        return targetName;
    }

    public synchronized String getStartCommand() {
        return startCommand;
    }

    public synchronized Path getWorkingDirectory() {
        return workingDirectory;
    }

    public synchronized void start(String name, String command, Path currentDir) throws IOException {
        if (isRunning()) {
            System.out.println(YELLOW + "[TARGET] Already running: " + targetName + RESET);
            return;
        }

        if (command == null || command.isBlank()) {
            System.out.println(RED + "[TARGET] Empty start command." + RESET);
            return;
        }

        targetName = (name == null || name.isBlank()) ? "target" : name.trim();
        startCommand = command.trim();
        workingDirectory = currentDir.toAbsolutePath().normalize();

        ProcessBuilder processBuilder = createProcessBuilder(startCommand);
        processBuilder.directory(workingDirectory.toFile());
        processBuilder.redirectErrorStream(true);

        process = processBuilder.start();
        processInput = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)
        );

        Thread outputThread = new Thread(
                () -> readOutput(process),
                "mjt-target-output-" + safeThreadName(targetName)
        );
        outputThread.setDaemon(true);
        outputThread.start();

        Thread watcherThread = new Thread(
                () -> waitForExit(process),
                "mjt-target-watcher-" + safeThreadName(targetName)
        );
        watcherThread.setDaemon(true);
        watcherThread.start();

        System.out.println(GREEN + "[TARGET] Started: " + targetName + RESET);
        System.out.println(CYAN + "[TARGET] Command: " + startCommand + RESET);
        System.out.println(CYAN + "[TARGET] Workdir : " + workingDirectory + RESET);

        logService.write("[TARGET START] " + targetName + " | " + startCommand + " | " + workingDirectory + "\n");
    }

    public synchronized void sendLine(String line) {
        if (!isRunning()) {
            System.out.println(YELLOW + "[TARGET] No target process is running." + RESET);
            return;
        }

        try {
            processInput.write(line);
            processInput.newLine();
            processInput.flush();
            logService.write("[TARGET INPUT] " + line + "\n");
        } catch (IOException e) {
            System.out.println(RED + "[TARGET] Cannot send command: " + e.getMessage() + RESET);
        }
    }

    public synchronized void stopGracefully() {
        stopGracefully("stop");
    }

    public synchronized void stopGracefully(String stopCommand) {
        if (!isRunning()) {
            System.out.println(YELLOW + "[TARGET] No target process is running." + RESET);
            return;
        }

        String command = (stopCommand == null || stopCommand.isBlank()) ? "stop" : stopCommand.trim();
        sendLine(command);
        System.out.println(YELLOW + "[TARGET] Sent graceful stop command: " + command + RESET);
    }

    public synchronized void kill() {
        if (!isRunning()) {
            System.out.println(YELLOW + "[TARGET] No target process is running." + RESET);
            return;
        }

        process.destroy();

        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (process.isAlive()) {
            process.destroyForcibly();
        }

        System.out.println(RED + "[TARGET] Killed: " + targetName + RESET);
    }

    public synchronized void printStatus() {
        System.out.println(CYAN + "[TARGET STATUS]" + RESET);
        System.out.println("Running : " + isRunning());
        System.out.println("Name    : " + targetName);
        System.out.println("Command : " + (startCommand.isBlank() ? "none" : startCommand));
        System.out.println("Workdir : " + (workingDirectory == null ? "none" : workingDirectory));
    }

    private ProcessBuilder createProcessBuilder(String command) {
        String osName = System.getProperty("os.name", "").toLowerCase();

        if (osName.contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        }

        return new ProcessBuilder("bash", "-lc", command);
    }

    private void readOutput(Process localProcess) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(localProcess.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String line;

            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                logService.write("[TARGET OUTPUT] " + line + "\n");
            }

        } catch (IOException e) {
            try {
                logService.write("[TARGET OUTPUT ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private void waitForExit(Process localProcess) {
        int exitCode = -1;

        try {
            exitCode = localProcess.waitFor();
            System.out.println(YELLOW + "[TARGET] Exited with code: " + exitCode + RESET);
            logService.write("[TARGET EXIT] " + targetName + " | code=" + exitCode + "\n");

        } catch (Exception e) {
            try {
                logService.write("[TARGET WAIT ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        } finally {
            cleanupAfterExit();

            Runnable callback;
            synchronized (this) {
                callback = onExit;
            }

            if (callback != null) {
                try {
                    callback.run();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private synchronized void cleanupAfterExit() {
        try {
            if (processInput != null) {
                processInput.close();
            }
        } catch (IOException ignored) {
        }

        processInput = null;
        process = null;
    }

    private String safeThreadName(String value) {
        if (value == null || value.isBlank()) {
            return "target";
        }

        return value.toLowerCase()
                .replaceAll("[^a-z0-9_.-]", "-");
    }
}
