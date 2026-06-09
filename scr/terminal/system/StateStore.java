package terminal.system;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

public class StateStore {
    private final Path stateFile;
    private final Properties properties = new Properties();

    public StateStore(Path stateFile) throws IOException {
        this.stateFile = stateFile.toAbsolutePath().normalize();
        load();
    }

    public synchronized void load() throws IOException {
        properties.clear();

        if (!Files.exists(stateFile)) {
            save();
            return;
        }

        try (InputStream input = Files.newInputStream(stateFile)) {
            properties.load(input);
        }
    }

    public synchronized void save() throws IOException {
        Path parent = stateFile.getParent();

        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }

        try (OutputStream output = Files.newOutputStream(
                stateFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            properties.store(output, "Terminal Console Monitor State");
        }
    }

    public synchronized String get(String key) {
        return properties.getProperty(key, "");
    }

    public synchronized String get(String key, String defaultValue) {
        return properties.getProperty(key, defaultValue);
    }

    public synchronized int getInt(String key, int defaultValue) {
        String value = get(key);

        if (value.isBlank()) {
            return defaultValue;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public synchronized boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);

        if (value.isBlank()) {
            return defaultValue;
        }

        return value.equalsIgnoreCase("true")
                || value.equalsIgnoreCase("yes")
                || value.equalsIgnoreCase("1");
    }

    public synchronized void set(String key, String value) throws IOException {
        properties.setProperty(key, value);
        save();
    }

    public synchronized void remove(String key) throws IOException {
        properties.remove(key);
        save();
    }

    public synchronized boolean has(String key) {
        return properties.containsKey(key);
    }

    public synchronized Set<String> keys() {
        return new TreeSet<>(properties.stringPropertyNames());
    }

    public Path getStateFile() {
        return stateFile;
    }

    public String maskSecret(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String trimmed = value.trim();

        if (trimmed.length() <= 8) {
            return "********";
        }

        return trimmed.substring(0, 6)
                + "..."
                + trimmed.substring(trimmed.length() - 4);
    }
}