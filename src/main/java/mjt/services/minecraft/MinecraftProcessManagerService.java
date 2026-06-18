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

import main.java.mjt.system.LogService;
import main.java.mjt.system.StateStore;

public class MinecraftProcessManagerService {
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";

    private static final int MAX_OUTPUT_LINES = 3000;

    private final StateStore stateStore;
    private final LogService logService;
    private final Map<String, ManagedMinecraftProcess> processes = new LinkedHashMap<>();

    private volatile String attachedProfile = "";

    public MinecraftProcessManagerService(StateStore stateStore, LogService logService) {
        this.stateStore = stateStore;
        this.logService = logService;
    }

    public synchronized void startProfile(String rawProfile) throws IOException {
        startProfile(rawProfile, "");
    }

    public synchronized void startProfile(String rawProfile, String commandOverride) throws IOException {
        String profile = normalizeProfileName(rawProfile);
        if (profile.isBlank()) {
            profile = normalizeProfileName(stateStore.get("minecraft.active", "smp"));
        }
        if (profile.isBlank()) {
            profile = "smp";
        }

        ManagedMinecraftProcess existing = processes.get(profile);
        if (existing != null && existing.isRunning()) {
            System.out.println(YELLOW + "[Minecraft] Profile already running: " + profile + RESET);
            return;
        }

        String workdirText = getProfileWorkdir(profile);
        String command = commandOverride == null || commandOverride.trim().isBlank()
                ? getProfileCommand(profile)
                : commandOverride.trim();

        if (command.isBlank()) {
            command = "bash start.sh";
        }

        Path workdir = Paths.get(workdirText).toAbsolutePath().normalize();
        Files.createDirectories(workdir);

        ProcessBuilder processBuilder = createProcessBuilder(command);
        processBuilder.directory(workdir.toFile());
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        BufferedWriter input = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)
        );

        ManagedMinecraftProcess managed = new ManagedMinecraftProcess(profile, command, workdir, process, input);
        processes.put(profile, managed);
        attachedProfile = profile;
        stateStore.set("minecraft.active", profile);

        Thread outputThread = new Thread(
                () -> readOutput(managed),
                "mjt-minecraft-output-" + safeThreadName(profile)
        );
        outputThread.setDaemon(true);
        outputThread.start();

        Thread watcherThread = new Thread(
                () -> waitForExit(managed),
                "mjt-minecraft-watcher-" + safeThreadName(profile)
        );
        watcherThread.setDaemon(true);
        watcherThread.start();

        managed.addOutputLine("[MINECRAFT] Started profile: " + profile);
        managed.addOutputLine("[MINECRAFT] Command: " + command);
        managed.addOutputLine("[MINECRAFT] Workdir : " + workdir);

        System.out.println(GREEN + "[Minecraft] Started profile: " + profile + RESET);
        System.out.println(CYAN + "[Minecraft] Command: " + command + RESET);
        System.out.println(CYAN + "[Minecraft] Workdir : " + workdir + RESET);

        logService.write("[MINECRAFT START] " + profile + " | " + command + " | " + workdir + "\n");
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
            ManagedMinecraftProcess managed = processes.get(profile);
            if (managed != null && managed.isRunning()) {
                stopProfile(profile);
            }
        }
    }

    public synchronized void killProfile(String rawProfile) {
        String profile = resolveProfile(rawProfile);
        ManagedMinecraftProcess managed = processes.get(profile);
        if (managed == null || !managed.isRunning()) {
            System.out.println(YELLOW + "[Minecraft] Profile is not running: " + profile + RESET);
            return;
        }

        destroyProcess(managed.process);
        managed.addOutputLine("[MINECRAFT] Killed profile: " + profile);
        System.out.println(RED + "[Minecraft] Killed profile: " + profile + RESET);
    }

    public synchronized void killAll() {
        for (String profile : new ArrayList<>(processes.keySet())) {
            ManagedMinecraftProcess managed = processes.get(profile);
            if (managed != null && managed.isRunning()) {
                destroyProcess(managed.process);
                managed.addOutputLine("[MINECRAFT] Killed profile: " + profile);
            }
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

    public synchronized void sendLineAttached(String line) {
        String profile = resolveProfile(attachedProfile);
        sendLine(profile, line);
    }

    public synchronized void attach(String rawProfile) throws IOException {
        String profile = normalizeProfileName(rawProfile);
        if (profile.isBlank()) {
            throw new IOException("Invalid profile name.");
        }
        if (!getProfileNames().contains(profile) && !processes.containsKey(profile)) {
            throw new IOException("Profile not found: " + profile);
        }
        attachedProfile = profile;
        stateStore.set("minecraft.active", profile);
        System.out.println(GREEN + "[Minecraft] Attached console to profile: " + profile + RESET);
    }

    public synchronized void attachActiveIfNeeded() {
        String profile = normalizeProfileName(attachedProfile);
        if (!profile.isBlank() && isRunning(profile)) {
            return;
        }

        String active = normalizeProfileName(stateStore.get("minecraft.active", "smp"));
        if (!active.isBlank() && isRunning(active)) {
            attachedProfile = active;
            return;
        }

        for (String name : processes.keySet()) {
            if (isRunning(name)) {
                attachedProfile = name;
                return;
            }
        }
    }

    public synchronized boolean isAttachedRunning() {
        attachActiveIfNeeded();
        return isRunning(attachedProfile);
    }

    public synchronized boolean hasRunningProcesses() {
        for (ManagedMinecraftProcess managed : processes.values()) {
            if (managed.isRunning()) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isRunning(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        ManagedMinecraftProcess managed = processes.get(profile);
        return managed != null && managed.isRunning();
    }

    public synchronized String getAttachedProfile() {
        attachActiveIfNeeded();
        return attachedProfile == null || attachedProfile.isBlank() ? stateStore.get("minecraft.active", "smp") : attachedProfile;
    }

    public synchronized List<String> getRunningProfileNames() {
        List<String> running = new ArrayList<>();
        for (String profile : processes.keySet()) {
            if (isRunning(profile)) {
                running.add(profile);
            }
        }
        return running;
    }

    public synchronized List<String> getProfileNames() {
        String raw = stateStore.get("minecraft.profiles", "velocity,smp,lobby").trim();
        List<String> profiles = new ArrayList<>();
        for (String item : raw.split(",")) {
            String clean = normalizeProfileName(item);
            if (!clean.isBlank() && !profiles.contains(clean)) {
                profiles.add(clean);
            }
        }
        return profiles;
    }

    public synchronized void printStatus() {
        System.out.println(CYAN + "[MINECRAFT PROCESSES]" + RESET);
        System.out.println("Active profile  : " + stateStore.get("minecraft.active", "smp"));
        System.out.println("Attached console: " + getAttachedProfile());
        System.out.println("Running profiles: " + (getRunningProfileNames().isEmpty() ? "none" : String.join(",", getRunningProfileNames())));
        System.out.println();

        for (String profile : getProfileNames()) {
            printStatus(profile);
        }
    }

    public synchronized void printStatus(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        if (profile.isBlank()) {
            profile = stateStore.get("minecraft.active", "smp");
        }
        ManagedMinecraftProcess managed = processes.get(profile);
        boolean running = managed != null && managed.isRunning();
        String base = "minecraft.profile." + profile + ".";

        System.out.println("  " + profile
                + " | running=" + running
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
        for (String line : getRecentOutputLines(profile, maxLines)) {
            System.out.println(line);
        }
    }

    public synchronized List<String> getRecentOutputLines(String rawProfile, int maxLines) {
        String profile = resolveProfile(rawProfile);
        ManagedMinecraftProcess managed = processes.get(profile);
        if (managed == null) {
            return List.of();
        }
        return managed.getRecentOutputLines(maxLines);
    }

    public String getProfileWorkdir(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        String key = "minecraft.profile." + profile + ".workdir";
        String fallback = stateStore.get("minecraft.workdir", "/home/container/server/Minecraft/smp");
        return stateStore.get(key, fallback).trim();
    }

    public String getProfileCommand(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        String key = "minecraft.profile." + profile + ".command";
        return stateStore.get(key, stateStore.get("minecraft.start-command", "bash start.sh")).trim();
    }

    public String getProfileStopCommand(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        String key = "minecraft.profile." + profile + ".stop";
        String stop = stateStore.get(key, stateStore.get("minecraft.stop-command", "stop")).trim();
        return stop.isBlank() ? "stop" : stop;
    }

    public String normalizeProfileName(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private synchronized String resolveProfile(String rawProfile) {
        String profile = normalizeProfileName(rawProfile);
        if (!profile.isBlank()) {
            return profile;
        }
        if (attachedProfile != null && !attachedProfile.isBlank()) {
            return attachedProfile;
        }
        return normalizeProfileName(stateStore.get("minecraft.active", "smp"));
    }

    private ProcessBuilder createProcessBuilder(String command) {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (osName.contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        }
        return new ProcessBuilder("bash", "-lc", command);
    }

    private void readOutput(ManagedMinecraftProcess managed) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(managed.process.getInputStream(), StandardCharsets.UTF_8)
        )) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(line);
                managed.addOutputLine(line);
                logService.write("[MINECRAFT OUTPUT " + managed.profile + "] " + line + "\n");
            }
        } catch (IOException e) {
            try {
                logService.write("[MINECRAFT OUTPUT ERROR " + managed.profile + "] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        }
    }

    private void waitForExit(ManagedMinecraftProcess managed) {
        int exitCode = -1;
        try {
            exitCode = managed.process.waitFor();
            managed.lastExitCode = exitCode;
            managed.addOutputLine("[MINECRAFT] Exited with code: " + exitCode);
            System.out.println(YELLOW + "[Minecraft] " + managed.profile + " exited with code: " + exitCode + RESET);
            logService.write("[MINECRAFT EXIT " + managed.profile + "] code=" + exitCode + "\n");
        } catch (Exception e) {
            managed.addOutputLine("[MINECRAFT] Wait error: " + e.getMessage());
            try {
                logService.write("[MINECRAFT WAIT ERROR " + managed.profile + "] " + e.getMessage() + "\n");
            } catch (IOException ignored) {
            }
        } finally {
            try {
                managed.input.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void destroyProcess(Process targetProcess) {
        if (targetProcess == null || !targetProcess.isAlive()) {
            return;
        }
        targetProcess.destroy();
        try {
            Thread.sleep(1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (targetProcess.isAlive()) {
            targetProcess.destroyForcibly();
        }
    }

    private String safeThreadName(String value) {
        if (value == null || value.isBlank()) {
            return "minecraft";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String formatDuration(Instant startedAt) {
        if (startedAt == null) {
            return "none";
        }
        long seconds = Math.max(0, Duration.between(startedAt, Instant.now()).toSeconds());
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m " + secs + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    private static final class ManagedMinecraftProcess {
        final String profile;
        final String command;
        final Path workdir;
        final Process process;
        final BufferedWriter input;
        final Instant startedAt = Instant.now();
        volatile int lastExitCode = Integer.MIN_VALUE;
        final ArrayDeque<String> outputLines = new ArrayDeque<>();

        ManagedMinecraftProcess(String profile, String command, Path workdir, Process process, BufferedWriter input) {
            this.profile = profile;
            this.command = command;
            this.workdir = workdir;
            this.process = process;
            this.input = input;
        }

        boolean isRunning() {
            return process != null && process.isAlive();
        }

        synchronized void addOutputLine(String line) {
            outputLines.addLast(line == null ? "" : line);
            while (outputLines.size() > MAX_OUTPUT_LINES) {
                outputLines.removeFirst();
            }
        }

        synchronized List<String> getRecentOutputLines(int maxLines) {
            int limit = maxLines <= 0 ? 200 : Math.min(maxLines, MAX_OUTPUT_LINES);
            List<String> all = new ArrayList<>(outputLines);
            if (all.size() <= limit) {
                return all;
            }
            return new ArrayList<>(all.subList(all.size() - limit, all.size()));
        }
    }
}
