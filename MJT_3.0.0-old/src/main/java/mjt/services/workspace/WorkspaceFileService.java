package main.java.mjt.services.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import main.java.mjt.system.StateStore;

/** Safe universal file API for registered workspaces. */
public final class WorkspaceFileService {
    public static final class FileEntry {
        public final String name;
        public final String path;
        public final String type;
        public final long size;
        public final long modifiedAt;
        public final boolean symlink;

        FileEntry(String name, String path, String type, long size, long modifiedAt, boolean symlink) {
            this.name = name;
            this.path = path;
            this.type = type;
            this.size = size;
            this.modifiedAt = modifiedAt;
            this.symlink = symlink;
        }
    }

    public static final class ListResult {
        public final WorkspaceDefinition workspace;
        public final String path;
        public final String parent;
        public final List<FileEntry> entries;

        ListResult(WorkspaceDefinition workspace, String path, String parent, List<FileEntry> entries) {
            this.workspace = workspace;
            this.path = path;
            this.parent = parent;
            this.entries = entries;
        }
    }

    public static final class ReadResult {
        public final WorkspaceDefinition workspace;
        public final String path;
        public final String content;
        public final long size;

        ReadResult(WorkspaceDefinition workspace, String path, String content, long size) {
            this.workspace = workspace;
            this.path = path;
            this.content = content;
            this.size = size;
        }
    }

    private final StateStore stateStore;
    private final WorkspaceRegistryService registry;
    private final WorkspacePathGuard guard;

    public WorkspaceFileService(StateStore stateStore, WorkspaceRegistryService registry) {
        this.stateStore = stateStore;
        this.registry = registry;
        this.guard = new WorkspacePathGuard(registry);
    }

    public ListResult list(String workspaceId, String rawPath) throws IOException {
        WorkspaceDefinition workspace = requireWorkspace(workspaceId);
        Path directory = guard.existingDirectory(workspace, rawPath);
        List<FileEntry> entries = new ArrayList<>();

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path child : stream) {
                boolean symlink = Files.isSymbolicLink(child);
                boolean directoryEntry = !symlink && Files.isDirectory(child);
                long size = directoryEntry || symlink ? 0L : safeSize(child);
                FileTime modified = safeModified(child);
                String relative = guard.relative(workspace, child);
                entries.add(new FileEntry(
                        child.getFileName().toString(),
                        relative,
                        symlink ? "symlink" : (directoryEntry ? "directory" : "file"),
                        size,
                        modified == null ? 0L : modified.toMillis(),
                        symlink
                ));
            }
        }

        entries.sort(Comparator
                .comparing((FileEntry entry) -> !entry.type.equals("directory"))
                .thenComparing(entry -> entry.name.toLowerCase()));

        String path = guard.relative(workspace, directory);
        String parent = "";
        if (!path.isBlank()) {
            int slash = path.lastIndexOf('/');
            parent = slash < 0 ? "" : path.substring(0, slash);
        }
        return new ListResult(workspace, path, parent, entries);
    }

    public ReadResult read(String workspaceId, String rawPath) throws IOException {
        WorkspaceDefinition workspace = requireWorkspace(workspaceId);
        Path file = guard.existingFile(workspace, rawPath);
        long size = Files.size(file);
        long max = Math.max(1, stateStore.getInt("workspace.files.maxReadBytes", 1_048_576));
        if (size > max) {
            throw new IOException("File is too large for panel read (max " + max + " bytes).");
        }
        return new ReadResult(workspace, guard.relative(workspace, file), Files.readString(file, StandardCharsets.UTF_8), size);
    }

    public void write(String workspaceId, String rawPath, String content) throws IOException {
        WorkspaceDefinition workspace = requireWritableWorkspace(workspaceId);
        Path target = guard.createTarget(workspace, rawPath);
        if (Files.exists(target) && Files.isDirectory(target)) throw new IOException("Cannot write a directory.");
        Files.createDirectories(target.getParent());
        Files.writeString(target, content == null ? "" : content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    public void createFile(String workspaceId, String rawPath) throws IOException {
        WorkspaceDefinition workspace = requireWritableWorkspace(workspaceId);
        Path target = guard.createTarget(workspace, rawPath);
        if (Files.exists(target)) throw new IOException("Path already exists.");
        Files.createDirectories(target.getParent());
        Files.writeString(target, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public void mkdir(String workspaceId, String rawPath) throws IOException {
        WorkspaceDefinition workspace = requireWritableWorkspace(workspaceId);
        Path target = guard.createTarget(workspace, rawPath);
        if (Files.exists(target)) throw new IOException("Path already exists.");
        Files.createDirectories(target);
    }

    public void rename(String workspaceId, String rawFrom, String rawTo) throws IOException {
        WorkspaceDefinition workspace = requireWritableWorkspace(workspaceId);
        Path source = guard.existing(workspace, rawFrom);
        Path target = guard.createTarget(workspace, rawTo);
        if (Files.exists(target)) throw new IOException("Target already exists.");
        Files.move(source, target);
    }

    public void delete(String workspaceId, String rawPath) throws IOException {
        WorkspaceDefinition workspace = requireWritableWorkspace(workspaceId);
        Path target = guard.existing(workspace, rawPath);
        Path root = guard.root(workspace);
        if (target.equals(root)) throw new IOException("Workspace root cannot be deleted.");
        if (Files.isDirectory(target) && !Files.isSymbolicLink(target)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(target)) {
                if (stream.iterator().hasNext()) throw new IOException("Directory must be empty before deletion.");
            }
        }
        Files.delete(target);
    }

    private WorkspaceDefinition requireWorkspace(String id) throws IOException {
        WorkspaceDefinition workspace = registry.get(id);
        if (workspace == null) throw new IOException("Workspace not found: " + id);
        return workspace;
    }

    private WorkspaceDefinition requireWritableWorkspace(String id) throws IOException {
        WorkspaceDefinition workspace = requireWorkspace(id);
        if (workspace.readOnly()) throw new IOException("Workspace is read-only.");
        return workspace;
    }

    private long safeSize(Path file) {
        try { return Files.size(file); } catch (IOException ignored) { return 0L; }
    }

    private FileTime safeModified(Path file) {
        try { return Files.getLastModifiedTime(file); } catch (IOException ignored) { return null; }
    }
}
