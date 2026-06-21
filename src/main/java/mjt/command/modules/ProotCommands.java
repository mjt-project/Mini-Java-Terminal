package main.java.mjt.command.modules;

import java.io.IOException;

import main.java.mjt.command.CommandModule;
import main.java.mjt.command.CommandRequest;
import main.java.mjt.services.proot.ProotService;

/**
 * Modular PRoot command group. It is included now so the legacy dispatcher can
 * migrate to CommandRouter without moving this feature a second time.
 */
public final class ProotCommands implements CommandModule {
    @Override
    public String name() {
        return "proot";
    }

    @Override
    public boolean supports(CommandRequest request) {
        return request != null && request.startsWith("proot");
    }

    @Override
    public boolean handle(CommandRequest request) throws IOException {
        ProotService proot = request.context().prootService();
        String raw = request.raw();

        if (raw.equalsIgnoreCase("proot")
                || raw.equalsIgnoreCase("proot show")
                || raw.equalsIgnoreCase("proot status")) {
            proot.showConfig();
            return true;
        }
        if (raw.equalsIgnoreCase("proot init")) {
            proot.initialize();
            return true;
        }
        if (raw.equalsIgnoreCase("proot test")) {
            proot.test();
            return true;
        }
        if (raw.equalsIgnoreCase("proot enter")) {
            proot.enterGuestRouting();
            return true;
        }
        if (raw.equalsIgnoreCase("proot leave")) {
            proot.leaveGuestRouting();
            return true;
        }
        if (raw.toLowerCase().startsWith("proot set ")) {
            String pair = raw.substring("proot set ".length()).trim();
            int space = pair.indexOf(' ');
            if (space <= 0) {
                System.out.println("Usage: .mjt proot set <key> <value>");
                return true;
            }
            proot.setConfig(pair.substring(0, space), pair.substring(space + 1));
            return true;
        }
        if (raw.toLowerCase().startsWith("proot exec ")) {
            proot.execute(raw.substring("proot exec ".length()));
            return true;
        }
        return false;
    }
}
