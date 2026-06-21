# MJT Phase 4 — Guest Service Lifecycle

Phase 4 extends the Phase 3 PRoot service manager. It does **not** create a generic host service runner. App commands still execute in PRootFS while `/home/container/server` is mounted into the guest workspace.

## Default behaviour

A new service is deliberately conservative:

```text
autostart         false
restart-policy    never
health-enabled    false
health-action     report
```

Turn features on only after verifying the app's command, local bind address and port.

## Example: Node API with health check

```text
.mjt service add api node /home/container/server/api npm run start
.mjt service set api port 3001
.mjt service set api health-enabled true
.mjt service set api health-path /health
.mjt service set api health-interval 30
.mjt service health api
.mjt service start api
```

The application must bind the loopback origin provided by the environment:

```text
MJT_SERVICE_HOST=127.0.0.1
MJT_SERVICE_PORT=3001
```

For example, a Node app can use `process.env.MJT_SERVICE_HOST` and `process.env.MJT_SERVICE_PORT` rather than hard-coding a public bind address.

## Restart policy

```text
.mjt service set api restart-policy on-failure
.mjt service set api restart-max 3
.mjt service set api restart-delay 5
```

Valid policy values:

- `never`: never restart automatically.
- `on-failure`: restart only after a non-zero exit code.
- `always`: restart after every exit.

`restart-max` caps the consecutive automatic restart streak. A process that has stayed up for five minutes resets this streak before a later exit.

## Health action

A health check uses ordinary HTTP from the Core to the app's loopback origin.

```text
.mjt service set api health-enabled true
.mjt service set api health-path /health
.mjt service set api health-timeout 3000
.mjt service set api health-failures 3
.mjt service set api health-action report
```

Use `report` first. To restart a process only after its configured failure threshold:

```text
.mjt service set api health-action restart
```

Manual check:

```text
.mjt service health api
```

## Start after MJT boot

A service is never started from persisted configuration unless explicitly opted in:

```text
.mjt service set api autostart true
```

MJT then attempts to start it only after its Core services and shutdown handler are ready. It does not automatically publish it through Cloudflare Tunnel.

## Port preflight

Before a service with `port > 0` starts, MJT verifies that no other local TCP listener already accepts connections at that loopback host and port. This prevents a managed app from silently starting against an origin owned by a different process.

`port=0` disables the preflight and health/publish support. Use it only when the service genuinely chooses an ephemeral port and does not need an MJT Tunnel route.

## Publishing remains explicit

After the service is healthy:

```text
.mjt service set api public-hostname api.example.com
.mjt service publish api
```

In Tunnel config mode, regenerate the ingress config and restart Cloudflare Tunnel. In token mode, define the matching Public Hostname in Cloudflare Zero Trust; MJT stores the matching origin metadata but never exposes the service directly on the host public port.
