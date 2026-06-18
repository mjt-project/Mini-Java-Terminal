# CHANGE.md

## 3.0.0-SNAPSHOT+9 — Workspace Foundation and Universal File API

### Added

- `WorkspaceRegistryService` for folders and services under `/home/container/server`.
- Default workspaces: `server-root`, `velocity`, `smp`, `lobby`.
- Automatic Minecraft profile → workspace synchronization.
- Safe universal file API scoped by workspace ID.
- API capabilities endpoint to prevent the frontend from calling unavailable features.
- Workspace commands: list, show, add, remove, sync.

### Security

- Workspace paths must stay inside `workspace.root`.
- File API accepts relative paths only.
- Path traversal, absolute paths and symlink escape are rejected.
- File reads are capped by `workspace.files.maxReadBytes`.
- Empty directories only may be deleted; recursive delete is intentionally not implemented.

### API

```text
GET  /api/capabilities
GET  /api/workspaces
GET  /api/workspaces/{id}
POST /api/workspaces/register
GET  /api/workspaces/{id}/files/list?path=
GET  /api/workspaces/{id}/files/read?path=
POST /api/workspaces/{id}/files/write
POST /api/workspaces/{id}/files/create
POST /api/workspaces/{id}/files/mkdir
POST /api/workspaces/{id}/files/rename
POST /api/workspaces/{id}/files/delete
```
