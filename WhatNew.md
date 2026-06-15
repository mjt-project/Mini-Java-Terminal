MJT Router Mode v2.4.2
======================

Replace these files in your project, then rebuild the JAR:

src/main/java/terminal/Main.java
src/main/java/terminal/command/CommandCenter.java
src/main/java/terminal/command/CommandContext.java
src/main/java/terminal/system/TargetProcessService.java
src/main/java/terminal/system/StateStore.java

Expected startup banner:
Mini Java Terminal v2.4.2 ROUTER-MODE

Strict command rule:
.mjt <command>       = MJT internal command
.command <command>   = Linux shell command
no prefix            = Minecraft console input only when Minecraft target is running and mode is MINECRAFT

Start flow:
.mjt ssh start
.mjt gateway help
.mjt minecraft start

Then Minecraft console:
say hello
list
stop

Shell while Minecraft is running:
.command ls
.command pwd
.command curl https://example.com

Do NOT use:
bash start-minecraft.sh
.command bash start-minecraft.sh

Use managed target instead:
.mjt minecraft start
