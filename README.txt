Mini Java Terminal v2.6.2
=========================

Release: Quick Tunnel Guest UX & Help Index

Main test commands:

  .mjt --version
  .mjt help
  .mjt help guest
  .mjt help tunnel
  .mjt website list
  .mjt website guest create
  .mjt website guest show <id>

Guest Quick Tunnel does NOT require a Cloudflare token.

For guest preview, MJT starts:

  cloudflared tunnel --url http://127.0.0.1:<guest-port>

Then MJT parses and saves the public URL:

  https://xxxxx.trycloudflare.com

If cloudflared is not in PATH, set it manually:

  .mjt tunnel set cloudflared /home/container/MJT/bin/cloudflared

Build:

  mvn -U clean package


v2.6.3 Cloudflared Auto Installer
=================================

Install cloudflared automatically:

  .mjt system install cloudflared

MJT will detect OS/CPU architecture, download the matching Cloudflare binary, store it in:

  /home/container/MJT/system/downloads/cloudflared/

Then MJT runs:

  cloudflared --version

If the version check succeeds, MJT saves tunnel.cloudflared.path automatically.
