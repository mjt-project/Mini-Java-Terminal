# CHANGE.md

## Version: `3.0.0-SNAPSHOT+1`

Release type: development snapshot  
Release focus: service layout, server workspace, guest quick tunnel, cloudflared binary installer, and help restructuring.

---

## Summary

This snapshot reorganizes Mini Java Terminal into a cleaner service manager architecture.

Main goals:

```text
1. Separate MJT runtime/config from user/server data.
2. Move website content into /home/container/server.
3. Add guest website mode using Cloudflare Quick Tunnel.
4. Make guest mode token-free.
5. Add automatic cloudflared binary installer.
6. Split commands into clearer service groups.
7. Prepare for stable custom domain support later.
```

---

## Version Rename

Target version:

```text
3.0.0-SNAPSHOT+1
```

Recommended `BuildInfo`:

```java
public static final String VERSION = "3.0.0-SNAPSHOT+1";
public static final String RELEASE = "Server Workspace, Guest Quick Tunnel & System Downloader";
```

---

## Added

### 1. Server Workspace Layout

Added separation between MJT control data and user/server data.

New data root:

```text
/home/container/server
```

Website root:

```text
/home/container/server/website/www
```

Minecraft root:

```text
/home/container/server/Minecraft
```

Created default folders:

```text
/home/container/server/website/www/main
/home/container/server/website/www/docs
/home/container/server/website/www/panel
/home/container/server/website/www/guest
/home/container/server/Minecraft/Velocity
/home/container/server/Minecraft/smp
/home/container/server/Minecraft/lobby
```

---

### 2. MJT System Folder

Added system-level folder for downloads and internal tasks:

```text
/home/container/MJT/system
/home/container/MJT/system/downloads
/home/container/MJT/system/tasks
```

Cloudflared download folder:

```text
/home/container/MJT/system/downloads/cloudflared
```

---

### 3. Cloudflared Auto Installer

Added automatic installer for Cloudflare Tunnel binary.

New commands:

```text
.mjt system install cloudflared
.mjt system download cloudflared
.mjt system cloudflared check
.mjt system cloudflared show
.mjt cloudflared install
.mjt tunnel binary install
```

Installer behavior:

```text
1. Detect OS.
2. Detect CPU architecture.
3. Select correct cloudflared binary.
4. Download into MJT/system/downloads/cloudflared.
5. chmod +x on Linux.
6. Run cloudflared --version.
7. Save working path into tunnel.cloudflared.path.
```

---

### 4. Guest Website Service

Added guest website manager.

New service:

```text
main.java.mjt.services.cloudflare.tunnel.GuestWebsiteService
```

New commands:

```text
.mjt website guest create
.mjt website guest list
.mjt website guest show <guest-id>
.mjt website guest stop <guest-id>
.mjt website guest restart <guest-id>
.mjt website guest remove <guest-id>
```

Guest creation flow:

```text
1. Generate guest ID.
2. Create guest folder.
3. Create default index.html.
4. Pick a free local HTTP port.
5. Register the guest as an HTTP site.
6. Start local HTTP service for the guest.
7. Start Cloudflare Quick Tunnel.
8. Parse trycloudflare.com URL.
9. Save public URL into state.
```

---

### 5. Cloudflare Quick Tunnel Support

Added quick tunnel process management.

Quick tunnel command:

```text
cloudflared tunnel --url http://127.0.0.1:<port>
```

Guest Quick Tunnel does not require:

```text
Cloudflare token
Cloudflare account
custom domain
DNS setup
```

Added URL parser for:

```text
https://*.trycloudflare.com
```

Saved result into:

```text
website.guest.<guest-id>.publicUrl
```

---

### 6. Multi Quick Tunnel Tracking

Added separate process tracking for guest quick tunnels.

Concept:

```text
guest-id -> cloudflared process
```

Stop/restart support:

```text
.mjt website guest stop <guest-id>
.mjt website guest restart <guest-id>
```

---

### 7. Website Command Aliases

Added user-friendly website commands:

```text
.mjt website list
.mjt website show <site>
.mjt website add <name> <host> <port> <root>
.mjt website remove <site>
.mjt website start <site>
.mjt website stop <site>
.mjt website restart <site>
.mjt website set <site> <key> <value>
```

Legacy HTTP commands remain available:

```text
.mjt http site list
.mjt http site add ...
.mjt http site start ...
```

---

### 8. Help Index Design

Main help should become an index rather than a long full manual.

Main help:

```text
.mjt help
```

Topic help:

```text
.mjt help website
.mjt help guest
.mjt help tunnel
.mjt help gateway
.mjt help minecraft
.mjt help ssh
.mjt help bot
.mjt help cloudflare-ddns
.mjt help shell
.mjt help download
```

Recommended future package:

```text
main.java.mjt.help
├── HelpCenter.java
├── HelpTopic.java
├── SystemHelp.java
├── WebsiteHelp.java
├── GuestHelp.java
├── TunnelHelp.java
├── GatewayHelp.java
├── MinecraftHelp.java
├── SshHelp.java
├── BotHelp.java
├── CloudflareDdnsHelp.java
└── ShellHelp.java
```

---

### 9. Shutdown Cleanup

Shutdown should stop:

```text
cloudflared global tunnel
cloudflared guest quick tunnels
KeepAlive bot
SSH/SFTP server
Gateway router
HTTPS service
HTTP service
```

---

## Changed

### 1. Default Website Root

Changed from:

```text
/home/container/MJT/www/main
/home/container/MJT/www/docs
/home/container/MJT/www/panel
```

to:

```text
/home/container/server/website/www/main
/home/container/server/website/www/docs
/home/container/server/website/www/panel
```

---

### 2. Guest Public Access Model

Changed guest public access from planned system-domain model to Cloudflare Quick Tunnel.

Old idea:

```text
guest-a8f31.mjt-domain.com
```

New model:

```text
https://random-name.trycloudflare.com
```

Reason:

```text
MJT should not require its own managed domain for guest preview sites.
Guest should work without DNS or token.
```

---

### 3. Tunnel Default Behavior

Recommended default:

```properties
tunnel.mode=quick
tunnel.enabled=false
tunnel.autoStart=false
```

Token mode is reserved for named tunnels and custom domain workflows.

---

### 4. Gateway Role

Gateway is no longer the recommended public path for web.

Recommended defaults:

```properties
gateway.route.http.enabled=false
gateway.route.https.enabled=false
gateway.tcp.default=close
```

Gateway should focus on:

```text
TCP routing
Minecraft fallback route
SSH/SFTP proxy if needed
manual TCP forwarding
```

---

### 5. Startup Output

Startup should show:

```text
Config dir  : /home/container/MJT
Server dir  : /home/container/server
Web root    : /home/container/server/website/www
```

Recommended quick commands:

```text
.mjt help
.mjt website list
.mjt website guest create
.mjt system install cloudflared
.mjt tunnel show
.mjt gateway show
.mjt minecraft start
.mjt ssh show
.mjt exit
```

---

## Fixed

### 1. Guest Asking For Token

Fixed expected behavior:

```text
Guest Quick Tunnel must not ask for tunnel.token.
```

If a guest creates a website and `cloudflared` is missing, MJT should show:

```text
cloudflared is not installed.
Run: .mjt system install cloudflared
```

It must not show:

```text
Missing tunnel.token
```

---

### 2. Old MJT/www Default Path

Corrected intended default from `MJT/www` to:

```text
/home/container/server/website/www
```

---

### 3. Cloudflared Path Handling

`cloudflared` path should be configurable:

```properties
tunnel.cloudflared.path=/home/container/MJT/system/downloads/cloudflared/cloudflared
```

Command:

```text
.mjt tunnel set cloudflared <path>
```

Installer should update this automatically after successful check.

---

### 4. Token Mode Confusion

Clarified tunnel modes:

```text
quick  -> no token, temporary trycloudflare.com URL
token  -> requires token, named tunnel
config -> requires named tunnel config
```

---

## Configuration Files

### App

```text
MJT/core/app.properties
```

```properties
app.name=Mini Java Terminal
app.version=3.0.0-SNAPSHOT+1
app.command.prefix=.
```

### HTTP

```text
MJT/services/http/http.properties
MJT/services/http/sites/sites.properties
```

```properties
http.enabled=true
http.sites=main
http.host=127.0.0.1
http.port=8081
http.root=/home/container/server/website/www/main
http.autoHttps=true
http.site.main.enabled=true
http.site.main.host=127.0.0.1
http.site.main.port=8081
http.site.main.root=/home/container/server/website/www/main
```

### Guest Websites

Stored in:

```text
MJT/services/http/sites/sites.properties
```

```properties
website.guest.ids=guest-a8f31
website.guest.rootBase=/home/container/server/website/www/guest
website.guest.nextPort=8091
website.guest.guest-a8f31.root=/home/container/server/website/www/guest/guest-a8f31/main
website.guest.guest-a8f31.local=http://127.0.0.1:8091
website.guest.guest-a8f31.publicUrl=https://example.trycloudflare.com
website.guest.guest-a8f31.status=running
website.guest.guest-a8f31.tunnel=running
```

### Cloudflare Tunnel

```text
MJT/services/cloudflare/tunnel/tunnel.properties
```

```properties
tunnel.enabled=false
tunnel.provider=cloudflare
tunnel.mode=quick
tunnel.autoStart=false
tunnel.cloudflared.path=cloudflared
tunnel.local.url=http://127.0.0.1:8081
tunnel.publicUrl=
tunnel.token=
tunnel.configFile=/home/container/MJT/services/cloudflare/tunnel/config.yml
```

### Gateway

```text
MJT/services/gateway/gateway.properties
MJT/services/tcp/tcp-routes.properties
```

```properties
gateway.enabled=true
gateway.public.host=0.0.0.0
gateway.public.port=auto
gateway.route.http.enabled=false
gateway.route.https.enabled=false
gateway.ssh.enabled=true
gateway.tcp.enabled=true
gateway.tcp.default=close
gateway.tcp.routes=
```

### SSH/SFTP

```text
MJT/services/ssh/ssh.properties
```

```properties
ssh.enabled=false
ssh.host=127.0.0.1
ssh.port=2022
ssh.username=admin
ssh.password=
ssh.root=/home/container
ssh.terminal.mode=basic
```

### Minecraft

```text
MJT/services/minecraft/minecraft.properties
```

```properties
minecraft.start-command=bash start-minecraft.sh
```

### System Downloader

Recommended path:

```text
/home/container/MJT/system/downloads/cloudflared
```

Recommended keys:

```properties
system.cloudflared.path=/home/container/MJT/system/downloads/cloudflared/cloudflared
tunnel.cloudflared.path=/home/container/MJT/system/downloads/cloudflared/cloudflared
```

---

## Migration Notes

Older configs may still contain:

```text
MJT/www/main
tunnel.mode=token
gateway.route.http.enabled=true
```

Recommended migration:

```text
1. Move website content to /home/container/server/website/www.
2. Set tunnel.mode=quick for guest/default behavior.
3. Set gateway.route.http.enabled=false unless explicitly needed.
4. Run .mjt system install cloudflared.
5. Test .mjt website guest create.
```

---

## Known Limitations

```text
1. trycloudflare.com URL is temporary.
2. Guest URL may change after restart.
3. Quick Tunnel is for preview/demo, not stable production hosting.
4. Stable custom domain mode still needs domain verification and named tunnel design.
5. User/workspace permission layer is not finalized yet.
6. Help system should be moved fully into a dedicated help package.
```

---

## Recommended Test Plan

```text
.mjt --version
.mjt help
.mjt help download
.mjt system install cloudflared
.mjt system cloudflared check
.mjt website list
.mjt website guest create
.mjt website guest list
.mjt website guest show <guest-id>
.mjt website guest restart <guest-id>
.mjt website guest remove <guest-id>
```

Expected guest behavior:

```text
- Local HTTP site starts.
- cloudflared quick tunnel starts.
- No token is requested.
- trycloudflare.com URL is parsed and saved.
```
