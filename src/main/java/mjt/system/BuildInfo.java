package main.java.mjt.system;

public final class BuildInfo {
    public static final String NAME = "Mini Java Terminal";
    public static final String VERSION = "3.0.0-SNAPSHOT+9";
    public static final String RELEASE = "Workspace Foundation and Universal File API";

    private BuildInfo() {
    }

    public static String displayVersion() {
        return NAME + " v" + VERSION;
    }
}
