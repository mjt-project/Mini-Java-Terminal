# MJT PRoot Phase 3 — Generic Guest Service Manager

Phase 3 adds one managed process model for runtime workloads that belong in the PRoot guest: Node.js applications, Java applications, Python applications, OpenVSCode-like processes, and other long-running commands.

## Boundary

```text
Host container
  /home/container/MJT             MJT Core: config, logs, PRoot binary, rootfs
  /home/container/server          Host workspace data

MJT PRootFS
  /                              apt, dpkg, Node, Java, Python and user packages
  /workspace                      bind mount of /home/container/server
```

Every generic service uses `runtime=proot`. There is intentionally no `host` runtime option.

The manager verifies that its working directory is inside `proot.workspace`, normally `/home/container/server`, then starts the command through `ProotService.buildGuestCommand(...)`. The service process receives:

```text
MJT_SERVICE_ID
MJT_SERVICE_TYPE
MJT_SERVICE_HOST
MJT_SERVICE_PORT
MJT_SERVICE_WORKSPACE
MJT_SERVICE_WORKDIR
```

The manager only accepts loopback metadata (`127.0.0.1`, `::1`, or `localhost`). An application command must still obey those settings, for example by using `--host 127.0.0.1` or the `MJT_SERVICE_HOST` variable.

## Commands

```text
.mjt service list
.mjt service show <id>
.mjt service add <id> <type> <workdir> <command...>
.mjt service remove <id>
.mjt service start <id|all>
.mjt service stop <id|all>
.mjt service restart <id|all>
.mjt service logs <id> [lines]
.mjt service set <id> <key> <value>
.mjt service publish <id>
.mjt service unpublish <id>
```

Valid service keys:

```text
enabled, type, runtime, workdir, command, host, port,
public-hostname, public-enabled
```

`runtime` is fixed to `proot`.

## Node example

```text
.mjt service add api node /home/container/server/api npm run start
.mjt service set api port 3001
.mjt service set api public-hostname api.example.com
.mjt service start api
.mjt service logs api 100
```

Your Node application should bind to loopback. For a custom command:

```text
.mjt service set api command npm run start -- --host "$MJT_SERVICE_HOST" --port "$MJT_SERVICE_PORT"
```

## Java example

```text
.mjt service add app java /home/container/server/app java -jar app.jar --server.address=127.0.0.1 --server.port=8088
.mjt service set app port 8088
.mjt service start app
```

## Cloudflare publication

A service can create/update local Tunnel route metadata after it has a loopback port and valid DNS hostname:

```text
.mjt service set api public-hostname api.example.com
.mjt service publish api
```

This calls MJT's existing tunnel route API with a generated route name such as `svc-api` and origin `http://127.0.0.1:3001`.

- **Tunnel config mode:** run `.mjt tunnel config generate`, then restart the tunnel.
- **Tunnel token mode:** MJT stores local route metadata, but Cloudflare's Public Hostname still must be configured in the Zero Trust dashboard; use the displayed loopback origin.

`publish` does **not** start or restart cloudflared automatically.

## Operational safety

- Services are **not auto-started** when MJT restarts. This avoids executing an old persisted command unexpectedly.
- `remove` only removes the MJT registry configuration. It never deletes workspace data.
- `stop`/MJT shutdown destroys the launched PRoot process tree after a brief graceful stop window.
- The service manager handles HTTP origins only. Raw TCP/UDP game traffic remains a Gateway/MJT Connect concern and is not exposed through `service publish`.
