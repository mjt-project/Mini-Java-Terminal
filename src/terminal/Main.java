package terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class Main {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static Path currentDir = Paths.get("").toAbsolutePath().normalize();

    // 0 = không giới hạn thời gian chạy command
    private static int commandTimeoutSeconds = 0;

    private static LogService logService;
    private static ShellRunner shellRunner;
    private static StateStore stateStore;
    private static PublicIpService publicIpService;
    private static CloudflareDnsService cloudflarensService;

    public static void main(String[] args) {
        try {
            initServices();
            printStartupMessage();

            BufferedReader console = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );

            String command;

            while ((command = console.readLine()) != null) {
                command = command.trim();

                if (command.isEmpty()) {
                    continue;
                }

                logService.write("\n========================================\n");
                logService.write("[INPUT] " + command + "\n");

                if (handleInternalCommand(command)) {
                    continue;
                }

                if (isBlockedCommand(command)) {
                    continue;
                }

                shellRunner.run(command, currentDir, commandTimeoutSeconds);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void initServices() throws IOException {
        logService = new LogService(Paths.get("logs"));
        stateStore = new StateStore(Paths.get("terminal-state.properties"));
        publicIpService = new PublicIpService(logService);
        cloudflarensService = new CloudflareDnsService(stateStore, publicIpService, logService);
        shellRunner = new ShellRunner(logService);
    }

    private static void printStartupMessage() throws IOException {
        System.out.println(GREEN + "Terminal Console Monitor started." + RESET);
        System.out.println(CYAN + "Java đang làm trung gian command cho panel." + RESET);
        System.out.println(YELLOW + "Gõ help để xem lệnh hỗ trợ." + RESET);
        System.out.println("Current dir: " + currentDir);
        System.out.println("Log file: " + logService.getLogFile().toAbsolutePath());
        System.out.println();

        logService.write("[START] Terminal Console Monitor\n");
        logService.write("[CURRENT DIR] " + currentDir + "\n");
    }

    private static boolean handleInternalCommand(String command) throws IOException {
        if (command.equalsIgnoreCase("help")) {
            printHelp();
            return true;
        }

        if (command.equalsIgnoreCase("public-ip")) {
            checkPublicIp();
            return true;
        }

        if (command.startsWith("timeout ")) {
            setTimeout(command);
            return true;
        }

        if (command.equalsIgnoreCase("timeout")) {
            showTimeout();
            return true;
        }

        if (command.equalsIgnoreCase("cloudflared-show")) {
            cloudflarensService.showConfig();
            return true;
        }

        if (command.startsWith("cloudflared-set ")) {
            handleCloudflareSet(command);
            return true;
        }

        if (command.equalsIgnoreCase("cloudflared-ddns-once")) {
            cloudflarensService.updateOnce();
            return true;
        }

        if (command.equalsIgnoreCase("cloudflared-ddns-start")) {
            cloudflarensService.startLoop();
            return true;
        }

        if (command.equalsIgnoreCase("cloudflared-ddns-stop")) {
            cloudflarensService.stopLoop();
            return true;
        }

        if (command.equalsIgnoreCase("cloudflared-ddns-status")) {
            cloudflarensService.printStatus();
            return true;
        }

        if (command.equalsIgnoreCase("exit")) {
            System.out.println(YELLOW + "Lệnh exit đã bị chặn để tránh server offline." + RESET);
            System.out.println(YELLOW + "Muốn tắt thật thì gõ: shutdown-terminal" + RESET);
            logService.write("[BLOCKED EXIT]\n");
            return true;
        }

        if (command.equalsIgnoreCase("shutdown-terminal")) {
            System.out.println(RED + "Đang tắt Terminal Console Monitor..." + RESET);
            logService.write("[SHUTDOWN]\n");
            System.exit(0);
            return true;
        }

        if (command.equalsIgnoreCase("pwd")) {
            System.out.println(currentDir);
            logService.write("[PWD] " + currentDir + "\n");
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
            logService.write("[CD] " + currentDir + "\n");
            return true;
        }

        if (command.startsWith("cd ")) {
            changeDirectory(command.substring(3).trim());
            return true;
        }

        return false;
    }

    private static void changeDirectory(String target) throws IOException {
        Path newDir;

        if (target.equals("~")) {
            newDir = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        } else {
            newDir = currentDir.resolve(target).normalize();
        }

        if (Files.exists(newDir) && Files.isDirectory(newDir)) {
            currentDir = newDir;
            System.out.println(CYAN + "Current dir: " + currentDir + RESET);
            logService.write("[CD] " + currentDir + "\n");
        } else {
            System.out.println(RED + "Không tìm thấy thư mục: " + newDir + RESET);
            logService.write("[CD ERROR] Directory not found: " + newDir + "\n");
        }
    }

    private static boolean isBlockedCommand(String command) throws IOException {
        String lower = command.toLowerCase();

        if (lower.equals("su") || lower.startsWith("su ")) {
            System.out.println(RED + "Không chạy su trong panel console này được." + RESET);
            System.out.println(YELLOW + "Lý do: su cần terminal tương tác thật và password root." + RESET);
            logService.write("[BLOCKED] su command\n");
            return true;
        }

        if (lower.equals("sudo") || lower.startsWith("sudo ")) {
            System.out.println(RED + "Không dùng sudo nếu host chưa cấp quyền sudo." + RESET);
            logService.write("[BLOCKED] sudo command\n");
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
            logService.write("[BLOCKED] interactive command\n");
            return true;
        }

        return false;
    }

    private static void checkPublicIp() {
        try {
            String ip = publicIpService.getPublicIpv4();

            System.out.println(CYAN + "[PUBLIC IP CHECK]" + RESET);
            System.out.println(GREEN + "Public IPv4: " + ip + RESET);

            logService.write("[PUBLIC IP] " + ip + "\n");

        } catch (Exception e) {
            System.out.println(RED + "Không check được public IP: " + e.getMessage() + RESET);

            try {
                logService.write("[PUBLIC IP ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private static void handleCloudflareSet(String command) {
        String raw = command.substring("cloudflared-set ".length()).trim();

        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Dùng: cloudflared-set <key> <value>" + RESET);
            System.out.println("Ví dụ: cloudflared-set name play.example.com");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        try {
            cloudflarensService.setConfig(key, value);
        } catch (Exception e) {
            System.out.println(RED + "[CF] Lỗi khi lưu config: " + e.getMessage() + RESET);
        }
    }

    private static void setTimeout(String command) {
        String value = command.substring("timeout ".length()).trim();

        try {
            int seconds = Integer.parseInt(value);

            if (seconds < 0) {
                System.out.println(RED + "Timeout không được nhỏ hơn 0." + RESET);
                return;
            }

            commandTimeoutSeconds = seconds;

            if (commandTimeoutSeconds == 0) {
                System.out.println(GREEN + "Command timeout: không giới hạn." + RESET);
            } else {
                System.out.println(GREEN + "Command timeout: " + commandTimeoutSeconds + " giây." + RESET);
            }

        } catch (NumberFormatException e) {
            System.out.println(RED + "Dùng: timeout <seconds>" + RESET);
            System.out.println("Ví dụ: timeout 0 hoặc timeout 60");
        }
    }

    private static void showTimeout() {
        if (commandTimeoutSeconds == 0) {
            System.out.println(GREEN + "Command timeout hiện tại: không giới hạn." + RESET);
        } else {
            System.out.println(GREEN + "Command timeout hiện tại: " + commandTimeoutSeconds + " giây." + RESET);
        }
    }

    private static void printHelp() throws IOException {
        System.out.println(YELLOW + "Các lệnh hỗ trợ:" + RESET);
        System.out.println("help                  - Xem hướng dẫn");
        System.out.println("pwd                   - Xem thư mục hiện tại");
        System.out.println("cd <folder>           - Chuyển thư mục");
        System.out.println("clear                 - Xóa màn hình console");
        System.out.println("public-ip             - Check public IPv4 của panel host");
        System.out.println("timeout               - Xem timeout hiện tại");
        System.out.println("timeout <seconds>     - Đặt timeout, 0 = không giới hạn");
        System.out.println();
        System.out.println(YELLOW + "Cloudflare DNS:" + RESET);
        System.out.println("cloudflared-set token <token>  - Lưu Cloudflare API token");
        System.out.println("cloudflared-set zone <id>      - Lưu Cloudflare Zone ID");
        System.out.println("cloudflared-set name <domain>  - Lưu record name");
        System.out.println("cloudflared-set proxied false  - Đặt DNS only");
        System.out.println("cloudflared-set ttl 120        - Đặt TTL");
        System.out.println("cloudflared-show               - Xem cấu hình Cloudflare đã lưu");
        System.out.println("cloudflared-ddns-once          - Cập nhật IPv4 lên Cloudflare một lần");
        System.out.println("cloudflared-ddns-start         - Tự động cập nhật IPv4 định kỳ");
        System.out.println("cloudflared-ddns-stop          - Dừng auto DDNS");
        System.out.println("cloudflared-ddns-status        - Xem trạng thái DDNS");
        System.out.println();
        System.out.println("shutdown-terminal     - Tắt app");
        System.out.println();
        System.out.println(RED + "Không hỗ trợ tốt:" + RESET);
        System.out.println("su, sudo, nano, vim, vi, top, htop");

        logService.write("[HELP]\n");
    }
}
