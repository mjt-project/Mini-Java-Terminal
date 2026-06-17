# MJT v2.5.0

This release adds the new KeepAliveBot feature for Minecraft servers.

KeepAliveBot is an offline-mode Minecraft bot that can join the server as a normal player and help keep the server active when enabled. This is useful for hosting environments that support renew / keep-alive behavior.

Main new feature:

* Added KeepAliveBot service
* Added offline-mode bot login
* Added bot reconnect loop
* Added bot configuration commands
* Added bot status display
* Added support for using the bot with local Minecraft server address

Recommended for:

* Minecraft servers with online-mode=false
* Hosts that allow renew / keep-alive behavior
* Servers that need one persistent bot player to stay active

New commands:
.mjt bot show
.mjt bot status
.mjt bot start
.mjt bot stop
.mjt bot set enabled true
.mjt bot set host 127.0.0.1
.mjt bot set port 25565
.mjt bot set username MJT_Renew
.mjt bot set reconnect 30

Basic setup:
.mjt bot set enabled true
.mjt bot set host 127.0.0.1
.mjt bot set port 25565
.mjt bot set username MJT_Renew
.mjt bot set reconnect 60
.mjt bot start

Expected behavior:

* Bot connects to 127.0.0.1:25565
* Bot joins as MJT_Renew
* If disconnected, bot waits and reconnects automatically
* Bot can be stopped manually with .mjt bot stop

Minecraft server requirement:
online-mode=false

Recommended server config:
server-ip=127.0.0.1
server-port=25565
online-mode=false

If the bot name is already online:

* The server may return duplicate_login
* Change the bot username or stop the old MJT process first

Example:
.mjt bot set username MJT_Renew2
.mjt bot stop
.mjt bot start

Release title:
v2.5.0 - KeepAliveBot Release
