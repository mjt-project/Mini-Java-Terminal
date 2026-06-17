package main.java.mjt.system;

public final class BuildInfo {
    public static final String NAME = "Mini Java Terminal";
    public static final String VERSION = "2.6.3";
    public static final String RELEASE = "System Downloader & Cloudflared Auto Install";

    private BuildInfo() {
    }

    public static String displayVersion() {
        return NAME + " v" + VERSION;
    }
}
