package main.java.mjt.system;

public final class BuildInfo {
    public static final String NAME = "Mini Java Terminal";
    public static final String VERSION = "2.5.6";
    public static final String RELEASE = "Gateway HTTP HTTPS Protocol Router";

    private BuildInfo() {
    }

    public static String displayVersion() {
        return NAME + " v" + VERSION;
    }
}
