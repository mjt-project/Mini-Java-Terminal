# MJT PRoot Phase 2 — Managed Guest Services

This overlay is cumulative: apply it on top of the MJT `dev` source tree. It includes Phase 1 and Phase 2 files.

## What Phase 2 adds

- `ProotService.buildGuestCommand(command, hostWorkingDirectory)` so managed services start in the correct guest-visible workspace path.
- Minecraft profile runtime selection: `host` (default) or `proot`.
- Managed OpenVSCode Server process running inside PRootFS.
- All OpenVSCode network binding is restricted to `127.0.0.1`, `::1`, or `localhost`.
- Cloudflare Tunnel remains host-side; it publishes the local OpenVSCode port without putting tunnel credentials in the guest rootfs.

## Important boundaries

```text
MJT Core (host)
  - panel, tunnel token, cloudflared, gateway, state/logs

PRootFS (guest)
  - apt/dpkg, Java, Node 24/npm, OpenVSCode binaries and guest packages

Host workspace /home/container/server
  - Minecraft worlds/configs, source code, node_modules, project data
  - bind-mounted in guest as /workspace
```

## 1. Bootstrap and test PRoot

```text
.mjt proot init
.mjt proot show
.mjt proot test
.mjt proot exec apt update
```

A modern Minecraft server must have a compatible Java runtime inside the rootfs. For example, after choosing the correct Ubuntu/Debian package version:

```text
.mjt proot exec apt install openjdk-21-jre-headless
```

## 2. Run one Minecraft profile inside PRootFS

Minecraft remains host runtime by default for compatibility. Stop it before switching runtime.

```text
.mjt minecraft stop smp
.mjt minecraft profile runtime smp proot
.mjt minecraft profile show smp
.mjt minecraft start smp
```

The profile workdir must remain under `proot.workspace`, normally `/home/container/server`. MJT converts it to a guest path below `/workspace` automatically.

To return to host Java:

```text
.mjt minecraft stop smp
.mjt minecraft profile runtime smp host
```

## 3. OpenVSCode Server in PRootFS

This phase starts an already-installed OpenVSCode Server binary. It intentionally does **not** auto-download `latest` at startup; point the configurable `current` path to the version you have installed inside the rootfs.

Recommended guest layout:

```text
/home/container/MJT/system/rootfs/opt/openvscode-server/<version>/bin/openvscode-server
/home/container/MJT/system/rootfs/opt/openvscode-server/current -> <version>
```

Then configure and run:

```text
.mjt code set binary /opt/openvscode-server/current/bin/openvscode-server
.mjt code set host 127.0.0.1
.mjt code set port 3000
.mjt code set workspace /home/container/server
.mjt code token reset
.mjt code start
.mjt code show
```

The connection token is generated automatically on first start. Rotate it only while Code Server is stopped.

## 4. Publish OpenVSCode through Cloudflare

Keep OpenVSCode local. Add a tunnel route pointing at its local port:

```text
.mjt tunnel route add vscode vscode.example.com http://127.0.0.1:3000
```

In Cloudflare **token mode**, configure the public hostname in the Zero Trust dashboard. In **config mode**, regenerate `config.yml` then start the tunnel using a valid tunnel name/id and credentials.

## New commands

```text
.mjt code show
.mjt code start
.mjt code stop
.mjt code restart
.mjt code token reset
.mjt code set <enabled|binary|host|port|workspace|token> <value>

.mjt minecraft profile runtime <profile> <host|proot>
.mjt minecraft profile set <profile> runtime <host|proot>
```

## Validation checklist

```text
.mjt proot test
.mjt minecraft profile runtime smp proot
.mjt minecraft start smp
.mjt minecraft status smp
.mjt code start
.mjt code show
.mjt code stop
```

## Not included yet

- Automatic OpenVSCode release downloader/updater.
- Panel buttons/API endpoints for Code Server.
- SSH forced command shell into PRootFS.
- Game protocol multiplexer / UDP gateway.

Those should remain separate phases so a code-server change cannot destabilize Minecraft, Cloudflare Tunnel, or the gateway.
