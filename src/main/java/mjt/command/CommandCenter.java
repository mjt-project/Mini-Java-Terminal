package main.java.mjt.command;

import java.io.IOException;

/**
 * Thin command facade for Mini Java Terminal.
 *
 * This class should stay small. It owns no feature command logic; it only
 * forwards user input into the command dispatcher. When adding new command
 * groups, add a module or move the logic out of CommandDispatcher instead of
 * growing this file again.
 */
public class CommandCenter {
    private final CommandDispatcher dispatcher;

    public CommandCenter(CommandContext context) {
        this.dispatcher = new CommandDispatcher(context);
    }

    public void handle(String command) throws IOException {
        dispatcher.handle(command);
    }
}
