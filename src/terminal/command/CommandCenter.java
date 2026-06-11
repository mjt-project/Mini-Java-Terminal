package terminal.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

                // Gateway
        if (command.equalsIgnoreCase("gateway-help")) {
            printGatewayHelp();
            return true;
        }

        if (command.equalsIgnoreCase("gateway-show")) {
            showGatewayConfig();
            return true;
        }

        if (command.startsWith("gateway-set ")) {
            handleGatewaySet(command);
            return true;
        }

        if (command.startsWith("gateway-default ")) {
            handleGatewayDefault(command);
            return true;
        }

        if (command.startsWith("gateway-route-add ")) {
            handleGatewayRouteAdd(command);
            return true;
        }

        if (command.startsWith("gateway-route-remove ")) {
            handleGatewayRouteRemove(command);
            return true;
        }

        if (command.startsWith("gateway-route-enable ")) {
            handleGatewayRouteToggle(command, true);
            return true;
        }

        if (command.startsWith("gateway-route-disable ")) {
            handleGatewayRouteToggle(command, false);
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

        private void handleGatewaySet(String command) {
        String raw = command.substring("gateway-set ".length()).trim();

        int firstSpace = raw.indexOf(' ');

        if (firstSpace <= 0) {
            System.out.println(RED + "Dùng: gateway-set <key> <value>" + RESET);
            System.out.println("Ví dụ: gateway-set gateway.http.enabled true");
            return;
        }

        String key = raw.substring(0, firstSpace).trim();
        String value = raw.substring(firstSpace + 1).trim();

        if (!key.startsWith("gateway.")) {
            System.out.println(RED + "Key gateway phải bắt đầu bằng gateway." + RESET);
            return;
        }

        try {
            context.stateStore().set(key, value);
            System.out.println(GREEN + "[Gateway] Đã lưu " + key + " = " + value + RESET);
            context.logService().write("[GATEWAY SET] " + key + " = " + value + "\n");
        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Lỗi khi lưu config: " + e.getMessage() + RESET);
        }
    }

    private void handleGatewayDefault(String command) {
        String routeName = command.substring("gateway-default ".length()).trim();

        if (routeName.isBlank()) {
            System.out.println(RED + "Dùng: gateway-default <route|close>" + RESET);
            return;
        }

        if (!routeName.equalsIgnoreCase("close") && !isValidGatewayRouteName(routeName)) {
            System.out.println(RED + "Tên route không hợp lệ. Chỉ dùng chữ, số, -, _" + RESET);
            return;
        }

        try {
            context.stateStore().set("gateway.tcp.default", routeName);
            System.out.println(GREEN + "[Gateway] Default TCP route = " + routeName + RESET);
            context.logService().write("[GATEWAY DEFAULT] " + routeName + "\n");
        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Lỗi: " + e.getMessage() + RESET);
        }
    }

    private void handleGatewayRouteAdd(String command) {
        String raw = command.substring("gateway-route-add ".length()).trim();
        String[] parts = raw.split("\\s+");

        if (parts.length < 3) {
            System.out.println(RED + "Dùng: gateway-route-add <name> <host> <port>" + RESET);
            System.out.println("Ví dụ: gateway-route-add mc 127.0.0.1 25565");
            return;
        }

        String name = parts[0].trim();
        String host = parts[1].trim();
        String portText = parts[2].trim();

        if (!isValidGatewayRouteName(name)) {
            System.out.println(RED + "Tên route không hợp lệ. Chỉ dùng chữ, số, -, _" + RESET);
            return;
        }

        int port;

        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            System.out.println(RED + "Port phải là số." + RESET);
            return;
        }

        if (port <= 0 || port > 65535) {
            System.out.println(RED + "Port không hợp lệ." + RESET);
            return;
        }

        try {
            List<String> routes = getGatewayRouteNames();

            if (!routes.contains(name)) {
                routes.add(name);
            }

            saveGatewayRouteNames(routes);

            context.stateStore().set("gateway.tcp." + name + ".enabled", "true");
            context.stateStore().set("gateway.tcp." + name + ".host", host);
            context.stateStore().set("gateway.tcp." + name + ".port", String.valueOf(port));
            context.stateStore().set("gateway.tcp.enabled", "true");

            System.out.println(GREEN + "[Gateway] Đã thêm route: " + name + " -> " + host + ":" + port + RESET);
            context.logService().write("[GATEWAY ROUTE ADD] " + name + " -> " + host + ":" + port + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Lỗi khi thêm route: " + e.getMessage() + RESET);
        }
    }

    private void handleGatewayRouteRemove(String command) {
        String name = command.substring("gateway-route-remove ".length()).trim();

        if (!isValidGatewayRouteName(name)) {
            System.out.println(RED + "Tên route không hợp lệ." + RESET);
            return;
        }

        try {
            List<String> routes = getGatewayRouteNames();
            routes.remove(name);
            saveGatewayRouteNames(routes);

            context.stateStore().remove("gateway.tcp." + name + ".enabled");
            context.stateStore().remove("gateway.tcp." + name + ".host");
            context.stateStore().remove("gateway.tcp." + name + ".port");

            String currentDefault = context.stateStore().get("gateway.tcp.default", "close");

            if (currentDefault.equalsIgnoreCase(name)) {
                context.stateStore().set("gateway.tcp.default", "close");
            }

            System.out.println(GREEN + "[Gateway] Đã xóa route: " + name + RESET);
            context.logService().write("[GATEWAY ROUTE REMOVE] " + name + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Lỗi khi xóa route: " + e.getMessage() + RESET);
        }
    }

    private void handleGatewayRouteToggle(String command, boolean enabled) {
        String prefix = enabled ? "gateway-route-enable " : "gateway-route-disable ";
        String name = command.substring(prefix.length()).trim();

        if (!isValidGatewayRouteName(name)) {
            System.out.println(RED + "Tên route không hợp lệ." + RESET);
            return;
        }

        try {
            context.stateStore().set("gateway.tcp." + name + ".enabled", String.valueOf(enabled));

            if (enabled) {
                System.out.println(GREEN + "[Gateway] Đã bật route: " + name + RESET);
            } else {
                System.out.println(YELLOW + "[Gateway] Đã tắt route: " + name + RESET);
            }

            context.logService().write("[GATEWAY ROUTE TOGGLE] " + name + " enabled=" + enabled + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[Gateway] Lỗi: " + e.getMessage() + RESET);
        }
    }

    private void showGatewayConfig() throws IOException {
        context.stateStore().load();

        System.out.println(CYAN + "[GATEWAY CONFIG]" + RESET);
        System.out.println("gateway.http.enabled = " + context.stateStore().get("gateway.http.enabled", "true"));
        System.out.println("gateway.http.root    = " + context.stateStore().get("gateway.http.root", "www"));
        System.out.println("gateway.http.index   = " + context.stateStore().get("gateway.http.index", "index.html"));
        System.out.println("gateway.http.spa     = " + context.stateStore().get("gateway.http.spa", "false"));
        System.out.println();

        System.out.println(YELLOW + "SSH/SFTP:" + RESET);
        System.out.println("gateway.ssh.enabled  = " + context.stateStore().get("gateway.ssh.enabled", "true"));
        System.out.println("gateway.ssh.host     = " + context.stateStore().get("gateway.ssh.host", "127.0.0.1"));
        System.out.println("gateway.ssh.port     = " + context.stateStore().get("gateway.ssh.port", "2022"));
        System.out.println();

        System.out.println(YELLOW + "TCP routes:" + RESET);
        System.out.println("gateway.tcp.enabled  = " + context.stateStore().get("gateway.tcp.enabled", "true"));
        System.out.println("gateway.tcp.default  = " + context.stateStore().get("gateway.tcp.default", "close"));
        System.out.println("gateway.tcp.routes   = " + context.stateStore().get("gateway.tcp.routes", ""));

        List<String> routes = getGatewayRouteNames();

        if (routes.isEmpty()) {
            System.out.println("  Không có route TCP nào.");
        } else {
            for (String name : routes) {
                System.out.println();
                System.out.println("  [" + name + "]");
                System.out.println("  enabled = " + context.stateStore().get("gateway.tcp." + name + ".enabled", "false"));
                System.out.println("  host    = " + context.stateStore().get("gateway.tcp." + name + ".host", ""));
                System.out.println("  port    = " + context.stateStore().get("gateway.tcp." + name + ".port", ""));
            }
        }

        context.logService().write("[GATEWAY SHOW]\n");
    }

    private void printGatewayHelp() {
        System.out.println(YELLOW + "Gateway Commands:" + RESET);
        System.out.println("gateway-help                                  - Xem hướng dẫn Gateway");
        System.out.println("gateway-show                                  - Xem cấu hình Gateway");
        System.out.println("gateway-set <key> <value>                    - Set config thủ công");
        System.out.println("gateway-default <route|close>                - Chọn TCP route mặc định");
        System.out.println();

        System.out.println(YELLOW + "TCP route management:" + RESET);
        System.out.println("gateway-route-add <name> <host> <port>       - Thêm/sửa route TCP");
        System.out.println("gateway-route-remove <name>                  - Xóa route TCP");
        System.out.println("gateway-route-enable <name>                  - Bật route TCP");
        System.out.println("gateway-route-disable <name>                 - Tắt route TCP");
        System.out.println();

        System.out.println(YELLOW + "Ví dụ:" + RESET);
        System.out.println("gateway-route-add mc 127.0.0.1 25565");
        System.out.println("gateway-default mc");
        System.out.println("gateway-route-add velocity 127.0.0.1 25577");
        System.out.println("gateway-default velocity");
        System.out.println("gateway-default close");
        System.out.println();

        System.out.println(YELLOW + "Config keys thường dùng:" + RESET);
        System.out.println("gateway.http.enabled true");
        System.out.println("gateway.ssh.enabled true");
        System.out.println("gateway.ssh.host 127.0.0.1");
        System.out.println("gateway.ssh.port 2022");
        System.out.println("gateway.tcp.enabled true");
    }

    private List<String> getGatewayRouteNames() {
        String raw = context.stateStore().get("gateway.tcp.routes", "").trim();

        if (raw.isBlank()) {
            return new ArrayList<>();
        }

        List<String> result = new ArrayList<>();

        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .filter(this::isValidGatewayRouteName)
                .forEach(item -> {
                    if (!result.contains(item)) {
                        result.add(item);
                    }
                });

        return result;
    }

    private void saveGatewayRouteNames(List<String> routes) throws IOException {
        context.stateStore().set("gateway.tcp.routes", String.join(",", routes));
    }

    private boolean isValidGatewayRouteName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]+");
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
        
        System.out.println(YELLOW + "Gateway:" + RESET);
        System.out.println("gateway-help                                  - Xem hướng dẫn Gateway");
        System.out.println("gateway-show                                  - Xem cấu hình Gateway");
        System.out.println("gateway-set <key> <value>                    - Set config Gateway thủ công");
        System.out.println("gateway-route-add <name> <host> <port>       - Thêm/sửa route TCP");
        System.out.println("gateway-route-remove <name>                  - Xóa route TCP");
        System.out.println("gateway-route-enable <name>                  - Bật route TCP");
        System.out.println("gateway-route-disable <name>                 - Tắt route TCP");
        System.out.println("gateway-default <route|close>                - Chọn TCP route mặc định");
        System.out.println();

        System.out.println(YELLOW + "HTTP static file service:" + RESET);
    System.out.println("gateway-set gateway.http.root /home/container/www       - Đặt thư mục chứa HTML/CSS/JS");
    System.out.println("gateway-set gateway.http.index index.html               - Đặt file index");
    System.out.println("gateway-set gateway.http.spa true                       - Bật SPA fallback về index.html");
    System.out.println("gateway-set gateway.http.spa false                      - Tắt SPA fallback");
    System.out.println();

        System.out.println("shutdown-terminal            - Tắt app");
        System.out.println();

        System.out.println(RED + "Không hỗ trợ tốt:" + RESET);
        System.out.println("su, sudo, nano, vim, vi, top, htop");

        context.logService().write("[HELP]\n");
    }
}
