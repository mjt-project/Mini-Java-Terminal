# MJT PRoot Phase 1

This overlay introduces the PRoot runtime boundary required for MJT guest workloads.

## What is in this phase

- `ProotService`: runs an explicitly requested shell command inside a bootstrapped rootfs.
- Guest rootfs default: `/home/container/MJT/system/rootfs`.
- Guest package manager data stays below that rootfs, including `/usr`, `/etc`, `/var/lib/dpkg` and `/var/cache/apt`.
- Host workspace `/home/container/server` is bind-mounted as `/workspace`.
- `policy-rc.d` is created in a ready rootfs so apt packages cannot auto-start host-visible daemons during installation.
- Optional `.command` routing into the guest after `.mjt proot enter`.

## Deliberately not in this phase

- Rootfs download/bootstrap automation. A generic downloader would need architecture, distribution, checksum/signature and provider policy choices. It must not be guessed.
- Interactive TTY programs in the existing line-based MJT console. `nano`, `vim`, `htop` need a real TTY; Phase 2 should force SSH sessions or Web Terminal PTY sessions through PRoot.
- Migrating Minecraft/OpenVSCode launchers to PRoot. `ProotService.buildGuestCommand(...)` is provided for that next step.
- Any claim that PRoot is a hard security sandbox. It is a compatibility/runtime boundary, not a replacement for namespaces/containers.

## Required operator setup

1. Install or upload a matching Linux `proot` binary at:
   `/home/container/MJT/system/bin/proot`
2. Unpack a real Debian/Ubuntu rootfs at:
   `/home/container/MJT/system/rootfs`
3. Confirm the guest contains:
   - `/bin/sh`
   - `/usr/bin/apt`
   - `/var/lib/dpkg/status`
4. Run:

```text
.mjt proot init
.mjt proot show
.mjt proot test
```

## Commands

```text
.mjt proot init
.mjt proot show
.mjt proot test
.mjt proot exec apt update
.mjt proot exec apt install nano htop git
.mjt proot enter
.command npm --version
.mjt proot leave
```

`proot enter` does not create an interactive shell. It changes `.command` routing only; commands execute through the guest rootfs.
