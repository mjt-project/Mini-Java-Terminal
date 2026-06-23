package main.java.mjt.services.minecraft;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import main.java.mjt.services.proot.ProotService;
import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

/**
 * Starts Minecraft profiles either on the host (legacy default) or in the
 * MJT PRoot guest runtime. Profile files always remain in the host workspace,
 * while Java and installed guest packages are resolved inside PRootFS.
 */
public class MinecraftProcessManagerService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final int MAX_OUTPUT_LINES = 3000;

    private final StateStore stateStore;
    private final LogService logService;
    private final ProotService prootService;
    private final Map<String, ManagedMinecraftProcess> processes = new LinkedHashMap<>();
    private volatile String attachedProfile = "";

    /** Compatibility constructor: host runtime only if PRoot is not supplied. */
    public MinecraftProcessManagerService(StateStore stateStore, LogService logService) {
        this(stateStore, logService, null);
    }

    public MinecraftProcessManagerService(StateStore stateStore, LogService logService, ProotService prootService) {
        this.stateStore = stateStore;
        this.logService = logService;
        this.prootService = prootService;
    }

    public synchronized void startProfile(String rawProfile) throws IOException {
        startProfile(rawProfile, "");
    }

    public synchronized void startProfile(String rawProfile, String commandOverride) throws IOException {
        String profile = resolveProfile(rawProfile);
        ManagedMinecraftProcess existing = processes.get(profile);
        if (existing != null && existing.isRunning()) {
            System.out.println(YELLOW + "[Minecraft] Profile already running: " + profile + RESET);
            return;
        }

        Path workdir = Paths.get(getProfileWorkdir(profile)).toAbsolutePath().normalize();
        Files.createDirectories(workdir);
        String command = commandOverride == null || commandOverride.isBlank() ? getProfileCommand(profile) : commandOverride.trim();
        if (command.isBlank()) command = "bash start.sh";
        String runtime = getProfileRuntime(profile);

        ProcessBuilder builder = createProcessBuilder(profile, command, workdir, runtime);
        builder.directory(workdir.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BufferedWriter input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        ManagedMinecraftProcess managed = new ManagedMinecraftProcess(profile, command, runtime, workdir, process, input);
        processes.put(profile, managed);
        attachedProfile = profile;
        stateStore.set("minecraft.active", profile);

        Thread outputThread = new Thread(() -> readOutput(managed), "mjt-minecraft-output-" + safeThreadName(profile));
        outputThread.setDaemon(true);
        outputThread.start();
        Thread watcherThread = new Thread(() -> waitForExit(managed), "mjt-minecraft-watcher-" + safeThreadName(profile));
        watcherThread.setDaemon(true);
        watcherThread.start();

        managed.addOutputLine("[MINECRAFT] Started profile: " + profile + " runtime=" + runtime);
        managed.addOutputLine("[MINECRAFT] Command: " + command);
        managed.addOutputLine("[MINECRAFT] Workdir : " + workdir);
        System.out.println(GREEN + "[Minecraft] Started profile: " + profile + " | runtime=" + runtime + RESET);
        System.out.println(CYAN + "[Minecraft] Command: " + command + RESET);
        System.out.println(CYAN + "[Minecraft] Workdir : " + workdir + RESET);
        logService.write("[MINECRAFT START] " + profile + " | runtime=" + runtime + " | " + command + " | " + workdir + "\n");
    }

    public synchronized void stopProfile(String rawProfile) {
        String profile = resolveProfile(rawProfile);
        ManagedMinecraftProcess managed = processes.get(profile);
        if (managed == null || !managed.isRunning()) {
            System.out.println(YELLOW + "[Minecraft] Profile is not running: " + profile + RESET);
            return;
        }
        String stopCommand = getProfileStopCommand(profile);
        sendLine(profile, stopCommand);
        System.out.println(YELLOW + "[Minecraft] Sent stop to " + profile + ": " + stopCommand + RESET);
    }

    public synchronized void stopAll() {
        for (String profile : new ArrayList<>(processes.keySet())) {
            if (isRunning(profile)) stopProfile(profile);
        }
    }

    public synchronized void killProfile(String rawProfile) {
        String profile = resolveProfile(rawProfile);
        ManagedMinecraftProcess managed = processes.get(profile);
        if (managed == null || !managed.isRunning()) {
            System.out.println(YELLOW + "[Minecraft] Profile is not running: " + profile + RESET);
            return;
        }
        destroyProcessTree(managed.process);
        managed.addOutputLine("[MINECRAFT] Killed profile: " + profile);
        System.out.println(RED + "[Minecraft] Killed profile: " + profile + RESET);
    }

    public synchronized void killAll() {
        for (ManagedMinecraftProcess managed : new ArrayList<>(processes.values())) {
            if (managed.isRunning()) destroyProcessTree(managed.process);
        }
        System.out.println(RED + "[Minecraft] Kill requested for all running profiles." + RESET);
    }

    public synchronized void sendLine(String rawProfile, String line) {
        String profile = resolveProfile(rawProfile);
        ManagedMinecraftProcess managed = processes.get(profile);
        if (managed == null || !managed.isRunning()) {
            System.out.println(YELLOW + "[Minecraft] Profile is not running: " + profile + RESET);
            return;
        }
        try {
            managed.input.write(line == null ? "" : line);
            managed.input.newLine();
            managed.input.flush();
            managed.addOutputLine("> " + line);
            logService.write("[MINECRAFT INPUT " + profile + "] " + line + "\n");
        } catch (IOException e) {
            System.out.println(RED + "[Minecraft] Cannot send command to " + profile + ": " + e.getMessage() + RESET);
        }
    }

    public synchronized void sendLineAttached(String line) { sendLine(getAttachedProfile(), line); }

    public synchronized void attach(String rawProfile) throws IOException {
        String profile = normalizeProfileName(rawProfile);
        if (profile.isBlank()) throw new IOException("Invalid profile name.");
        if (!getProfileNames().contains(profile) && !processes.containsKey(profile)) throw new IOException("Profile not found: " + profile);
        attachedProfile = profile;
        stateStore.set("minecraft.active", profile);
        System.out.println(GREEN + "[Minecraft] Attached console to profile: " + profile + RESET);
    }

    public synchronized void attachActiveIfNeeded() {
        if (!attachedProfile.isBlank() && isRunning(attachedProfile)) return;
        String active = normalizeProfileName(stateStore.get("minecraft.active", "smp"));
        if (!active.isBlank() && isRunning(active)) { attachedProfile = active; return; }
        for (String name : processes.keySet()) if (isRunning(name)) { attachedProfile = name; return; }
    }

    public synchronized boolean isAttachedRunning() { attachActiveIfNeeded(); return isRunning(attachedProfile); }
    public synchronized boolean hasRunningProcesses() { return processes.keySet().stream().anyMatch(this::isRunning); }
    public synchronized boolean isRunning(String rawProfile) {
        ManagedMinecraftProcess managed = processes.get(normalizeProfileName(rawProfile));
        return managed != null && managed.isRunning();
    }
    public synchronized String getAttachedProfile() {
        attachActiveIfNeeded();
        return attachedProfile == null || attachedProfile.isBlank() ? stateStore.get("minecraft.active", "smp") : attachedProfile;
    }
    public synchronized List<String> getRunningProfileNames() {
        List<String> result = new ArrayList<>();
        for (String name : processes.keySet()) if (isRunning(name)) result.add(name);
        return result;
    }
    public synchronized List<String> getProfileNames() {
        List<String> result = new ArrayList<>();
        for (String item : stateStore.get("minecraft.profiles", "velocity,smp,lobby").split(",")) {
            String profile = normalizeProfileName(item);
            if (!profile.isBlank() && !result.contains(profile)) result.add(profile);
        }
        return result;
    }

    public synchronized void printStatus() {
        System.out.println(CYAN + "[MINECRAFT PROCESSES]" + RESET);
        System.out.println("Active profile  : " + stateStore.get("minecraft.active", "smp"));
        System.out.println("Attached console: " + getAttachedProfile());
        System.out.println("Running profiles: " + (getRunningProfileNames().isEmpty() ? "none" : String.join(",", getRunningProfileNames())));
        System.out.println();
        for (String profile : getProfileNames()) printStatus(profile);
    }

    public synchronized void printStatus(String rawProfile) {
        String profile = resolveProfile(rawProfile);
        ManagedMinecraftProcess managed = processes.get(profile);
        String base = "minecraft.profile." + profile + ".";
        System.out.println("  " + profile + " | running=" + (managed != null && managed.isRunning())
                + " | runtime=" + getProfileRuntime(profile)
                + " | type=" + stateStore.get(base + "type", "minecraft")
                + " | port=" + stateStore.get(base + "port", "")
                + " | workdir=" + getProfileWorkdir(profile));
        if (managed != null) {
            System.out.println("    command=" + managed.command);
            System.out.println("    uptime=" + formatDuration(managed.startedAt));
            System.out.println("    exit=" + managed.lastExitCode);
        }
    }

    public synchronized void printLogs(String rawProfile, int maxLines) {
        String profile = resolveProfile(rawProfile);
        System.out.println(CYAN + "[MINECRAFT LOGS] " + profile + RESET);
        for (String line : getRecentOutputLines(profile, maxLines)) System.out.println(line);
    }
    public synchronized List<String> getRecentOutputLines(String rawProfile, int maxLines) {
        ManagedMinecraftProcess managed = processes.get(resolveProfile(rawProfile));
        return managed == null ? List.of() : managed.getRecentOutputLines(maxLines);
    }

    public String getProfileWorkdir(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        return stateStore.get("minecraft.profile." + profile + ".workdir", stateStore.get("minecraft.workdir", "/home/container/server/Minecraft/smp")).trim();
    }
    public String getProfileCommand(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        return stateStore.get("minecraft.profile." + profile + ".command", stateStore.get("minecraft.start-command", "bash start.sh")).trim();
    }
    public String getProfileStopCommand(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        String stop = stateStore.get("minecraft.profile." + profile + ".stop", stateStore.get("minecraft.stop-command", "stop")).trim();
        return stop.isBlank() ? "stop" : stop;
    }

    public synchronized String getProfileRuntime(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        String runtime = stateStore.get("minecraft.profile." + profile + ".runtime", stateStore.get("minecraft.runtime", "host"));
        return normalizeRuntime(runtime);
    }

    public synchronized void setProfileRuntime(String rawProfile, String rawRuntime) throws IOException {
        String profile = normalizeProfileName(rawProfile);
        if (profile.isBlank()) throw new IOException("Invalid profile name.");
        String runtime = normalizeRuntime(rawRuntime);
        if (!runtime.equals("host") && !runtime.equals("proot")) throw new IOException("Runtime must be host or proot.");
        if (isRunning(profile)) throw new IOException("Stop profile before changing its runtime: " + profile);
        stateStore.set("minecraft.profile." + profile + ".runtime", runtime);
        System.out.println(GREEN + "[Minecraft] Profile runtime saved: " + profile + " = " + runtime + RESET);
    }

    public String normalizeProfileName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private ProcessBuilder createProcessBuilder(String profile, String command, Path workdir, String runtime) throws IOException {
        if (runtime.equals("proot")) {
            if (prootService == null) throw new IOException("PRoot runtime is not wired. Restart MJT with the PRoot Phase 2 overlay.");
            return new ProcessBuilder(prootService.buildGuestCommand(command, workdir));
        }
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return osName.contains("win") ? new ProcessBuilder("cmd.exe", "/c", command) : new ProcessBuilder("bash", "-lc", command);
    }

    private synchronized String resolveProfile(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        if (!profile.isBlank()) return profile;
        if (attachedProfile != null && !attachedProfile.isBlank()) return attachedProfile;
        profile = normalizeProfileName(stateStore.get("minecraft.active", "smp"));
        return profile.isBlank() ? "smp" : profile;
    }

    private String normalizeRuntime(String raw) {
        String runtime = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return runtime.equals("proot") || runtime.equals("guest") ? "proot" : "host";
    }

    private void readOutput(ManagedMinecraftProcess managed) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(managed.process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                managed.addOutputLine(line);
                logService.write("[MINECRAFT OUTPUT " + managed.profile + "] " + line + "\n");
            }
        } catch (IOException e) { logQuietly("[MINECRAFT OUTPUT ERROR " + managed.profile + "] " + e.getMessage() + "\n"); }
    }

    private void waitForExit(ManagedMinecraftProcess managed) {
        try {
            int code = managed.process.waitFor();
            managed.lastExitCode = code;
            managed.addOutputLine("[MINECRAFT] Exited with code: " + code);
            System.out.println(YELLOW + "[Minecraft] " + managed.profile + " exited with code: " + code + RESET);
            logService.write("[MINECRAFT EXIT " + managed.profile + "] code=" + code + "\n");
        } catch (Exception e) {
            managed.addOutputLine("[MINECRAFT] Wait error: " + e.getMessage());
            logQuietly("[MINECRAFT WAIT ERROR " + managed.profile + "] " + e.getMessage() + "\n");
        } finally { try { managed.input.close(); } catch (IOException ignored) {} }
    }

    private void destroyProcessTree(Process process) {
        if (process == null || !process.isAlive()) return;
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(child -> { try { child.destroy(); } catch (Exception ignored) {} });
        process.destroy();
        try { Thread.sleep(1000L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (process.isAlive()) {
            handle.descendants().forEach(child -> { try { child.destroyForcibly(); } catch (Exception ignored) {} });
            process.destroyForcibly();
        }
    }
    private String safeThreadName(String value) { return value == null || value.isBlank() ? "minecraft" : value.replaceAll("[^a-zA-Z0-9._-]", "_"); }
    private String formatDuration(Instant at) {
        if (at == null) return "none";
        long seconds = Math.max(0, Duration.between(at, Instant.now()).toSeconds());
        long h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? h + "h " + m + "m " + s + "s" : (m > 0 ? m + "m " + s + "s" : s + "s");
    }
    private void logQuietly(String text) { try { logService.write(text); } catch (IOException ignored) {} }

    private static final class ManagedMinecraftProcess {
        final String profile, command, runtime;
        final Path workdir;
        final Process process;
        final BufferedWriter input;
        final Instant startedAt = Instant.now();
        volatile int lastExitCode = Integer.MIN_VALUE;
        final ArrayDeque<String> outputLines = new ArrayDeque<>();
        ManagedMinecraftProcess(String profile, String command, String runtime, Path workdir, Process process, BufferedWriter input) {
            this.profile = profile; this.command = command; this.runtime = runtime; this.workdir = workdir; this.process = process; this.input = input;
        }
        boolean isRunning() { return process != null && process.isAlive(); }
        synchronized void addOutputLine(String line) { outputLines.addLast(line == null ? "" : line); while (outputLines.size() > MAX_OUTPUT_LINES) outputLines.removeFirst(); }
        synchronized List<String> getRecentOutputLines(int maxLines) {
            int limit = maxLines <= 0 ? 200 : Math.min(maxLines, MAX_OUTPUT_LINES);
            List<String> all = new ArrayList<>(outputLines);
            return all.size() <= limit ? all : new ArrayList<>(all.subList(all.size() - limit, all.size()));
        }
    }
}
