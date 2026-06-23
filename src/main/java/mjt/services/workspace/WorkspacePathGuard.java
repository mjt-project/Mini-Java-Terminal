package main.java.mjt.services.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves panel file paths without permitting traversal or symlink escape.
 */
public final class WorkspacePathGuard {
    private final WorkspaceRegistryService registry;

    public WorkspacePathGuard(WorkspaceRegistryService registry) {
        this.registry = registry;
    }

    public Path root(WorkspaceDefinition workspace) throws IOException {
        Path configured = workspace.root().toAbsolutePath().normalize();
        if (!Files.isDirectory(configured)) {
            throw new IOException("Workspace folder is unavailable: " + configured);
        }
        Path real = configured.toRealPath();
        Path allowed = registry.getServerRoot().toRealPath();
        if (!real.startsWith(allowed)) {
            throw new IOException("Workspace root escaped server root.");
        }
        return real;
    }

    public Path existing(WorkspaceDefinition workspace, String relativePath) throws IOException {
        Path root = root(workspace);
        Path candidate = resolve(root, relativePath);
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Path not found: " + display(relativePath));
        }
        Path real = candidate.toRealPath();
        if (!real.startsWith(root)) {
            throw new IOException("Path resolves outside workspace.");
        }
        return real;
    }

    public Path existingDirectory(WorkspaceDefinition workspace, String relativePath) throws IOException {
        Path target = existing(workspace, relativePath);
        if (!Files.isDirectory(target)) throw new IOException("Not a directory: " + display(relativePath));
        return target;
    }

    public Path existingFile(WorkspaceDefinition workspace, String relativePath) throws IOException {
        Path target = existing(workspace, relativePath);
        if (!Files.isRegularFile(target)) throw new IOException("Not a regular file: " + display(relativePath));
        return target;
    }

    public Path createTarget(WorkspaceDefinition workspace, String relativePath) throws IOException {
        Path root = root(workspace);
        Path candidate = resolve(root, relativePath);
        if (candidate.equals(root)) throw new IOException("Workspace root cannot be overwritten.");

        Path parent = candidate.getParent();
        if (parent == null) throw new IOException("Invalid path.");
        Path existingParent = parent;
        while (existingParent != null && !Files.exists(existingParent, LinkOption.NOFOLLOW_LINKS)) {
            existingParent = existingParent.getParent();
        }
        if (existingParent == null) throw new IOException("No existing parent for target.");
        Path realParent = existingParent.toRealPath();
        if (!realParent.startsWith(root)) throw new IOException("Target parent escapes workspace.");

        if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) throw new IOException("Target resolves outside workspace.");
        }
        return candidate;
    }

    public String relative(WorkspaceDefinition workspace, Path target) throws IOException {
        Path root = root(workspace);
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IOException("Path outside workspace.");
        String value = root.relativize(normalized).toString().replace('\\', '/');
        return value.equals(".") ? "" : value;
    }

    private Path resolve(Path root, String rawRelative) throws IOException {
        String clean = rawRelative == null ? "" : rawRelative.trim();
        if (clean.indexOf('\0') >= 0) throw new IOException("Invalid path.");
        Path relative = clean.isEmpty() ? Paths.get("") : Paths.get(clean);
        if (relative.isAbsolute()) throw new IOException("Absolute paths are not allowed.");
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) throw new IOException("Path traversal is not allowed.");
        return candidate;
    }

    private String display(String path) { return path == null || path.isBlank() ? "/" : path; }
}
