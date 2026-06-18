package main.java.mjt.services.workspace;

import java.nio.file.Path;

/**
 * A registered, panel-visible folder inside the MJT server data root.
 * The workspace id is stable; the display name/path can be changed later.
 */
public final class WorkspaceDefinition {
    private final String id;
    private final String type;
    private final String name;
    private final Path root;
    private final String startCommand;
    private final String stopCommand;
    private final String port;
    private final String linkedMinecraftProfile;
    private final boolean readOnly;

    public WorkspaceDefinition(
            String id,
            String type,
            String name,
            Path root,
            String startCommand,
            String stopCommand,
            String port,
            String linkedMinecraftProfile,
            boolean readOnly
    ) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.root = root;
        this.startCommand = startCommand;
        this.stopCommand = stopCommand;
        this.port = port;
        this.linkedMinecraftProfile = linkedMinecraftProfile;
        this.readOnly = readOnly;
    }

    public String id() { return id; }
    public String type() { return type; }
    public String name() { return name; }
    public Path root() { return root; }
    public String startCommand() { return startCommand; }
    public String stopCommand() { return stopCommand; }
    public String port() { return port; }
    public String linkedMinecraftProfile() { return linkedMinecraftProfile; }
    public boolean readOnly() { return readOnly; }

    public boolean isMinecraft() {
        return type.startsWith("minecraft-") || !linkedMinecraftProfile.isBlank();
    }
}
