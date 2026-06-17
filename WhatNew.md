# CHANGE.md

## Mini Java Terminal v2.5.6

### Gateway HTTP / HTTPS Protocol Router

This release improves Gateway routing so HTTP, HTTPS, SSH/SFTP, and Minecraft can share the same public Gateway port more cleanly.

Gateway is still only a router / forwarder. It does not serve website files directly.

### Fixed

- Fixed Gateway protocol priority so HTTP traffic is detected before the Minecraft default TCP route.
- Added TLS ClientHello detection for HTTPS traffic.
- Added HTTP backend error responses with valid HTTP status output instead of raw TCP text.
- Reduced browser `ERR_INVALID_HTTP_RESPONSE` cases when the HTTP backend is unavailable.

### Added

- Added local HTTPS Service.
- Added HTTPS Gateway route.
- Added self-signed certificate generation command using `keytool`.
- Added HTTPS configuration commands.

### Routing Behavior

Gateway now routes traffic in this order:

```text
HTTP request       -> 127.0.0.1:8080
TLS/HTTPS request  -> 127.0.0.1:8443
SSH banner         -> 127.0.0.1:2022
Unknown TCP        -> gateway.tcp.default, for example Minecraft 127.0.0.1:25565
```

This means the same public Gateway port can be used for:

```text
http://domain:PORT
https://domain:PORT
Minecraft domain:PORT
ssh domain -p PORT
```

### New HTTPS Commands

```text
.mjt https show
.mjt https start
.mjt https stop
.mjt https status
.mjt https set enabled true
.mjt https set host 127.0.0.1
.mjt https set port 8443
.mjt https set root /home/container/www
.mjt https set keystore /home/container/mjt-config/https.p12
.mjt https set password change-me
.mjt https set cn localhost
.mjt https cert self-signed
```

### Recommended Setup

HTTP local service:

```text
.mjt http set host 127.0.0.1
.mjt http set port 8080
.mjt http start
```

HTTPS local service:

```text
.mjt https set enabled true
.mjt https set host 127.0.0.1
.mjt https set port 8443
.mjt https set password change-me
.mjt https cert self-signed
.mjt https start
```

Gateway public router:

```text
.mjt gateway set gateway.public.host 0.0.0.0
.mjt gateway set gateway.public.port auto
.mjt gateway set gateway.route.http.enabled true
.mjt gateway set gateway.route.http.host 127.0.0.1
.mjt gateway set gateway.route.http.port 8080
.mjt gateway set gateway.route.https.enabled true
.mjt gateway set gateway.route.https.host 127.0.0.1
.mjt gateway set gateway.route.https.port 8443
.mjt gateway route add mc 127.0.0.1 25565
.mjt gateway default mc
```

### Browser Note

If you use `.mjt https cert self-signed`, browsers will show a certificate warning. This is expected for self-signed certificates.

For production HTTPS, use a trusted certificate or terminate HTTPS at an outer proxy / tunnel.

### Release File

```text
mjt-gateway-https-router-v2.5.6.zip
```
