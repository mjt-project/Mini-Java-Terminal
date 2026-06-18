package main.java.mjt.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Small ordered router for modular command handlers.
 */
public final class CommandRouter {
    private final List<CommandModule> modules = new ArrayList<>();

    public CommandRouter register(CommandModule module) {
        if (module != null) {
            modules.add(module);
        }
        return this;
    }

    public List<CommandModule> modules() {
        return Collections.unmodifiableList(modules);
    }

    public boolean route(CommandRequest request) throws IOException {
        for (CommandModule module : modules) {
            if (module.supports(request) && module.handle(request)) {
                return true;
            }
        }
        return false;
    }
}
