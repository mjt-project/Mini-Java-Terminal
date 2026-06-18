package main.java.mjt.command;

import java.util.Arrays;
import java.util.List;

/**
 * Normalized command payload passed to modular command handlers.
 */
public final class CommandRequest {
    private final String raw;
    private final String original;
    private final List<String> args;
    private final CommandContext context;

    public CommandRequest(String raw, String original, CommandContext context) {
        this.raw = raw == null ? "" : raw.trim();
        this.original = original == null ? this.raw : original.trim();
        this.args = this.raw.isBlank() ? List.of() : Arrays.asList(this.raw.split("\\s+"));
        this.context = context;
    }

    public String raw() {
        return raw;
    }

    public String original() {
        return original;
    }

    public List<String> args() {
        return args;
    }

    public CommandContext context() {
        return context;
    }

    public boolean is(String command) {
        return raw.equalsIgnoreCase(command);
    }

    public boolean startsWith(String prefix) {
        return raw.equalsIgnoreCase(prefix)
                || raw.toLowerCase().startsWith(prefix.toLowerCase() + " ");
    }

    public String after(String prefix) {
        if (raw.length() <= prefix.length()) {
            return "";
        }
        return raw.substring(prefix.length()).trim();
    }
}
