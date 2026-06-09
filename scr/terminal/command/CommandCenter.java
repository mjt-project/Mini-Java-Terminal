package terminal.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class CommandCenter {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final CommandContext context;

    public CommandCenter(CommandContext context) {
        this.context = context;
    }

    public void handle(String command) throws IOException {
        context.logService().write("\n========================================\n");
        context.logService().write("[INPUT] " + command + "\n");

        if (handleInternalCommand(command)) {
            return;
        }

        if (context.commandGuard().isBlocked(command)) {
            return;
        }

        context.shellRunner().run(
                command,
                context.runtimeConfig().getCurrentDir(),
                context.runtimeConfig().getCommandTimeoutSeconds()
        );
    }

    private boolean handleInternalCommand(String command) throws IOException {
        if (command.equalsIgnoreCase("help")) {
            printHelp();
            return true;
        }

        if (command.equalsIgnoreCase("public-ip")) {
            context.publicIpService().printPublicIpv4();
            return true;
        }

        if (command.equalsIgnoreCase("timeout")) {
            showTimeout();
            return true;
        }

        if (command.startsWith("timeout ")) {
            setTimeout(command);
            return true;
        }

        if (command.equalsIgnoreCase("pwd")) {
            System.out.println(context.runtimeConfig().getCurrentDir());
            context.logService().write("[PWD] " + context.runtimeConfig().getCurrentDir() + "\n");
            return true;
        }

        if (command.equalsIgnoreCase("clear") || command.equalsIgnoreCase("cls")) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            return true;
        }

        if (command.equalsIgnoreCase("cd")) {
            changeDirectory(System.getProperty("user.home"));
            return true;
        }

        if (command.startsWith("cd ")) {
            changeDirectory(command.substring(3).trim());
            return true;
        }

        if (command.equalsIgnoreCase("exit")) {
            System.out.println(YELLOW + "Lệnh exit đã bị chặn để tránh server offline." + RESET);
            System.out.println(YELLOW + "Muốn tắt thật thì gõ: shutdown-terminal" + RESET);
            context.logService().write("[BLOCKED EXIT]\n");
            return true;
        }

        if (command.equalsIgnoreCase("shutdown-terminal")) {
            System.out.println(RED + "Đang tắt Mini Java Terminal..." + RESET);
            context.logService().write("[SHUTDOWN]\n");
            System.exit(0);
            return true;
        }

        // Cloudflare DDNS
        if (command.equalsIgnoreCase("cloudflare-show")) {
            context.cloudflareDnsService().showConfig();
            return true;
        }

        if (command.startsWith("cloudflare-set ")) {
            handleCloudflareSet(command);
            return true;
        }

        if (command.equalsIgnoreCase("cloudflare-ddns-once")) {
            context.cloudflareDnsService().updateOnce();
            return true;
        }

        if (command.equalsIgnoreCase("cloudflare-ddns-start")) {
            context.cloudflareDnsService().startLoop();
            return true;
        }

        if (command.equalsIgnoreCase("cloudflare-ddns-stop")) {
            context.cloudflareDnsService().stopLoop();
            return true;
        }

        if (command.equalsIgnoreCase("cloudflare-ddns-status")) {
            context.cloudflareDnsService().printStatus();
            return true;
        }

        // SSH + SFTP server
        if (command.equalsIgnoreCase("ssh-show")) {
            context.sshServerService().showConfig();
            return true;
        }

        if (command.startsWith("ssh-set ")) {
            handleSshSet(command);
            return true;
        }

        if (command.equalsIgnoreCase("ssh-start")) {
            context.sshServerService().start();
            return true;
        }

        if (command.equalsIgnoreCase("ssh-stop")) {
            context.sshServerService().stop();
            return true;
        }

        if (command.equalsIgnoreCase("ssh-status")) {
            context.sshServerService().status();
            return true;
        }

        // Backward-compatible aliases for old SFTP commands
        if (command.equalsIgnoreCase("sftp-show")) {
            context.sshServerService().showConfig();
            return true;
        }

        if (command.startsWith("sftp-set ")) {
            handleSftpAliasSet(command);
            return true;
        }

        if (command.equalsIgnoreCase("sftp-start")) {
            context.sshServerService().start();
            return true;
        }

        if (command.equalsIgnoreCase("sftp-stop")) {
            context.sshServerService().stop();
            return true;
        }

        if (command.equalsIgnoreCase("sftp-status")) {
            context.sshServerService().status();
            return true;
        }

        return false;
    }

    private void changeDirectory(String target) throws IOException {
        Path currentDir = context.runtimeConfig().getCurrentDir();
        Path newDir;

        if (target.equals("~")) {
            newDir = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        } else {
            newDir = currentDir.resolve(target).normalize();
        }

        if (Files.exists(newDir) && Files.isDirectory(newDir)) {
            context.runtimeConfig().setCurrentDir(newDir);
            System.out.println(CYAN + "Current dir: " + context.runtimeConfig().getCurrentDir() + RESET);
            context.logService().write("[CD] " + context.runtimeConfig().getCurrentDir() + "\n");
        } else {
            System.out.println(RED + "Không tìm thấy thư mục: " + newDir + RESET);
            context.logService().write("[CD ERROR] Directory not found: " + newDir + "\n");
        }
    }

    private void setTimeout(String command) {
        String value = command.substring("timeout ".length()).trim();

        try {
            int seconds = Integer.parseInt(value);

            context.runtimeConfig().setCommandTimeoutSeconds(seconds);

            if (seconds == 0) {
                System.out.println(GREEN + "Command timeout: không giới hạn." + RESET);
            } else {
                System.out.println(GREEN + "Command timeout: " + seconds + " giây." + RESET);
            }

        } catch (NumberFormatException e) {
            System.out.println(RED + "Dùng: timeout <seconds>" + RESET);
            System.out.println("Ví dụ: timeout 0 hoặc timeout 60");
        } catch (IllegalArgumentException e) {
            System.out.println(RED + e.getMessage() + RESET);
        }
    }

    private void showTimeout() {
        int seconds = context.runtimeConfig().getCommandTimeoutSeconds();

        if (seconds == 0) {
            System.out.println(GREEN + "Command timeout hiện tại: không giới hạn." + RESET);
        } else {
            System.out.println(GREEN + "Command timeout hiện tại: " + seconds + " giây." + RESET);
        }
    }

    private void handleCloudflareSet(String command) {
        String raw = command.substring("cloudflare-set ".length()).trim();

        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Dùng: cloudflare-set <key> <value>" + RESET);
            System.out.println("Ví dụ: cloudflare-set name document.io.vn");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        try {
            context.cloudflareDnsService().setConfig(key, value);
        } catch (Exception e) {
            System.out.println(RED + "[Cloudflare] Lỗi khi lưu config: " + e.getMessage() + RESET);
        }
    }

    private void handleSshSet(String command) {
        String raw = command.substring("ssh-set ".length()).trim();
        handleSshSetRaw(raw);
    }

    private void handleSftpAliasSet(String command) {
        String raw = command.substring("sftp-set ".length()).trim();
        handleSshSetRaw(raw);
    }

    private void handleSshSetRaw(String raw) {
        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Dùng: ssh-set <key> <value>" + RESET);
            System.out.println("Ví dụ: ssh-set port 40078");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        try {
            context.sshServerService().setConfig(key, value);
        } catch (Exception e) {
            System.out.println(RED + "[SSH] Lỗi khi lưu config: " + e.getMessage() + RESET);
        }
    }

    private void printHelp() throws IOException {
        System.out.println(YELLOW + "Mini Java Terminal Commands:" + RESET);
        System.out.println("help                         - Xem hướng dẫn");
        System.out.println("pwd                          - Xem thư mục hiện tại");
        System.out.println("cd <folder>                  - Chuyển thư mục");
        System.out.println("clear                        - Xóa màn hình console");
        System.out.println("public-ip                    - Check public IPv4 của panel host");
        System.out.println("timeout                      - Xem timeout hiện tại");
        System.out.println("timeout <seconds>            - Đặt timeout, 0 = không giới hạn");
        System.out.println();

        System.out.println(YELLOW + "Cloudflare DDNS:" + RESET);
        System.out.println("cloudflare-set token <token> - Lưu Cloudflare API token");
        System.out.println("cloudflare-set zone <id>     - Lưu Cloudflare Zone ID");
        System.out.println("cloudflare-set name <domain> - Lưu DNS record name");
        System.out.println("cloudflare-set proxied false - Đặt DNS only");
        System.out.println("cloudflare-set ttl 120       - Đặt TTL");
        System.out.println("cloudflare-set interval 300  - Đặt thời gian check IP");
        System.out.println("cloudflare-show              - Xem config Cloudflare");
        System.out.println("cloudflare-ddns-once         - Cập nhật DNS một lần");
        System.out.println("cloudflare-ddns-start        - Bật auto DDNS");
        System.out.println("cloudflare-ddns-stop         - Dừng auto DDNS");
        System.out.println("cloudflare-ddns-status       - Xem trạng thái DDNS");
        System.out.println();

        System.out.println(YELLOW + "SSH / SFTP Server:" + RESET);
        System.out.println("ssh-set host 0.0.0.0         - Bind host");
        System.out.println("ssh-set port <port>          - Port SSH/SFTP public");
        System.out.println("ssh-set user <username>      - Username SSH/SFTP");
        System.out.println("ssh-set pass <password>      - Password SSH/SFTP");
        System.out.println("ssh-set root <folder>        - Thư mục gốc SSH/SFTP");
        System.out.println("ssh-show                     - Xem config SSH/SFTP");
        System.out.println("ssh-start                    - Bật SSH/SFTP server");
        System.out.println("ssh-stop                     - Tắt SSH/SFTP server");
        System.out.println("ssh-status                   - Xem trạng thái SSH/SFTP");
        System.out.println();

        System.out.println(YELLOW + "Compatibility aliases:" + RESET);
        System.out.println("sftp-set / sftp-show / sftp-start / sftp-stop / sftp-status");
        System.out.println();

        System.out.println("shutdown-terminal            - Tắt app");
        System.out.println();

        System.out.println(RED + "Không hỗ trợ tốt:" + RESET);
        System.out.println("su, sudo, nano, vim, vi, top, htop");

        context.logService().write("[HELP]\n");
    }
}
