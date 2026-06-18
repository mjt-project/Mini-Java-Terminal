package main.java.mjt.command;

/**
 * Alias helper for future modular command migration.
 *
 * The compatibility dispatcher still contains the full historical alias table.
 * New modules can use this helper for small local aliases while commands are
 * gradually moved out of CommandDispatcher.
 */
public final class CommandAliases {
    private CommandAliases() {
    }

    public static String compact(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
    }

    public static String firstToken(String raw) {
        String clean = compact(raw);
        int space = clean.indexOf(' ');
        return space < 0 ? clean : clean.substring(0, space);
    }
}
