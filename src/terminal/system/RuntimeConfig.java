package terminal.system;

import java.nio.file.Path;
import java.nio.file.Paths;

public class RuntimeConfig {
    private Path currentDir = Paths.get("").toAbsolutePath().normalize();

    // 0 = không giới hạn thời gian chạy command
    private int commandTimeoutSeconds = 0;

    public Path getCurrentDir() {
        return currentDir;
    }

    public void setCurrentDir(Path currentDir) {
        this.currentDir = currentDir.toAbsolutePath().normalize();
    }

    public int getCommandTimeoutSeconds() {
        return commandTimeoutSeconds;
    }

    public void setCommandTimeoutSeconds(int commandTimeoutSeconds) {
        if (commandTimeoutSeconds < 0) {
            throw new IllegalArgumentException("Timeout không được nhỏ hơn 0.");
        }

        this.commandTimeoutSeconds = commandTimeoutSeconds;
    }

    public boolean hasTimeout() {
        return commandTimeoutSeconds > 0;
    }
}