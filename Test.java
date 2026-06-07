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

    private static int COMMAND_TIMEOUT_SECONDS = 0; // 0 = không giới hạn. Mặc định là 0

    public static void main(String[] args) {
        try {
            prepareLogs();

            System.out.println(GREEN + "Panel Terminal started." + RESET);
            System.out.println(CYAN + "Java đang làm trung gian command cho panel." + RESET);
            System.out.println(YELLOW + "Gõ help để xem lệnh hỗ trợ." + RESET);
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
            System.out.println(YELLOW + "Lệnh exit đã bị chặn để tránh server offline." + RESET);
            System.out.println(YELLOW + "Muốn tắt thật thì gõ: shutdown-terminal" + RESET);
            writeLog("[BLOCKED EXIT]\n");
            return true;
        }

        if (command.equalsIgnoreCase("shutdown-terminal")) {
            System.out.println(RED + "Đang tắt Panel Terminal..." + RESET);
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
                System.out.println(RED + "Không tìm thấy thư mục: " + newDir + RESET);
                writeLog("[CD ERROR] Directory not found: " + newDir + "\n");
            }

            return true;
        }

        return false;
    }

    private static boolean isBlockedCommand(String command) throws IOException {
        String lower = command.toLowerCase();

        if (lower.equals("su") || lower.startsWith("su ")) {
            System.out.println(RED + "Không chạy su trong panel console này được." + RESET);
            System.out.println(YELLOW + "Lý do: su cần terminal tương tác thật và password root." + RESET);
            writeLog("[BLOCKED] su command\n");
            return true;
        }

        if (lower.equals("sudo") || lower.startsWith("sudo ")) {
            System.out.println(RED + "Không dùng sudo được nếu host chưa cấp quyền sudo." + RESET);
            System.out.println(YELLOW + "Muốn cài package bằng apt thì cần quyền root từ nhà cung cấp host." + RESET);
            writeLog("[BLOCKED] sudo command\n");
            return true;
        }

        if (lower.startsWith("apt install") || lower.startsWith("apt-get install")) {
            System.out.println(RED + "apt install cần quyền root nên panel user hiện tại không chạy được." + RESET);
            System.out.println(YELLOW + "Có thể dùng: apt search ten_goi hoặc tải binary portable vào thư mục server." + RESET);
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
            System.out.println(RED + "Lệnh này cần terminal tương tác thật nên dễ treo trong panel." + RESET);
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
                
                    System.out.println(RED + "Command bị dừng vì chạy quá "
                            + COMMAND_TIMEOUT_SECONDS + " giây." + RESET);
                
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
            System.out.println(RED + "Lỗi khi chạy command: " + e.getMessage() + RESET);

            try {
                writeLog("[ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }

        } finally {
            executor.shutdownNow();
        }
    }

    private static void printHelp() throws IOException {
        System.out.println(YELLOW + "Các lệnh hỗ trợ:" + RESET);
        System.out.println("help                  - Xem hướng dẫn");
        System.out.println("pwd                   - Xem thư mục hiện tại");
        System.out.println("cd <folder>           - Chuyển thư mục");
        System.out.println("ls                    - Xem file");
        System.out.println("java -version         - Xem Java version");
        System.out.println("apt search <package>  - Tìm package, không cần root");
        System.out.println("shutdown-terminal     - Tắt app");
        System.out.println();
        System.out.println(RED + "Không hỗ trợ tốt:" + RESET);
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