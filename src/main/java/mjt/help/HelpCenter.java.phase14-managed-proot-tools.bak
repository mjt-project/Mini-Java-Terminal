package main.java.mjt.help;

public final class HelpCenter {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private HelpCenter() {
    }

    public static void printIndex() {
        title("Mini Java Terminal Help Index");
        section("Core");
        cmd(".mjt help system", "Runtime, cd, pwd, timeout, version");
        cmd(".mjt help shell", "Safe shell routing and .command usage");
        cmd(".mjt help download", "Install required binaries such as cloudflared");
        cmd(".mjt help proot", "PRoot guest runtime, APT rootfs and command routing");
        cmd(".mjt help minecraft", "Managed Minecraft target commands and server profiles");
        cmd(".mjt help panel", "Lightweight Minecraft control panel");

        section("Website");
        cmd(".mjt help website", "Local website / HTTP site manager");
        cmd(".mjt help guest", "Guest website + Cloudflare Quick Tunnel");
        cmd(".mjt help tunnel", "cloudflared binary, quick/token/config modes");
        cmd(".mjt help code", "OpenVSCode Server in the PRootFS");
        cmd(".mjt help service", "Generic PRoot service manager (Node, Java, Python)");

        section("Services");
        cmd(".mjt help gateway", "TCP gateway / Minecraft fallback / SSH proxy");
        cmd(".mjt help ssh", "SSH/SFTP server");
        cmd(".mjt help bot", "KeepAlive bot");
        cmd(".mjt help cloudflare-ddns", "Cloudflare DNS update service");

        System.out.println();
        System.out.println(YELLOW + "Tip:" + RESET + " Detailed aliases still work: .mjt website help, .mjt tunnel help, .mjt gateway help");
        System.out.println();
    }

    public static void printTopic(String topic) {
        String clean = topic == null ? "" : topic.trim().toLowerCase();

        switch (clean) {
            case "system":
            case "core":
                title("System Help");
                cmd(".mjt --version", "Show MJT version");
                cmd(".mjt mode", "Show route mode and target status");
                cmd(".mjt pwd", "Show current directory");
                cmd(".mjt cd <folder>", "Change MJT working directory");
                cmd(".mjt public-ip", "Check public IPv4");
                cmd(".mjt timeout <seconds>", "Set shell command timeout, 0 = unlimited");
                return;

            case "proot":
            case "guest-runtime":
                title("PRoot Guest Runtime Help");
                System.out.println("PRoot keeps apt, dpkg and guest packages under MJT/system/rootfs.");
                cmd(".mjt proot init", "Create MJT runtime directories and policy-rc.d after rootfs bootstrap");
                cmd(".mjt proot show", "Show PRoot binary/rootfs/workspace readiness");
                cmd(".mjt proot test", "Verify id, apt and guest rootfs execution");
                cmd(".mjt proot exec apt update", "Run one command inside guest PRootFS");
                cmd(".mjt proot exec apt install nano htop", "Install guest-only packages without host sudo");
                cmd(".mjt proot enter", "Route .command calls into the guest rootfs");
                cmd(".mjt proot leave", "Return .command calls to the host shell");
                cmd(".mjt proot set rootfs <path>", "Set guest rootfs location");
                return;

            case "download":
            case "installer":
            case "cloudflared-install":
                title("System Download Help");
                System.out.println("MJT can download helper binaries into separate task folders under MJT/system/downloads/.");
                cmd(".mjt system install cloudflared", "Auto-detect OS/CPU, download cloudflared, chmod +x, verify --version");
                cmd(".mjt system cloudflared check", "Check configured/cloudflared binary with --version");
                cmd(".mjt system cloudflared show", "Show download path, last asset, version, status");
                cmd(".mjt tunnel set cloudflared <path>", "Manually set cloudflared binary path");
                return;

            case "service":
            case "services":
            case "guest-service":
                title("Guest Service Manager Help");
                System.out.println("All managed services run inside PRootFS and must bind to loopback.");
                cmd(".mjt service list", "List registered PRoot services");
                cmd(".mjt service add api node /home/container/server/api npm run start", "Register a Node service");
                cmd(".mjt service add app java /home/container/server/app java -jar app.jar", "Register a Java service");
                cmd(".mjt service set api port 3001", "Store the expected local port");
                cmd(".mjt service set api public-hostname api.example.com", "Set Cloudflare hostname metadata");
                cmd(".mjt service start|stop|restart <id>", "Control one guest service");
                cmd(".mjt service logs <id> [lines]", "Show in-memory process logs");
                cmd(".mjt service health <id>", "Run one immediate HTTP health check");
                cmd(".mjt service set api restart-policy on-failure", "Restart only after a non-zero exit");
                cmd(".mjt service set api restart-max 3", "Cap consecutive automatic restarts");
                cmd(".mjt service set api health-enabled true", "Enable periodic loopback health checks");
                cmd(".mjt service set api health-action restart", "Restart after the configured failed-health threshold");
                cmd(".mjt service set api autostart true", "Start this service after the MJT Core starts");
                cmd(".mjt service publish <id>", "Create/update Tunnel route metadata for loopback HTTP origin");
                cmd(".mjt service unpublish <id>", "Remove the managed Tunnel route metadata");
                System.out.println(YELLOW + "Security:" + RESET + " service commands are intentionally not auto-started after MJT restart.");
                return;

            case "code":
            case "openvscode":
                title("OpenVSCode Server Help");
                cmd(".mjt code show", "Show managed OpenVSCode configuration/status");
                cmd(".mjt code set binary /opt/openvscode-server/current/bin/openvscode-server", "Set guest executable path");
                cmd(".mjt code set port 3000", "Set loopback port");
                cmd(".mjt code set workspace /home/container/server", "Set project folder inside PRoot workspace");
                cmd(".mjt code token reset", "Rotate the browser connection token while stopped");
                cmd(".mjt code start", "Run OpenVSCode Server in PRootFS");
                cmd(".mjt code stop", "Stop managed OpenVSCode Server");
                return;

            case "shell":
            case "command":
                title("Shell Help");
                cmd(".command <shell-command>", "Run Linux shell command explicitly");
                cmd(".command terminal", "Switch no-prefix input back to terminal mode");
                cmd(".command minecraft", "Route no-prefix input to running Minecraft target");
                System.out.println(YELLOW + "Note:" + RESET + " Panel console blocks su/sudo/nano/vim/top/htop because they need a real TTY.");
                return;

            case "website":
            case "http":
                title("Website Help");
                cmd(".mjt website list", "List local websites");
                cmd(".mjt website show <name>", "Show website config");
                cmd(".mjt website add <name> <host> <port> <root>", "Add local website");
                cmd(".mjt website start <name|all>", "Start website");
                cmd(".mjt website stop <name|all>", "Stop website");
                cmd(".mjt website restart <name|all>", "Restart website");
                cmd(".mjt website set <name> <key> <value>", "Set enabled/host/port/root/index/spa");
                return;

            case "guest":
            case "quick":
            case "quick-tunnel":
                title("Guest Quick Tunnel Help");
                System.out.println("Guest websites use Cloudflare Quick Tunnel and DO NOT need a token.");
                cmd(".mjt website guest create", "Create guest site + start cloudflared quick tunnel");
                cmd(".mjt website guest list", "List guest sites");
                cmd(".mjt website guest show <id>", "Show local/public URL");
                cmd(".mjt website guest stop <id>", "Stop HTTP site and quick tunnel");
                cmd(".mjt website guest restart <id>", "Restart guest site and get a new trycloudflare URL");
                cmd(".mjt website guest remove <id>", "Remove guest config, keep files for safety");
                return;

            case "tunnel":
            case "cloudflared":
                title("Tunnel Help");
                cmd(".mjt tunnel show", "Show tunnel status/config");
                cmd(".mjt system install cloudflared", "Download cloudflared automatically if missing");
                cmd(".mjt tunnel set cloudflared <path>", "Set cloudflared binary path manually");
                cmd(".mjt tunnel set mode quick", "Manual Quick Tunnel mode, no token");
                cmd(".mjt tunnel set local http://127.0.0.1:8081", "Set manual quick origin");
                cmd(".mjt tunnel start", "Start global quick/token/config tunnel");
                cmd(".mjt tunnel stop", "Stop global tunnel");
                cmd(".mjt tunnel set mode token", "Named tunnel token mode, needs token");
                cmd(".mjt tunnel set token <token>", "Set named tunnel token");
                return;

            case "gateway":
                title("Gateway Help");
                cmd(".mjt gateway show", "Show gateway config");
                cmd(".mjt gateway start", "Start TCP gateway");
                cmd(".mjt gateway stop", "Stop TCP gateway");
                cmd(".mjt gateway route add mc 127.0.0.1 25565", "Add Minecraft TCP route");
                cmd(".mjt gateway default mc", "Use Minecraft as unknown TCP fallback");
                cmd(".mjt gateway default close", "Close unknown TCP fallback");
                return;

            case "minecraft":
            case "mc":
                title("Minecraft Help");
                cmd(".mjt minecraft profile list", "List Velocity/SMP/Lobby profiles");
                cmd(".mjt minecraft profile show <name>", "Show profile workdir/command");
                cmd(".mjt minecraft profile use <name>", "Set active profile");
                cmd(".mjt minecraft profile add <name> <workdir> <command>", "Add profile");
                cmd(".mjt minecraft profile runtime <name> proot", "Run a stopped profile with guest Java/PRootFS");
                cmd(".mjt minecraft profile runtime <name> host", "Return a stopped profile to host runtime");
                cmd(".mjt minecraft installer show", "Show installer sources and defaults");
                cmd(".mjt minecraft install velocity velocity latest", "Download/install latest Velocity profile");
                cmd(".mjt minecraft install paper smp latest --accept-eula", "Download/install latest Paper profile");
                cmd(".mjt minecraft install purpur lobby latest --accept-eula", "Download/install latest Purpur profile");
                cmd(".mjt minecraft start", "Start active profile in its own workdir");
                cmd(".mjt minecraft start <profile>", "Start selected profile without stopping others");
                cmd(".mjt minecraft stop <profile>", "Send stop command to one profile");
                cmd(".mjt minecraft kill <profile>", "Force kill one profile");
                cmd(".mjt minecraft status", "Show all Minecraft process statuses");
                cmd(".mjt minecraft send <profile> <cmd>", "Send command to a specific server");
                cmd(".mjt minecraft attach <profile>", "Route no-prefix console input to one running profile");
                cmd(".mjt minecraft logs <profile>", "Show recent logs for one profile");
                return;

            case "panel":
            case "control-panel":
                title("MJT Control Panel Help");
                cmd(".mjt panel show", "Show legacy static panel config and token status");
                cmd(".mjt panel api show", "Show v1 loopback control API status");
                cmd(".mjt panel api set port 9091", "Set v1 API listener port");
                cmd(".mjt panel api start", "Start v1 control API");
                cmd(".mjt panel api stop", "Stop v1 control API");
                cmd(".mjt panel set enabled true", "Enable legacy local panel service");
                cmd(".mjt panel set host 127.0.0.1", "Bind panel locally");
                cmd(".mjt panel set port 9090", "Set panel port");
                cmd(".mjt panel install", "Download frontend from GitHub URL in core/app.properties");
                cmd(".mjt panel update", "Download and replace frontend static files");
                cmd(".mjt panel frontend show", "Show frontend URL, install path, installed version");
                cmd(".mjt panel frontend set url <url>", "Save frontend zip URL into core/app.properties");
                cmd(".mjt panel frontend set tag 0.0.1", "Use a GitHub tag source zip URL");
                cmd("POST /api/minecraft/install", "Panel API: install velocity/paper/purpur into a profile");
                cmd(".mjt panel token reset", "Generate a new panel token");
                cmd(".mjt panel start", "Start web panel");
                cmd(".mjt panel stop", "Stop web panel");
                System.out.println(YELLOW + "Security:" + RESET + " keep panel on 127.0.0.1 unless intentionally published through a protected tunnel.");
                return;

            case "ssh":
            case "sftp":
                title("SSH/SFTP Help");
                cmd(".mjt ssh show", "Show config");
                cmd(".mjt ssh set host 127.0.0.1", "Set bind host");
                cmd(".mjt ssh set port 2022", "Set SSH/SFTP port");
                cmd(".mjt ssh set user admin", "Set username");
                cmd(".mjt ssh set pass <password>", "Set password");
                cmd(".mjt ssh set mode real-tty", "Use real terminal mode if supported");
                cmd(".mjt ssh start", "Start SSH/SFTP");
                return;

            case "bot":
                title("KeepAlive Bot Help");
                cmd(".mjt bot show", "Show bot status/config");
                cmd(".mjt bot set enabled true", "Enable bot");
                cmd(".mjt bot set host 127.0.0.1", "Set MC server host");
                cmd(".mjt bot set port 25565", "Set MC server port");
                cmd(".mjt bot start", "Start bot loop");
                cmd(".mjt bot stop", "Stop bot loop");
                return;

            case "cloudflare-ddns":
            case "ddns":
                title("Cloudflare DDNS Help");
                cmd(".mjt cloudflare show", "Show DDNS config");
                cmd(".mjt cloudflare set token <token>", "Set Cloudflare API token");
                cmd(".mjt cloudflare set zone <zone_id>", "Set Zone ID");
                cmd(".mjt cloudflare set name <domain>", "Set DNS record name");
                cmd(".mjt cloudflare ddns once", "Update once");
                cmd(".mjt cloudflare ddns start", "Start DDNS loop");
                return;

            default:
                System.out.println(RED + "Unknown help topic: " + topic + RESET);
                System.out.println(YELLOW + "Use: .mjt help" + RESET);
        }
    }

    private static void title(String title) {
        System.out.println(GREEN + "==================================================" + RESET);
        System.out.println(GREEN + title + RESET);
        System.out.println(GREEN + "==================================================" + RESET);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println(YELLOW + "== " + title + " ==" + RESET);
    }

    private static void cmd(String command, String description) {
        System.out.printf("  " + CYAN + "%-42s" + RESET + " %s%n", command, description);
    }
}
