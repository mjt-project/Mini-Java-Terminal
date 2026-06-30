package main.java.mjt.services.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import main.java.mjt.system.StateStore;

/**
 * Keeps a small registry of panel workspaces. It never treats arbitrary paths
 * as valid: every workspace must stay inside workspace.root (server data root).
 */
public final class WorkspaceRegistryService {
    private static final String IDS_KEY = "workspace.ids";
    private final StateStore stateStore;

    public WorkspaceRegistryService(StateStore stateStore) {
        this.stateStore = stateStore;
    }

    public synchronized Path getServerRoot() throws IOException {
        Path root = Paths.get(stateStore.get("workspace.root", "/home/container/server"))
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(root);
        return root;
    }

    public synchronized List<WorkspaceDefinition> list() throws IOException {
        syncMinecraftProfiles();
        List<WorkspaceDefinition> result = new ArrayList<>();
        for (String id : ids()) {
            WorkspaceDefinition definition = load(id);
            if (definition != null) result.add(definition);
        }
        return result;
    }

    public synchronized WorkspaceDefinition get(String rawId) throws IOException {
        syncMinecraftProfiles();
        String id = normalizeId(rawId);
        if (id.isBlank()) return null;
        return load(id);
    }

    public synchronized WorkspaceDefinition registerExisting(
            String rawId,
            String rawType,
            String rawName,
            String rawPath,
            String startCommand,
            String stopCommand,
            String port
    ) throws IOException {
        String id = requireId(rawId);
        String type = normalizeType(rawType);
        String name = rawName == null || rawName.isBlank() ? id : rawName.trim();
        Path root = getServerRoot();
        Path requested = Paths.get(rawPath == null ? "" : rawPath.trim());
        if (!requested.isAbsolute()) requested = root.resolve(requested);
        requested = requested.toAbsolutePath().normalize();

        if (!requested.startsWith(root)) {
            throw new IOException("Workspace path must stay inside " + root);
        }
        if (!Files.isDirectory(requested)) {
            throw new IOException("Workspace folder does not exist: " + requested);
        }

        String base = "workspace." + id + ".";
        stateStore.set(base + "type", type);
        stateStore.set(base + "name", name);
        stateStore.set(base + "path", requested.toString());
        stateStore.set(base + "start", safe(startCommand));
        stateStore.set(base + "stop", safe(stopCommand));
        stateStore.set(base + "port", safe(port));
        stateStore.set(base + "linkedMinecraftProfile", "");
        stateStore.set(base + "readOnly", "false");
        addId(id);
        return load(id);
    }

    public synchronized void remove(String rawId) throws IOException {
        String id = requireId(rawId);
        if (id.equals("server-root")) {
            throw new IOException("The server-root workspace is protected.");
        }
        String base = "workspace." + id + ".";
        stateStore.remove(base + "type");
        stateStore.remove(base + "name");
        stateStore.remove(base + "path");
        stateStore.remove(base + "start");
        stateStore.remove(base + "stop");
        stateStore.remove(base + "port");
        stateStore.remove(base + "linkedMinecraftProfile");
        stateStore.remove(base + "readOnly");
        Set<String> ids = ids();
        ids.remove(id);
        stateStore.set(IDS_KEY, String.join(",", ids));
    }

    public synchronized void syncMinecraftProfiles() throws IOException {
        Path root = getServerRoot();
        Set<String> ids = ids();
        boolean changed = false;

        for (String profile : split(stateStore.get("minecraft.profiles", ""))) {
            String cleanProfile = normalizeId(profile);
            if (cleanProfile.isBlank()) continue;
            String baseMinecraft = "minecraft.profile." + cleanProfile + ".";
            String workdir = stateStore.get(baseMinecraft + "workdir", "").trim();
            if (workdir.isBlank()) continue;

            Path path = Paths.get(workdir).toAbsolutePath().normalize();
            if (!path.startsWith(root)) continue;
            Files.createDirectories(path);

            String workspaceBase = "workspace." + cleanProfile + ".";
            if (!stateStore.has(workspaceBase + "path")) {
                String minecraftType = normalizeType("minecraft-" + stateStore.get(baseMinecraft + "type", "server"));
                stateStore.set(workspaceBase + "type", minecraftType);
                stateStore.set(workspaceBase + "name", cleanProfile);
                stateStore.set(workspaceBase + "path", path.toString());
                stateStore.set(workspaceBase + "start", stateStore.get(baseMinecraft + "command", ""));
                stateStore.set(workspaceBase + "stop", stateStore.get(baseMinecraft + "stop", ""));
                stateStore.set(workspaceBase + "port", stateStore.get(baseMinecraft + "port", ""));
                stateStore.set(workspaceBase + "linkedMinecraftProfile", cleanProfile);
                stateStore.set(workspaceBase + "readOnly", "false");
                ids.add(cleanProfile);
                changed = true;
            }
        }

        if (!stateStore.has("workspace.server-root.path")) {
            stateStore.set("workspace.server-root.type", "folder");
            stateStore.set("workspace.server-root.name", "Server Root");
            stateStore.set("workspace.server-root.path", root.toString());
            stateStore.set("workspace.server-root.start", "");
            stateStore.set("workspace.server-root.stop", "");
            stateStore.set("workspace.server-root.port", "");
            stateStore.set("workspace.server-root.linkedMinecraftProfile", "");
            stateStore.set("workspace.server-root.readOnly", "false");
            ids.add("server-root");
            changed = true;
        }

        if (changed || !stateStore.has(IDS_KEY)) {
            stateStore.set(IDS_KEY, String.join(",", ids));
        }
    }

    public void printWorkspaces() throws IOException {
        List<WorkspaceDefinition> workspaces = list();
        System.out.println("[WORKSPACES] root=" + getServerRoot());
        for (WorkspaceDefinition workspace : workspaces) {
            System.out.println("- " + workspace.id() + " | " + workspace.type() + " | " + workspace.root());
        }
    }

    public void printWorkspace(String id) throws IOException {
        WorkspaceDefinition workspace = get(id);
        if (workspace == null) {
            System.out.println("[WORKSPACE] Not found: " + id);
            return;
        }
        System.out.println("[WORKSPACE] " + workspace.id());
        System.out.println("Name       : " + workspace.name());
        System.out.println("Type       : " + workspace.type());
        System.out.println("Path       : " + workspace.root());
        System.out.println("Start      : " + workspace.startCommand());
        System.out.println("Stop       : " + workspace.stopCommand());
        System.out.println("Port       : " + workspace.port());
        System.out.println("Minecraft  : " + workspace.linkedMinecraftProfile());
        System.out.println("Read only  : " + workspace.readOnly());
    }

    private WorkspaceDefinition load(String id) throws IOException {
        String base = "workspace." + id + ".";
        String rawPath = stateStore.get(base + "path", "").trim();
        if (rawPath.isBlank()) return null;
        Path serverRoot = getServerRoot();
        Path root = Paths.get(rawPath).toAbsolutePath().normalize();
        if (!root.startsWith(serverRoot)) return null;
        return new WorkspaceDefinition(
                id,
                normalizeType(stateStore.get(base + "type", "folder")),
                stateStore.get(base + "name", id).trim(),
                root,
                stateStore.get(base + "start", "").trim(),
                stateStore.get(base + "stop", "").trim(),
                stateStore.get(base + "port", "").trim(),
                normalizeId(stateStore.get(base + "linkedMinecraftProfile", "")),
                stateStore.getBoolean(base + "readOnly", false)
        );
    }

    private void addId(String id) throws IOException {
        Set<String> ids = ids();
        ids.add(id);
        stateStore.set(IDS_KEY, String.join(",", ids));
    }

    private Set<String> ids() {
        Set<String> ids = new LinkedHashSet<>();
        for (String id : split(stateStore.get(IDS_KEY, "server-root"))) {
            String clean = normalizeId(id);
            if (!clean.isBlank()) ids.add(clean);
        }
        if (ids.isEmpty()) ids.add("server-root");
        return ids;
    }

    private List<String> split(String raw) {
        List<String> values = new ArrayList<>();
        if (raw == null || raw.isBlank()) return values;
        for (String value : raw.split(",")) values.add(value);
        return values;
    }

    private String requireId(String raw) throws IOException {
        String id = normalizeId(raw);
        if (id.isBlank()) throw new IOException("Workspace id must use lowercase letters, numbers, hyphen or underscore.");
        return id;
    }

    public static String normalizeId(String raw) {
        if (raw == null) return "";
        String clean = raw.trim().toLowerCase(Locale.ROOT);
        if (!clean.matches("[a-z0-9][a-z0-9_-]{0,63}")) return "";
        return clean;
    }

    private String normalizeType(String raw) {
        String clean = raw == null ? "folder" : raw.trim().toLowerCase(Locale.ROOT);
        if (!clean.matches("[a-z0-9][a-z0-9_-]{0,63}")) return "folder";
        return clean;
    }

    private String safe(String raw) { return raw == null ? "" : raw.trim(); }
}
