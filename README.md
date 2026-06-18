# MJT — Workspace Foundation

This patch turns MJT from a Minecraft-only panel backend into a workspace-oriented control core.

## Root model

```text
/home/container/MJT     -> MJT control plane
/home/container/server  -> panel-managed workspace root
```

Minecraft, websites, Node applications, Python applications and configuration folders can all be registered as workspaces below `/home/container/server`.

## New commands

```text
.mjt workspace list
.mjt workspace show <id>
.mjt workspace sync
.mjt workspace add <id> <type> <path>
.mjt workspace remove <id>
```

`workspace add` registers an existing folder only. It never deletes the folder.

## Files API safety model

The browser sends a workspace ID plus a relative path:

```json
{ "workspace": "smp", "path": "server.properties" }
```

The Java core resolves it inside the registered workspace root. Absolute paths, `../` traversal and symlink escape are rejected.

## Install

Overlay this patch onto `3.0.0-SNAPSHOT+8`, then build:

```bash
mvn -U clean package
```

After first start inspect:

```text
.mjt workspace list
.mjt panel start
```
