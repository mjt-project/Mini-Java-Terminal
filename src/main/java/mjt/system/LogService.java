package main.java.mjt.system;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class LogService {
    private final Path logsDir;
    private final Path logFile;

    public LogService(Path logsDir) throws IOException {
        this.logsDir = logsDir;

        if (!Files.exists(this.logsDir)) {
            Files.createDirectories(this.logsDir);
        }

        String time = LocalDateTime.now().format(
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        );

        this.logFile = this.logsDir.resolve("terminal-" + time + ".log");

        write("[LOG START] " + LocalDateTime.now() + "\n");
    }

    public Path getLogFile() {
        return logFile;
    }

    public synchronized void write(String text) throws IOException {
        Files.writeString(
                logFile,
                text,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        );
    }

    public synchronized void line(String text) throws IOException {
        write(text + System.lineSeparator());
    }

    public synchronized void section(String title) throws IOException {
        write("\n========================================\n");
        write("[" + title + "] " + LocalDateTime.now() + "\n");
    }
}