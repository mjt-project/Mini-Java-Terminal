## What's Changed

### New

* Released version `2.3.27`.
* Added experimental Gateway Service for single-port TCP routing.
* Added Gateway startup information showing:

  * Public TCP
  * HTTP status
  * SSH/SFTP target
  * TCP status
  * TCP default route
  * TCP route list
* Added Gateway command group to the main `help` output.
* Added dedicated `gateway-help` command.
* Added Gateway core commands:

  * `gateway-help`
  * `gateway-show`
  * `gateway-set <key> <value>`
  * `gateway-default <route|close>`
* Added manual TCP route commands:

  * `gateway-route-add <name> <host> <port>`
  * `gateway-route-remove <name>`
  * `gateway-route-enable <name>`
  * `gateway-route-disable <name>`
* Added Gateway HTTP config commands:

  * `gateway-set gateway.http.enabled true`
  * `gateway-set gateway.http.enabled false`
  * `gateway-set gateway.http.root /home/container/www`
  * `gateway-set gateway.http.index index.html`
  * `gateway-set gateway.http.spa true`
  * `gateway-set gateway.http.spa false`
* Added Gateway SSH/SFTP proxy config commands:

  * `gateway-set gateway.ssh.enabled true`
  * `gateway-set gateway.ssh.enabled false`
  * `gateway-set gateway.ssh.host 127.0.0.1`
  * `gateway-set gateway.ssh.port 2022`
* Added clearer SFTP compatibility alias section in `help`.

### Changed

* Improved the main `help` display.
* Reorganized `help` output into clearer sections:

  * Terminal Runtime
  * Cloudflare DDNS
  * SSH / SFTP Server
  * SFTP Compatibility Aliases
  * Gateway
  * Safety
  * Commands not recommended
* Moved detailed Gateway usage into `gateway-help`.
* Improved Gateway command examples for Minecraft Java and Velocity-style local TCP routes.
* Improved command readability by aligning command names and descriptions.
* Kept `shutdown-terminal` as the current official stop command for this release.
* Kept `terminal-state.properties` as the current runtime config file for this release.

### Fixed

* Fixed confusing `help` command output by separating command groups.
* Fixed Gateway command documentation being mixed into the main help too densely.
* Fixed Gateway help readability by separating:

  * Gateway Core
  * HTTP Static File Service
  * SSH / SFTP Gateway Proxy
  * Manual TCP Routes
  * Examples
* Fixed unclear TCP fallback usage by documenting `gateway-default close`.
* Fixed unclear route examples by adding direct examples for:

  * `mc`
  * `velocity`
* Fix windwos ssh connect dose not show correctly.

### Gateway Commands

```text
gateway-help
gateway-show
gateway-set <key> <value>
gateway-default <route|close>
gateway-route-add <name> <host> <port>
gateway-route-remove <name>
gateway-route-enable <name>
gateway-route-disable <name>
```

### Gateway Help Sections

```text
Gateway Core
HTTP Static File Service
SSH / SFTP Gateway Proxy
Manual TCP Routes
Examples
```

### Manual TCP Route Example

```text
gateway-route-add mc 127.0.0.1 25565
gateway-default mc
gateway-show
```

To close TCP fallback:

```text
gateway-default close
```

### Note

* This is an experimental development release.
* Gateway Service is intended for controlled and authorized testing environments.
* Manual TCP backend routing is experimental and may require additional testing depending on the target service.
* Minecraft Java routing is experimental.
* This release focuses on Gateway commands and clearer help output.
* This release does not include the larger config-folder refactor yet.
