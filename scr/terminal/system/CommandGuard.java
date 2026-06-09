package terminal.system;

import java.io.IOException;

public class CommandGuard {
    private static final String RESET = "\u001B[0m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final LogService logService;

    public CommandGuard(LogService logService) {
        this.logService = logService;
    }

    public boolean isBlocked(String command) throws IOException {
        String lower = command.toLowerCase().trim();

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

        if (lower.equals("nano") || lower.startsWith("nano ")
                || lower.equals("vim") || lower.startsWith("vim ")
                || lower.equals("vi") || lower.startsWith("vi ")
                || lower.equals("top")
                || lower.equals("htop")) {

            System.out.println(RED + "Lệnh này cần terminal tương tác thật nên dễ treo trong panel." + RESET);
            logService.write("[BLOCKED] interactive command\n");
            return true;
        }

        return false;
    }
}