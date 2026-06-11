## What's Changed

### New

* Added Gateway Service for routing multiple protocols through a single public TCP port.
* Added built-in HTTP service directly inside `server.jar`, allowing a web page to run without opening an additional HTTP port.
* Added SSH/SFTP gateway proxy support, allowing external SSH/SFTP connections through the public port while forwarding internally to a local SSH service.
* Added manual TCP route configuration through `terminal-state.properties`.
* Added support for dynamic TCP route management, including adding, removing, enabling, disabling, and selecting a default TCP route.
* Added Gateway command support:

  * `gateway-help`
  * `gateway-show`
  * `gateway-set <key> <value>`
  * `gateway-route-add <name> <host> <port>`
  * `gateway-route-remove <name>`
  * `gateway-route-enable <name>`
  * `gateway-route-disable <name>`
  * `gateway-default <route|close>`
* Added support for reading the public gateway port from the hosting environment variable `SERVER_PORT`.
* Added dynamic Gateway configuration reload for new incoming connections.
* Added a cleaner routing structure where HTTP, SSH/SFTP, and manual TCP routes are handled separately.
* Added experimental support for routing Minecraft Java traffic to a local backend service through the Gateway.

### Fixed

* Fixed SSH/SFTP connections closing immediately after successful password authentication.
* Fixed TCP proxy timeout behavior by resetting socket timeout after protocol detection.
* Fixed SSH/SFTP conflict with the public port by allowing SSH/SFTP to run internally on a local port such as `127.0.0.1:2022`.
* Fixed the issue where Gateway protocol detection could consume the first bytes of a connection without forwarding them to the backend.
* Fixed unstable SSH proxy behavior when the SSH client waits for the server banner first.
* Improved handling of unknown protocols through the Gateway.
* Improved Gateway logging for HTTP, SSH/SFTP, TCP proxy, unknown protocols, and backend connection errors.
* Improved compatibility with hosting environments that provide only one public port.
* Improved project structure for future support of additional internal services.

### Changed

* Gateway now owns the public TCP port instead of the SSH service.
* SSH/SFTP is now expected to run as an internal service and be accessed through the Gateway.
* HTTP no longer requires a separate web server or additional port.
* Manual TCP routes are now controlled through Gateway configuration instead of hardcoded Java variables.
* Default TCP route can be changed without rebuilding the project.
* The project is moving toward a single-port multi-service runtime architecture.

### Gateway Configuration Example

```properties
gateway.http.enabled=true

gateway.ssh.enabled=true
gateway.ssh.host=127.0.0.1
gateway.ssh.port=2022

gateway.tcp.enabled=true
gateway.tcp.default=mc
gateway.tcp.routes=mc

gateway.tcp.mc.enabled=true
gateway.tcp.mc.host=127.0.0.1
gateway.tcp.mc.port=25565
```

### Gateway Command Example

```bash
gateway-route-add mc 127.0.0.1 25565
gateway-default mc
gateway-show
```

To disable the default TCP backend:

```bash
gateway-default close
```

To remove a route:

```bash
gateway-route-remove mc
```

### Note

* This is an experimental development build.
* This version focuses on single-port Gateway routing for restricted hosting environments.
* HTTP and SSH/SFTP have been tested through the same public port.
* TCP backend routing is experimental and may require further testing depending on the target service.
* Minecraft Java routing is still experimental and should be tested carefully.
* UDP routing is not included in this version yet.
* This build is intended for controlled testing environments and may not be stable for production use.

### Dev by @SimonNg-code

**Full Changelog**: https://github.com/SimonNg-code/Mini-Java-Terminal/commits/2.3.22