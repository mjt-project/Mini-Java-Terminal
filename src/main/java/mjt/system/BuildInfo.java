package main.java.mjt.system;

public final class BuildInfo {
    public static final String NAME = "Mini Java Terminal";
    public static final String VERSION = "3.0.0-SNAPSHOT+16";
    public static final String RELEASE = "";

    private BuildInfo() {
    }

    public static String displayVersion() {
        return NAME + " v" + VERSION;
    }
}
