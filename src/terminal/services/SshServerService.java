package terminal.services;

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
            System.out.println(RED + "[SSH] Key không hợp lệ: " + key + RESET);
            printSetHelp();
            return;
        }

        if (realKey.equals("ssh.port")) {
            try {
                int port = Integer.parseInt(value.trim());

                if (port <= 0 || port > 65535) {
                    System.out.println(RED + "[SSH] Port không hợp lệ." + RESET);
                    return;
                }

            } catch (NumberFormatException e) {
                System.out.println(RED + "[SSH] Port phải là số." + RESET);
                return;
            }
        }

        stateStore.set(realKey, value);

        if (realKey.equals("ssh.password")) {
            System.out.println(GREEN + "[SSH] Đã lưu password: " + stateStore.maskSecret(value) + RESET);
        } else {
            System.out.println(GREEN + "[SSH] Đã lưu " + realKey + " = " + value + RESET);
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
    }

    public void start() {
        if (running) {
            System.out.println(YELLOW + "[SSH] Server đang chạy rồi." + RESET);
            return;
        }

        try {
            validateConfig();

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

            // SFTP chạy chung SSH server
            sshServer.setSubsystemFactories(Collections.singletonList(
                    new SftpSubsystemFactory.Builder().build()
            ));

            // SFTP root folder
            sshServer.setFileSystemFactory(new VirtualFileSystemFactory(root));

            // SSH interactive shell đơn giản
            sshServer.setShellFactory(channel -> new SimpleSshShell(root));

            sshServer.start();
            running = true;

            System.out.println(GREEN + "[SSH] Server started." + RESET);
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
            System.out.println(RED + "[SSH] Start lỗi: " + e.getMessage() + RESET);

            try {
                logService.write("[SSH START ERROR] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    public void stop() {
        if (!running || sshServer == null) {
            System.out.println(YELLOW + "[SSH] Server chưa chạy." + RESET);
            return;
        }

        try {
            sshServer.stop();
            running = false;

            System.out.println(GREEN + "[SSH] Server stopped." + RESET);
            logService.write("[SSH STOP]\n");

        } catch (IOException e) {
            System.out.println(RED + "[SSH] Stop lỗi: " + e.getMessage() + RESET);
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
            throw new IOException("Thiếu ssh.port. Dùng: ssh-set port <port>");
        }

        if (stateStore.get("ssh.username").isBlank()) {
            throw new IOException("Thiếu ssh.username. Dùng: ssh-set user <username>");
        }

        if (stateStore.get("ssh.password").isBlank()) {
            throw new IOException("Thiếu ssh.password. Dùng: ssh-set pass <password>");
        }

        if (stateStore.get("ssh.root").isBlank()) {
            throw new IOException("Thiếu ssh.root. Dùng: ssh-set root <folder>");
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

            default:
                return null;
        }
    }

    private void printSetHelp() {
        System.out.println(YELLOW + "Các key hợp lệ:" + RESET);
        System.out.println("ssh-set host 0.0.0.0");
        System.out.println("ssh-set port <public_port>");
        System.out.println("ssh-set user <username>");
        System.out.println("ssh-set pass <password>");
        System.out.println("ssh-set root /home/container/uploads");
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
                writeLine(out, "Mini Java Terminal SSH");
                writeLine(out, "Type 'help' for commands, 'exit' to close.");
                writeLine(out, "");
            
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

                    if (line.equalsIgnoreCase("ssh-help")) {
                        printSshHelp(out);
                        continue;
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

                    if (isMjtCommand(line)) {
                        String output = runMjtCommandAndCaptureOutput(line);
                    
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
            
                // Bỏ qua một số escape key đơn giản như mũi tên
                if (ch == 27) {
                    continue;
                }
            
                builder.append(ch);
                write(out, String.valueOf(ch));
            }
        }

        private void printSshHelp(PrintWriter out) {
            writeLine(out, "Available SSH commands:");
            writeLine(out, "  help        - Show help");
            writeLine(out, "  pwd         - Show current directory");
            writeLine(out, "  ls          - List files");
            writeLine(out, "  ll          - List files with size");
            writeLine(out, "  cd <folder> - Change directory inside ssh.root");
            writeLine(out, "  clear       - Clear screen");
            writeLine(out, "  exit        - Close SSH session");
            writeLine(out, "");
            writeLine(out, "Other commands are executed by the host shell.");
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

        private boolean isMjtCommand(String line) {
            String lower = line.toLowerCase();
        
            return lower.equals("help")
                    || lower.equals("public-ip")
                    || lower.equals("timeout")
                    || lower.startsWith("timeout ")
                    || lower.startsWith("cloudflare-")
                    || lower.startsWith("ssh-")
                    || lower.startsWith("sftp-")
                    || lower.startsWith("web-")
                    || lower.equals("shutdown-terminal");
        }

        private synchronized String runMjtCommandAndCaptureOutput(String command) {
            if (commandCenter == null) {
                return "[SSH] CommandCenter chưa được gắn vào SshServerService.";
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
}