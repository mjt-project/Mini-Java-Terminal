# Mini Java Terminal v2.6.3

Release: System Downloader & Cloudflared Auto Install

## Added

- Added `SystemDownloadService` under `main.java.mjt.system.download`.
- Added per-task system folder layout:
  - `MJT/system/downloads/cloudflared/`
  - `MJT/system/tasks/`
- Added automatic cloudflared installer:
  - Detects OS using `os.name`.
  - Detects CPU architecture using `os.arch`.
  - Selects the matching Cloudflare GitHub release asset.
  - Downloads to `MJT/system/downloads/cloudflared/`.
  - Makes binary executable on Linux.
  - Runs `cloudflared --version` after install.
  - Saves `tunnel.cloudflared.path` automatically.

## Commands

- `.mjt system install cloudflared`
- `.mjt system download cloudflared`
- `.mjt system cloudflared check`
- `.mjt system cloudflared show`
- `.mjt cloudflared install`
- `.mjt tunnel binary install`

## Notes

Guest Quick Tunnel still does not need any token.
If cloudflared is missing, run:

```text
.mjt system install cloudflared
```

Then test guest site:

```text
.mjt website guest create
```
