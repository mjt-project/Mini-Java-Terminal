package main.java.mjt.command;

import java.io.IOException;

/**
 * A command group such as panel, minecraft, ssh, gateway, or website.
 */
public interface CommandModule {
    String name();

    boolean supports(CommandRequest request);

    boolean handle(CommandRequest request) throws IOException;
}
