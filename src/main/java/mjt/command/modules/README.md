# Command Modules

New command groups should be added here instead of growing `CommandCenter.java`.

Recommended mapping:

- `MinecraftCommands.java` for start/stop/status/logs/send/profile.
- `MinecraftInstallerCommands.java` for Paper/Purpur/Velocity installers.
- `PanelCommands.java` for panel start/stop/install/update/token.
- `SshCommands.java` for ssh/sftp.
- `GatewayCommands.java` for gateway routes.
- `WebsiteCommands.java` for HTTP sites and guest sites.
- `CloudflareCommands.java` for DDNS/tunnel.
- `SystemCommands.java` for version, downloads, pwd, timeout.

Stage 1 keeps `CommandDispatcher.java` as a compatibility bridge.
Move one command group at a time into this package.
