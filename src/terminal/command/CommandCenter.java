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
    printTitle("Gateway Commands");

    printSection("1. Gateway Core");
    printCommand("gateway-help", "Xem hướng dẫn Gateway");
    printCommand("gateway-show", "Xem toàn bộ cấu hình Gateway");
    printCommand("gateway-set <key> <value>", "Set config Gateway thủ công");
    printCommand("gateway-default <route|close>", "Chọn TCP route mặc định hoặc đóng TCP fallback");

    printSection("2. HTTP Static File Service");
    printCommand("gateway-set gateway.http.enabled true", "Bật HTTP service");
    printCommand("gateway-set gateway.http.enabled false", "Tắt HTTP service");
    printCommand("gateway-set gateway.http.root /home/container/www", "Đặt thư mục chứa HTML/CSS/JS");
    printCommand("gateway-set gateway.http.index index.html", "Đặt file index mặc định");
    printCommand("gateway-set gateway.http.spa true", "Bật SPA fallback về index.html");
    printCommand("gateway-set gateway.http.spa false", "Tắt SPA fallback");

    printSection("3. SSH / SFTP Gateway Proxy");
    printCommand("gateway-set gateway.ssh.enabled true", "Bật SSH/SFTP route qua Gateway");
    printCommand("gateway-set gateway.ssh.enabled false", "Tắt SSH/SFTP route qua Gateway");
    printCommand("gateway-set gateway.ssh.host 127.0.0.1", "Đặt host SSH/SFTP nội bộ");
    printCommand("gateway-set gateway.ssh.port 2022", "Đặt port SSH/SFTP nội bộ");

    printSection("4. Manual TCP Routes");
    printCommand("gateway-route-add <name> <host> <port>", "Thêm hoặc sửa TCP route");
    printCommand("gateway-route-remove <name>", "Xóa TCP route");
    printCommand("gateway-route-enable <name>", "Bật TCP route");
    printCommand("gateway-route-disable <name>", "Tắt TCP route");
    printCommand("gateway-default <name>", "Chọn route làm TCP fallback mặc định");
    printCommand("gateway-default close", "Đóng TCP fallback nếu không phải HTTP/SSH");

    printSection(" ---   Examples   ---");
    printCommand("gateway-route-add mc 127.0.0.1 25565", "Thêm route Minecraft Java");
    printCommand("gateway-default mc", "Cho TCP fallback đi vào route mc");
    printCommand("gateway-route-add velocity 127.0.0.1 25577", "Thêm route Velocity");
    printCommand("gateway-default velocity", "Cho TCP fallback đi vào Velocity");
    printCommand("gateway-default close", "Tắt TCP fallback");

    System.out.println();
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

    private void printTitle(String title) {
        System.out.println();
        System.out.println(GREEN + "==================================================" + RESET);
        System.out.println(GREEN + title + RESET);
        System.out.println(GREEN + "==================================================" + RESET);
    }
    
    private void printSection(String title) {
        System.out.println();
        System.out.println(YELLOW + "== " + title + " ==" + RESET);
    }
    
    private void printCommand(String command, String description) {
        System.out.printf("  " + CYAN + "%-48s" + RESET + " %s%n", command, description);
    }

    private void printHelp() throws IOException {
        printTitle("Mini Java Terminal Commands");

        printSection("1. Terminal Runtime");
        printCommand("help", "Xem hướng dẫn tổng quan");
        printCommand("pwd", "Xem thư mục hiện tại");
        printCommand("cd <folder>", "Chuyển thư mục");
        printCommand("clear / cls", "Xóa màn hình console");
        printCommand("public-ip", "Kiểm tra public IPv4 của panel host");
        printCommand("timeout", "Xem timeout hiện tại");
        printCommand("timeout <seconds>", "Đặt timeout, 0 = không giới hạn");

        printSection("2. Cloudflare DDNS");
        printCommand("cloudflare-show", "Xem config Cloudflare");
        printCommand("cloudflare-set token <token>", "Lưu Cloudflare API token");
        printCommand("cloudflare-set zone <zone_id>", "Lưu Cloudflare Zone ID");
        printCommand("cloudflare-set name <domain>", "Lưu DNS record name");
        printCommand("cloudflare-set proxied false", "Đặt DNS only");
        printCommand("cloudflare-set ttl 120", "Đặt TTL");
        printCommand("cloudflare-set interval 300", "Đặt thời gian check IP");
        printCommand("cloudflare-ddns-once", "Cập nhật DNS một lần");
        printCommand("cloudflare-ddns-start", "Bật auto DDNS");
        printCommand("cloudflare-ddns-stop", "Dừng auto DDNS");
        printCommand("cloudflare-ddns-status", "Xem trạng thái DDNS");

        printSection("3. SSH / SFTP Server");
        printCommand("ssh-show", "Xem config SSH/SFTP");
        printCommand("ssh-set host <host>", "Bind host, ví dụ 127.0.0.1 hoặc 0.0.0.0");
        printCommand("ssh-set port <port>", "Đặt port SSH/SFTP");
        printCommand("ssh-set user <username>", "Đặt username SSH/SFTP");
        printCommand("ssh-set pass <password>", "Đặt password SSH/SFTP");
        printCommand("ssh-set root <folder>", "Đặt thư mục gốc SSH/SFTP");
        printCommand("ssh-start", "Bật SSH/SFTP server");
        printCommand("ssh-stop", "Tắt SSH/SFTP server");
        printCommand("ssh-status", "Xem trạng thái SSH/SFTP");

        printSection("4. SFTP Compatibility Aliases");
        printCommand("sftp-show", "Alias của ssh-show");
        printCommand("sftp-set <key> <value>", "Alias của ssh-set");
        printCommand("sftp-start", "Alias của ssh-start");
        printCommand("sftp-stop", "Alias của ssh-stop");
        printCommand("sftp-status", "Alias của ssh-status");

        printSection("5. Gateway");
        printCommand("gateway-help", "Xem hướng dẫn Gateway đầy đủ");
        printCommand("gateway-show", "Xem cấu hình Gateway");
        printCommand("gateway-set <key> <value>", "Set config Gateway thủ công");
        printCommand("gateway-default <route|close>", "Chọn TCP route mặc định");
        printCommand("gateway-route-add <name> <host> <port>", "Thêm/sửa route TCP");
        printCommand("gateway-route-remove <name>", "Xóa route TCP");
        printCommand("gateway-route-enable <name>", "Bật route TCP");
        printCommand("gateway-route-disable <name>", "Tắt route TCP");

        printSection("6. Safety");
        printCommand("shutdown-terminal", "Tắt Mini Java Terminal");
        printCommand("exit", "Bị chặn để tránh làm server offline");

        printSection("Commands not recommended");
        System.out.println("  " + RED + "su, sudo, nano, vim, vi, top, htop" + RESET);

        System.out.println();
        System.out.println(YELLOW + "Tip:" + RESET + " Gõ " + CYAN + "gateway-help" + RESET + " để xem riêng phần Gateway.");
        System.out.println();

        context.logService().write("[HELP]\n");
    }
}
