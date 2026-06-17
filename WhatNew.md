# CHANGE.md

## Mini Java Terminal v2.5.1

### Fixed

* Fixed KeepAliveBot duplicate login behavior.
* Prevented the bot from creating a second session while the first session is still active or connecting.
* Improved reconnect loop stability.
* Reduced repeated disconnect / reconnect spam.
* Improved bot session cleanup after disconnect.
* Improved behavior when using the same offline-mode bot username.

### Notes
KeepAliveBot is designed for Minecraft servers using:
