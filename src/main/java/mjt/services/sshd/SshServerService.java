package terminal.services.sshd;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;

import terminal.command.CommandCenter;
import terminal.system.CommandGuard;
import terminal.system.LogService;
import terminal.system.StateStore;

public class SshServerService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private final StateStore stateStore;
    private final LogService logService;
    private final CommandGuard commandGuard;

    private CommandCenter commandCenter;
    private SshServer sshServer;
    private volatile boolean running = false;

    public SshServerService(
            StateStore stateStore,
            LogService logService,
            CommandGuard commandGuard
    ) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.commandGuard = commandGuard;
    }

    
    public void setCommandCenter(CommandCenter commandCenter) {
        this.commandCenter = commandCenter;
    }

    public void setConfig(String key, String value) throws IOException {
        String realKey = normalizeKey(key);

        if (realKey == null) {
            System.out.println(RED + "[SSH] Invalid key: " + key + RESET);
            printSetHelp();
            return;
        }

        if (realKey.equals("ssh.port")) {
            try {
                int port = Integer.parseInt(value.trim());

                if (port <= 0 || port > 65535) {
                    System.out.println(RED + "[SSH] Invalid port." + RESET);
                    return;
                }

            } catch (NumberFormatException e) {
                System.out.println(RED + "[SSH] Port must be a number." + RESET);
                return;
            }
        }

        // Terminal mode normalization check
        if (realKey.equals("ssh.terminal.mode")) {
            String normalizedMode = normalizeTerminalMode(value);
                
            if (normalizedMode == null) {
                System.out.println(RED + "[SSH] Invalid terminal mode: " + value + RESET);
                System.out.println(YELLOW + "Valid modes: real-tty, basic" + RESET);
                return;
            }
            value = normalizedMode;
        }

        stateStore.set(realKey, value);

        if (realKey.equals("ssh.password")) {
            System.out.println(GREEN + "[SSH] Saved password: " + stateStore.maskSecret(value) + RESET);
        } else {
            System.out.println(GREEN + "[SSH] Saved " + realKey + " = " + value + RESET);
        }

        logService.write("[SSH SET] " + realKey + "\n");
    }

    public void showConfig() {
        System.out.println(CYAN + "[SSH CONFIG]" + RESET);
        System.out.println("ssh.host     = " + stateStore.get("ssh.host", "0.0.0.0"));
        System.out.println("ssh.port     = " + stateStore.get("ssh.port", "(empty)"));
        System.out.println("ssh.username = " + stateStore.get("ssh.username", "(empty)"));
        System.out.println("ssh.password = " + stateStore.maskSecret(stateStore.get("ssh.password", "")));
        System.out.println("ssh.root     = " + stateStore.get("ssh.root", "(empty)"));
        System.out.println("ssh.terminal.mode = " + stateStore.get("ssh.terminal.mode", "basic"));
    }

    public void start() {
        if (running) {
            System.out.println(YELLOW + "[SSH] Server is already running." + RESET);
            return;
        }

        try {
            validateConfig();

            String terminalMode = stateStore.get("ssh.terminal.mode", "basic").trim().toLowerCase();
            String host = stateStore.get("ssh.host", "0.0.0.0").trim();
            int port = stateStore.getInt("ssh.port", 0);
            String username = stateStore.get("ssh.username").trim();
            String password = stateStore.get("ssh.password").trim();
            Path root = Paths.get(stateStore.get("ssh.root").trim()).toAbsolutePath().normalize();
            Files.createDirectories(root);

            Path hostKey = Paths.get("ssh-hostkey.ser").toAbsolutePath().normalize();

            sshServer = SshServer.setUpDefaultServer();
            sshServer.setHost(host);
            sshServer.setPort(port);

            sshServer.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));

            sshServer.setPasswordAuthenticator((inputUser, inputPassword, session) ->
                    username.equals(inputUser) && password.equals(inputPassword)
            );

            // SFTP runs on the same SSH server
            sshServer.setSubsystemFactories(Collections.singletonList(
                    new SftpSubsystemFactory.Builder().build()
            ));

            // SFTP root folder
            sshServer.setFileSystemFactory(new VirtualFileSystemFactory(root));

            // SSH shell
            // Linux/container: real terminal with PTY helper fallback
            if (terminalMode.equals("basic")) {
                sshServer.setShellFactory(channel -> new SimpleSshShell(root));
            } 
            else if (terminalMode.equals("real-tty")) {
                sshServer.setShellFactory(channel -> new RealTerminalShell(root));
            }

            // Windows host: use simple line-based shell because Windows cmd.exe does not provide a real PTY here.
            if (isWindowsHost()) {
                sshServer.setShellFactory(channel -> new SimpleSshShell(root));
            }

            sshServer.start();
            running = true;

            System.out.println(GREEN + "[SSH] Server started." + RESET);
            System.out.println("Terminal SSH Mode : " + terminalMode);
            System.out.println("Host : " + host);
            System.out.println("Port : " + port);
            System.out.println("User : " + username);
            System.out.println("Root : " + root);
            System.out.println();
            System.out.println(CYAN + "SSH:" + RESET);
            System.out.println("ssh " + username + "@<domain-or-ip> -p " + port);
            System.out.println(CYAN + "SFTP:" + RESET);
            System.out.println("sftp -P " + port + " " + username + "@<domain-or-ip>");

            logService.write("[SSH START] host=" + host + " port=" + port + " root=" + root + "\n");

        } catch (Exception e) {
            System.out.println(RED + "[SSH] Start error: " + e.getMessage() + RESET);

            try {
                logService.write("[SSH START ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    public void stop() {
        if (!running || sshServer == null) {
            System.out.println(YELLOW + "[SSH] Server is not running." + RESET);
            return;
        }

        try {
            sshServer.stop();
            running = false;

            System.out.println(GREEN + "[SSH] Server stopped." + RESET);
            logService.write("[SSH STOP]\n");

        } catch (IOException e) {
            System.out.println(RED + "[SSH] Stop error: " + e.getMessage() + RESET);
        }
    }

    public void status() {
        System.out.println(CYAN + "[SSH STATUS]" + RESET);
        System.out.println("Running : " + running);
        System.out.println("Port    : " + stateStore.get("ssh.port", "none"));
        System.out.println("User    : " + stateStore.get("ssh.username", "none"));
        System.out.println("Root    : " + stateStore.get("ssh.root", "none"));
    }

    private void validateConfig() throws IOException {
        if (stateStore.get("ssh.port").isBlank()) {
            throw new IOException("Missing ssh.port. Use: ssh-set port <port>");
        }

        if (stateStore.get("ssh.username").isBlank()) {
            throw new IOException("Missing ssh.username. Use: ssh-set user <username>");
        }

        if (stateStore.get("ssh.password").isBlank()) {
            throw new IOException("Missing ssh.password. Use: ssh-set pass <password>");
        }

        if (stateStore.get("ssh.root").isBlank()) {
            throw new IOException("Missing ssh.root. Use: ssh-set root <folder>");
        }
    }

    private String normalizeKey(String key) {
        String lower = key.toLowerCase().trim();

        switch (lower) {
            case "host":
            case "bind":
            case "ssh.host":
            case "sftp.host":
                return "ssh.host";

            case "port":
            case "ssh.port":
            case "sftp.port":
                return "ssh.port";

            case "user":
            case "username":
            case "ssh.username":
            case "sftp.username":
                return "ssh.username";

            case "pass":
            case "password":
            case "ssh.password":
            case "sftp.password":
                return "ssh.password";

            case "root":
            case "folder":
            case "path":
            case "ssh.root":
            case "sftp.root":
                return "ssh.root";

            case "mode":
            case "terminal-mode":
            case "terminal_mode":
            case "terminal.mode":
            case "ssh.mode":
            case "ssh.terminal.mode":
            case "ssh.terminal-mode":
            case "ssh.terminal_mode":
                return "ssh.terminal.mode";

            default:
                return null;
        }
    }

    private void printSetHelp() {
        System.out.println(YELLOW + "Valid keys:" + RESET);
        System.out.println("ssh-set host 0.0.0.0");
        System.out.println("ssh-set port <public_port>");
        System.out.println("ssh-set user <username>");
        System.out.println("ssh-set pass <password>");
        System.out.println("ssh-set root /home/container/uploads");
        System.out.println("ssh-set mode real-tty");
        System.out.println("ssh-set mode basic");
    }

    private boolean isWindowsHost() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win");
    }

    private class SimpleSshShell implements Command {
        private final Path root;
        private Path currentDir;

        private InputStream inputStream;
        private OutputStream outputStream;
        private OutputStream errorStream;
        private ExitCallback exitCallback;

        private Thread worker;

        SimpleSshShell(Path root) {
            this.root = root.toAbsolutePath().normalize();
            this.currentDir = this.root;
        }

        private void write(PrintWriter out, String text) {
            out.print(text);
            out.flush();
        }       

        private void writeLine(PrintWriter out, String text) {
            out.print(text + "\r\n");
            out.flush();
        }

        private void writeError(PrintWriter err, String text) {
            err.print(text + "\r\n");
            err.flush();
        }

        @Override
        public void setInputStream(InputStream inputStream) {
            this.inputStream = inputStream;
        }

        @Override
        public void setOutputStream(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        @Override
        public void setErrorStream(OutputStream errorStream) {
            this.errorStream = errorStream;
        }

        @Override
        public void setExitCallback(ExitCallback exitCallback) {
            this.exitCallback = exitCallback;
        }

        @Override
        public void start(ChannelSession channel, Environment environment) {
            worker = new Thread(this::runShell, "mini-java-terminal-ssh-shell");
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (worker != null) {
                worker.interrupt();
            }
        }

        private void runShell() {
            try (
                    PrintWriter out = new PrintWriter(
                            new OutputStreamWriter(outputStream, StandardCharsets.UTF_8),
                            true
                    );
                    PrintWriter err = new PrintWriter(
                            new OutputStreamWriter(errorStream, StandardCharsets.UTF_8),
                            true
                    )
            ) {

                String prefix = stateStore.get("app.command.prefix", ".").trim();
                writeLine(out, "Mini Java Terminal SSH");
                writeLine(out, "Type '" + prefix + "help' for MJT commands.");
                writeLine(out, "Type 'exit' to close.");
            
                String line;
            
                while (true) {
                    write(out, "mjt:" + getPromptPath() + "$ ");
                
                    line = readLineWithEcho(out);
                
                    if (line == null) {
                        break;
                    }
                
                    line = line.trim();
                
                    if (line.isEmpty()) {
                        continue;
                    }

                    logService.write("[SSH INPUT] " + line + "\n");

                    if (line.equalsIgnoreCase("exit")
                            || line.equalsIgnoreCase("logout")
                            || line.equalsIgnoreCase("quit")) {
                        writeLine(out, "Bye.");
                        break;
                    }

                    if (line.equalsIgnoreCase("pwd")) {
                        writeLine(out, currentDir.toString());
                        continue;
                    }

                    if (line.equalsIgnoreCase("ls") || line.equalsIgnoreCase("dir")) {
                        printDirectoryList(out, false);
                        continue;
                    }

                    if (line.equalsIgnoreCase("ll") || line.equalsIgnoreCase("ls -l")) {
                        printDirectoryList(out, true);
                        continue;
                    }

                    if (line.equalsIgnoreCase("clear") || line.equalsIgnoreCase("cls")) {
                        out.print("\033[H\033[2J");
                        out.flush();
                        continue;
                    }

                    if (line.equalsIgnoreCase("cd")) {
                        currentDir = root;
                        continue;
                    }

                    if (line.startsWith("cd ")) {
                        handleCd(line.substring(3).trim(), out, err);
                        continue;
                    }

                    /*
                     * MJT Command
                     *
                     * Example:
                     * .help
                     * .gateway-show
                     * .ssh-show
                     */
                    if (!prefix.isBlank() && line.startsWith(prefix)) {
                    
                        String output =
                                runMjtCommandAndCaptureOutput(line);
                    
                        for (String outputLine : output.split("\\R")) {
                            writeLine(out, outputLine);
                        }
                    
                        continue;
                    }
                    
                    // SSH is closer to a real terminal session than the web panel.
                    // Do not block sudo/nano/vim/top here.
                    // Keep the web panel protection in the panel command handler instead.
                    if (commandGuard.isBlocked(line)) {
                        logService.write("[SSH GUARD BYPASSED] " + line + "\n");
                    }
                    
                    runSystemCommand(line, out, err);
                }

                if (exitCallback != null) {
                    exitCallback.onExit(0);
                }

            } catch (Exception e) {
                try {
                    logService.write("[SSH SHELL ERROR] " + e.getMessage() + "\n");
                } catch (IOException ignored) {
                }

                if (exitCallback != null) {
                    exitCallback.onExit(1, e.getMessage());
                }
            }
        }

        private String readLineWithEcho(PrintWriter out) throws IOException {
            StringBuilder builder = new StringBuilder();
        
            while (true) {
                int value = inputStream.read();
            
                if (value == -1) {
                    if (builder.length() == 0) {
                        return null;
                    }
                
                    return builder.toString();
                }
            
                char ch = (char) value;
            
                // Enter
                if (ch == '\r' || ch == '\n') {
                    writeLine(out, "");
                    return builder.toString();
                }
            
                // Ctrl + D
                if (ch == 4) {
                    return null;
                }
            
                // Ctrl + C
                if (ch == 3) {
                    writeLine(out, "^C");
                    return "";
                }
            
                // Backspace
                if (ch == 8 || ch == 127) {
                    if (builder.length() > 0) {
                        builder.deleteCharAt(builder.length() - 1);
                        write(out, "\b \b");
                    }
                
                    continue;
                }
            
                // Skip simple escape keys like arrow keys
                if (ch == 27) {
                    continue;
                }
            
                builder.append(ch);
                write(out, String.valueOf(ch));
            }
        }

        private void printSshHelp(
                PrintWriter out,
                String prefix
        ) {
            writeLine(out, "SSH Built-in Commands:");
            writeLine(out, "  pwd");
            writeLine(out, "  ls");
            writeLine(out, "  ll");
            writeLine(out, "  cd");
            writeLine(out, "  clear");
            writeLine(out, "  exit");
            writeLine(out, "");
            writeLine(out, "MJT Commands:");
            writeLine(out, "  " + prefix + "help");
        }   

        private void handleCd(String target, PrintWriter out, PrintWriter err) {
            try {
                Path next;

                if (target.equals("~") || target.equals("/")) {
                    next = root;
                } else {
                    next = currentDir.resolve(target).normalize();
                }

                if (!next.startsWith(root)) {
                    writeError(err, "Access denied: outside ssh.root");
                    return;
                }

                if (!Files.exists(next) || !Files.isDirectory(next)) {
                    writeError(err, "Directory not found: " + target);
                    return;
                }

                currentDir = next;

            } catch (Exception e) {
                writeError(err, "cd error: " + e.getMessage());
            }
        }

        private void runSystemCommand(String command, PrintWriter out, PrintWriter err) {
            try {
                ProcessBuilder processBuilder = createProcessBuilder(command);
                processBuilder.directory(currentDir.toFile());
                processBuilder.redirectErrorStream(true);

                Process process = processBuilder.start();

                try (BufferedReader processReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
                )) {
                    String outputLine;

                    while ((outputLine = processReader.readLine()) != null) {
                        writeLine(out, outputLine);
                        logService.write("[SSH OUTPUT] " + outputLine + "\n");
                    }
                }

                int exitCode = process.waitFor();

                if (exitCode != 0) {
                    writeError(err, "Exit code: " + exitCode);
                }

                logService.write("[SSH EXIT CODE] " + exitCode + "\n");

            } catch (Exception e) {
                writeError(err, "Command error: " + e.getMessage());

                try {
                    logService.write("[SSH COMMAND ERROR] " + e.getMessage() + "\n");
                } catch (IOException ignored) {
                }
            }
        }

        private ProcessBuilder createProcessBuilder(String command) {
            String osName = System.getProperty("os.name").toLowerCase();

            if (osName.contains("win")) {
                return new ProcessBuilder("cmd.exe", "/c", command);
            }

            return new ProcessBuilder("bash", "-lc", command);
        }

        private String getPromptPath() {
            Path relative = root.relativize(currentDir);        

            if (relative.toString().isBlank()) {
                return "/";
            }       

            return "/" + relative.toString().replace("\\", "/");
        }       

        private void printDirectoryList(PrintWriter out, boolean longFormat) {
            try {
                if (!Files.exists(currentDir) || !Files.isDirectory(currentDir)) {
                    writeLine(out, "Current path is not a directory.");
                    return;
                }       

                try (var stream = Files.list(currentDir)) {
                    stream
                            .sorted((a, b) -> {
                                boolean ad = Files.isDirectory(a);
                                boolean bd = Files.isDirectory(b);      

                                if (ad != bd) {
                                    return ad ? -1 : 1;
                                }       

                                return a.getFileName().toString()
                                        .compareToIgnoreCase(b.getFileName().toString());
                            })
                            .forEach(path -> {
                                try {
                                    String name = path.getFileName().toString();        

                                    if (Files.isDirectory(path)) {
                                        name = name + "/";
                                    }       

                                    if (longFormat) {
                                        String type = Files.isDirectory(path) ? "d" : "-";
                                        long size = Files.isDirectory(path) ? 0 : Files.size(path);     

                                        writeLine(out, String.format(
                                                "%s %10d  %s",
                                                type,
                                                size,
                                                name
                                        ));
                                    } else {
                                        writeLine(out, name);
                                    }       

                                } catch (IOException e) {
                                    writeLine(out, path.getFileName() + "  [error]");
                                }
                            });
                }       

            } catch (IOException e) {
                writeLine(out, "ls error: " + e.getMessage());
            }
        }

        private synchronized String runMjtCommandAndCaptureOutput(String command) {
            if (commandCenter == null) {
                return "[SSH] CommandCenter has not been attached to SshServerService.";
            }
        
            PrintStream originalOut = System.out;
            PrintStream originalErr = System.err;
        
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            PrintStream capture = new PrintStream(buffer, true, StandardCharsets.UTF_8);
        
            try {
                System.setOut(capture);
                System.setErr(capture);
            
                commandCenter.handle(command);
            
            } catch (Exception e) {
                e.printStackTrace(capture);
            
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        
            return buffer.toString(StandardCharsets.UTF_8);
        }

    }

    private class RealTerminalShell implements Command {
    private final Path root;

    private InputStream inputStream;
    private OutputStream outputStream;
    private OutputStream errorStream;
    private ExitCallback exitCallback;

    private Process process;
    private Thread worker;

    RealTerminalShell(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void setInputStream(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    @Override
    public void setOutputStream(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    @Override
    public void setErrorStream(OutputStream errorStream) {
        this.errorStream = errorStream;
    }

    @Override
    public void setExitCallback(ExitCallback exitCallback) {
        this.exitCallback = exitCallback;
    }

    @Override
    public void start(ChannelSession channel, Environment environment) {
        worker = new Thread(this::runRealTerminal, "mini-java-terminal-real-ssh-shell");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void destroy(ChannelSession channel) {
        closeQuietly(inputStream);
        closeQuietly(outputStream);
        closeQuietly(errorStream);

        try {
            if (process != null && process.isAlive()) {
                process.destroy();

                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        } catch (Exception ignored) {
        }

        if (worker != null) {
            worker.interrupt();
        }
    }

    private void runRealTerminal() {
        try {
            Files.createDirectories(root);

            writeRaw("Mini Java Terminal - Real SSH Terminal\r\n");
            writeRaw("Type 'exit' to close this SSH session.\r\n");

            PtyMethod method = detectPtyMethod();

            writeRaw("PTY mode: " + method.name + "\r\n");

            if (method.type == PtyType.BASIC_SHELL) {
                writeRaw("\r\n[WARN] No PTY helper found.\r\n");
                writeRaw("[WARN] Falling back to basic shell mode.\r\n");
                writeRaw("[WARN] su/nano/vim/top may not work correctly without PTY.\r\n");
            }

            writeRaw("\r\n");

            ProcessBuilder processBuilder = method.builder;
            processBuilder.directory(root.toFile());
            processBuilder.redirectErrorStream(true);

            processBuilder.environment().put("TERM", "xterm-256color");
            processBuilder.environment().put("HOME", root.toString());
            processBuilder.environment().put("PWD", root.toString());
            processBuilder.environment().put("MJT_SSH_ROOT", root.toString());
            processBuilder.environment().put("COLUMNS", "120");
            processBuilder.environment().put("LINES", "32");

            process = processBuilder.start();

            Thread inputPump = new Thread(
                    () -> pump(inputStream, process.getOutputStream()),
                    "mjt-real-ssh-input-pump"
            );

            Thread outputPump = new Thread(
                    () -> pump(process.getInputStream(), outputStream),
                    "mjt-real-ssh-output-pump"
            );

            inputPump.setDaemon(true);
            outputPump.setDaemon(true);

            inputPump.start();
            outputPump.start();

            int exitCode = process.waitFor();

            try {
                outputPump.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            logService.write("[SSH REAL TERMINAL EXIT] " + exitCode + "\n");

            if (exitCallback != null) {
                exitCallback.onExit(exitCode);
            }

        } catch (Exception e) {
            try {
                logService.write("[SSH REAL TERMINAL ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }

            try {
                writeRaw("\r\nSSH terminal error: " + e.getMessage() + "\r\n");
            } catch (IOException ignored) {
            }

            if (exitCallback != null) {
                exitCallback.onExit(1, e.getMessage());
            }
        }
    }

    private PtyMethod detectPtyMethod() {
        String osName = System.getProperty("os.name").toLowerCase();

        if (osName.contains("win")) {
            return new PtyMethod(
                    PtyType.BASIC_SHELL,
                    "windows-cmd",
                    new ProcessBuilder("cmd.exe")
            );
        }

        String shell = chooseShell();

        Path script = firstExecutable(
                "/usr/bin/script",
                "/bin/script"
        );

        if (script != null) {
            return new PtyMethod(
                    PtyType.SCRIPT,
                    "script",
                    new ProcessBuilder(
                            script.toString(),
                            "-qfc",
                            "exec " + shell + " -i",
                            "/dev/null"
                    )
            );
        }

        Path busybox = firstExecutable(
                "/usr/bin/busybox",
                "/bin/busybox",
                "/sbin/busybox"
        );

        if (busybox != null && busyboxScriptSupportsCommand(busybox)) {
            return new PtyMethod(
                    PtyType.BUSYBOX_SCRIPT,
                    "busybox-script",
                    new ProcessBuilder(
                            busybox.toString(),
                            "script",
                            "-q",
                            "-c",
                            "exec " + shell + " -i",
                            "/dev/null"
                    )
            );
        }

        Path socat = firstExecutable(
                "/usr/bin/socat",
                "/bin/socat",
                "/usr/local/bin/socat"
        );

        if (socat != null) {
            return new PtyMethod(
                    PtyType.SOCAT,
                    "socat",
                    new ProcessBuilder(
                            socat.toString(),
                            "-",
                            "EXEC:" + shell + " -i,pty,stderr,setsid,sigint,sane"
                    )
            );
        }

        return new PtyMethod(
                PtyType.BASIC_SHELL,
                "basic-shell",
                new ProcessBuilder(shell, "-i")
        );
    }

    private String chooseShell() {
        if (Files.isExecutable(Paths.get("/bin/bash"))) {
            return "/bin/bash";
        }

        if (Files.isExecutable(Paths.get("/usr/bin/bash"))) {
            return "/usr/bin/bash";
        }

        if (Files.isExecutable(Paths.get("/bin/ash"))) {
            return "/bin/ash";
        }

        return "/bin/sh";
    }

    private Path firstExecutable(String... paths) {
        for (String item : paths) {
            Path path = Paths.get(item);

            if (Files.isExecutable(path)) {
                return path;
            }
        }

        return null;
    }

    private boolean busyboxScriptSupportsCommand(Path busybox) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    busybox.toString(),
                    "script",
                    "--help"
            );

            processBuilder.redirectErrorStream(true);

            Process testProcess = processBuilder.start();

            StringBuilder helpText = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(testProcess.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;

                while ((line = reader.readLine()) != null) {
                    helpText.append(line).append('\n');
                }
            }

            testProcess.waitFor();

            String lowerHelp = helpText.toString().toLowerCase();

            return lowerHelp.contains("-c")
                    || lowerHelp.contains("command")
                    || lowerHelp.contains("script");

        } catch (Exception e) {
            return false;
        }
    }

    private void pump(InputStream source, OutputStream target) {
        byte[] buffer = new byte[8192];

        try {
            int length;

            while ((length = source.read(buffer)) != -1) {
                target.write(buffer, 0, length);
                target.flush();
            }

        } catch (IOException ignored) {
        }
    }

    private void writeRaw(String text) throws IOException {
        outputStream.write(text.getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
    }

    private void closeQuietly(InputStream stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(OutputStream stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
        }
    }

    private enum PtyType {
        SCRIPT,
        BUSYBOX_SCRIPT,
        SOCAT,
        BASIC_SHELL
    }

    private class PtyMethod {
        private final PtyType type;
        private final String name;
        private final ProcessBuilder builder;

        private PtyMethod(PtyType type, String name, ProcessBuilder builder) {
            this.type = type;
            this.name = name;
            this.builder = builder;
        }
    }
}

    private String normalizeTerminalMode(String value) {
        if (value == null) {
            return null;
        }
    
        String mode = value.trim().toLowerCase();
    
        switch (mode) {
            case "basic":
            case "simple":
            case "simple-terminal":
            case "simple_terminal":
            case "line":
            case "line-based":
                return "basic";
        
            case "real":
            case "real-tty":
            case "real_tty":
            case "real-terminal":
            case "real_terminal":
            case "pty":
                return "real-tty";
        
            default:
                return null;
        }
    }
}