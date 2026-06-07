import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

public class Test {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static Path logFile;
    private static Path currentDir = Paths.get("").toAbsolutePath().normalize();

    private static int COMMAND_TIMEOUT_SECONDS = 0; // 0 = unlimited. Default is 0

    public static void main(String[] args) {
        try {
            prepareLogs();

            System.out.println(GREEN + "Panel Terminal started." + RESET);
            System.out.println(YELLOW + "Type help to see supported commands." + RESET);
            System.out.println("Current dir: " + currentDir);
            System.out.println("Log file: " + logFile.toAbsolutePath());
            System.out.println();

            BufferedReader console = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );

            String command;

            while ((command = console.readLine()) != null) {
                command = command.trim();

                if (command.isEmpty()) {
                    continue;
                }

                writeLog("\n========================================\n");
                writeLog("[TIME] " + LocalDateTime.now() + "\n");
                writeLog("[INPUT] " + command + "\n");

                if (handleInternalCommand(command)) {
                    continue;
                }

                if (isBlockedCommand(command)) {
                    continue;
                }

                runCommand(command);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean handleInternalCommand(String command) throws IOException {
        if (command.equalsIgnoreCase("help")) {
            printHelp();
            return true;
        }

        if (command.equalsIgnoreCase("exit")) {
            System.out.println(YELLOW + "The exit command is blocked to prevent server offline." + RESET);
            System.out.println(YELLOW + "To actually shut down, type: shutdown-terminal" + RESET);
            writeLog("[BLOCKED EXIT]\n");
            return true;
        }

        if (command.equalsIgnoreCase("shutdown-terminal")) {
            System.out.println(RED + "Shutting down Panel Terminal..." + RESET);
            writeLog("[SHUTDOWN]\n");
            System.exit(0);
            return true;
        }

        if (command.equalsIgnoreCase("pwd")) {
            System.out.println(currentDir);
            writeLog("[PWD] " + currentDir + "\n");
            return true;
        }

        if (command.equalsIgnoreCase("clear") || command.equalsIgnoreCase("cls")) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            return true;
        }

        if (command.equals("cd")) {
            currentDir = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
            System.out.println(CYAN + "Current dir: " + currentDir + RESET);
            writeLog("[CD] " + currentDir + "\n");
            return true;
        }

        if (command.startsWith("cd ")) {
            String target = command.substring(3).trim();

            Path newDir;

            if (target.equals("~")) {
                newDir = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
            } else {
                newDir = currentDir.resolve(target).normalize();
            }

            if (Files.exists(newDir) && Files.isDirectory(newDir)) {
                currentDir = newDir;
                System.out.println(CYAN + "Current dir: " + currentDir + RESET);
                writeLog("[CD] " + currentDir + "\n");
            } else {
                System.out.println(RED + "Directory not found: " + newDir + RESET);
                writeLog("[CD ERROR] Directory not found: " + newDir + "\n");
            }

            return true;
        }

        return false;
    }

    private static boolean isBlockedCommand(String command) throws IOException {
        String lower = command.toLowerCase();

        if (lower.equals("su") || lower.startsWith("su ")) {
            System.out.println(RED + "Cannot run su in this console panel." + RESET);
            System.out.println(YELLOW + "Reason: su requires a real interactive terminal and root password." + RESET);
            writeLog("[BLOCKED] su command\n");
            return true;
        }

        if (lower.equals("sudo") || lower.startsWith("sudo ")) {
            System.out.println(RED + "Cannot use sudo if the host has not granted sudo permissions." + RESET);
            System.out.println(YELLOW + "To install packages with apt, you need root permissions from the hosting provider." + RESET);
            writeLog("[BLOCKED] sudo command\n");
            return true;
        }

        if (lower.startsWith("apt install") || lower.startsWith("apt-get install")) {
            System.out.println(RED + "apt install requires root permissions, so the current panel user cannot run it." + RESET);
            System.out.println(YELLOW + "You can use: apt search package_name or download portable binary to server folder." + RESET);
            writeLog("[BLOCKED] apt install command\n");
            return true;
        }

        if (
                lower.equals("nano") || lower.startsWith("nano ") ||
                lower.equals("vim") || lower.startsWith("vim ") ||
                lower.equals("vi") || lower.startsWith("vi ") ||
                lower.equals("top") ||
                lower.equals("htop")
        ) {
            System.out.println(RED + "This command requires a real interactive terminal, so it may hang in this panel." + RESET);
            writeLog("[BLOCKED] interactive command\n");
            return true;
        }

        return false;
    }

    private static void runCommand(String command) {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            System.out.println(CYAN + currentDir + " $ " + command + RESET);
            writeLog("[COMMAND] " + command + "\n");
            writeLog("[DIR] " + currentDir + "\n");

            ProcessBuilder processBuilder;

            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
            } else {
                processBuilder = new ProcessBuilder("bash", "-lc", command);
            }

            processBuilder.directory(currentDir.toFile());
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();

            Future<?> outputFuture = executor.submit(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                )) {
                    String line;

                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                        writeLog(line + "\n");
                    }

                } catch (IOException e) {
                    try {
                        writeLog("[OUTPUT ERROR] " + e.getMessage() + "\n");
                    } catch (IOException ignored) {
                    }
                }
            });

            if (COMMAND_TIMEOUT_SECONDS <= 0) {
                process.waitFor();
            } else {
                boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
                if (!finished) {
                    process.destroyForcibly();
                
                    System.out.println(RED + "Command stopped because it ran over "
                            + COMMAND_TIMEOUT_SECONDS + " seconds." + RESET);
                
                    writeLog("[TIMEOUT] Command killed after "
                            + COMMAND_TIMEOUT_SECONDS + " seconds\n");
                
                    return;
                }
            }

            outputFuture.get(3, TimeUnit.SECONDS);

            int exitCode = process.exitValue();

            if (exitCode == 0) {
                System.out.println(GREEN + "Exit code: " + exitCode + RESET);
            } else {
                System.out.println(RED + "Exit code: " + exitCode + RESET);
            }

            writeLog("[EXIT CODE] " + exitCode + "\n");

        } catch (TimeoutException e) {
            System.out.println(RED + "Output reader timeout." + RESET);

            try {
                writeLog("[OUTPUT TIMEOUT]\n");
            } catch (IOException ignored) {
            }

        } catch (Exception e) {
            System.out.println(RED + "Error running command: " + e.getMessage() + RESET);

            try {
                writeLog("[ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }

        } finally {
            executor.shutdownNow();
        }
    }

    private static void printHelp() throws IOException {
        System.out.println(YELLOW + "Supported commands:" + RESET);
        System.out.println("help                  - View guide");
        System.out.println("pwd                   - View current directory");
        System.out.println("cd <folder>           - Change directory");
        System.out.println("ls                    - View files");
        System.out.println("java -version         - View Java version");
        System.out.println("apt search <package>  - Search package, no root required");
        System.out.println("shutdown-terminal     - Shutdown app");
        System.out.println();
        System.out.println(RED + "Not well supported:" + RESET);
        System.out.println("su, sudo, apt install, nano, vim, top, htop");

        writeLog("[HELP]\n");
    }

    private static void prepareLogs() throws IOException {
        Path logsDir = Paths.get("logs");

        if (!Files.exists(logsDir)) {
            Files.createDirectories(logsDir);
        }

        String time = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        );

        logFile = logsDir.resolve("terminal-" + time + ".log");

        writeLog("[START] " + LocalDateTime.now() + "\n");
    }

    private static synchronized void writeLog(String text) throws IOException {
        Files.writeString(
                logFile,
                text,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }
}