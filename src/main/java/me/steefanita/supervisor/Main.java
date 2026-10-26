package me.steefanita.supervisor;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import com.google.inject.Inject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Plugin(
        id = "supervisor",
        name = "Supervisor",
        version = "1.0.0",
        description = "Manage child Minecraft servers from Velocity.",
        authors = {"steefanita"}
)
public final class Main {

    private static final String PERM_ADMIN = "supervisor.admin";
    private static final String PERM_USE = "supervisor.use";
    private static final String PERM_LIST = "supervisor.list";
    private static final String PERM_STATUS = "supervisor.status";
    private static final String PERM_START = "supervisor.start";
    private static final String PERM_STOP = "supervisor.stop";
    private static final String PERM_RESTART = "supervisor.restart";
    private static final String PERM_RELOAD = "supervisor.reload";
    private static final String PERM_CONSOLE = "supervisor.console";

    private static final long STOP_TIMEOUT_SECONDS = 30L;
    private static final long STARTUP_TIMEOUT_SECONDS = 300L;
    private static final long SHUTDOWN_TOTAL_MS = 30_000L;
    private static final int STARTUP_STAGGER_SEC = 2;
    private static final int RESTART_DELAY_SEC = 2;
    private static final boolean DEFAULT_VERBOSE = false;

    private static final List<String> SUBCOMMANDS =
            List.of("help", "list", "status", "start", "stop", "restart", "reload", "cmd");

    private static final Set<String> SERVER_SUBCOMMANDS =
            Set.of("status", "start", "stop", "restart", "cmd");

    private static final String DEFAULT_CONFIG = String.join("\n",
            "# Supervisor Configuration",
            "#",
            "# Each section is one child server.",
            "# Keys:",
            "# enabled - auto-start on proxy startup (default false)",
            "# command - launch command",
            "# verbose - print all child console output to proxy console (default false)",
            "# If verbose is false, only important lines are shown (ERROR, WARN, Done (, Started server in, etc.)",
            "#",
            "# Each server runs in plugins/Supervisor/servers/<name>/",
            "#",
            "# Use /supervisor cmd <server> <command> to send a command directly to the server console.",
            "",
            "[lobby]",
            "enabled=false",
            "command=java -jar server.jar --nogui --port 1000",
            "verbose=false",
            "",
            "[vanilla]",
            "enabled=false",
            "command=java -jar server.jar --nogui --port 2000",
            "verbose=false",
            "");

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;

    private final Map<String, ServerConfig> config = new ConcurrentHashMap<>();
    private final Map<String, ChildServer> servers = new ConcurrentHashMap<>();

    private final Object lifecycleLock = new Object();

    private ExecutorService logExecutor;
    private ScheduledExecutorService scheduler;
    private Path serversDir;
    private Path configFile;
    private volatile boolean shuttingDown = false;

    @Inject
    public Main(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            Files.createDirectories(dataDirectory);
            serversDir = dataDirectory.resolve("servers");
            Files.createDirectories(serversDir);
            configFile = dataDirectory.resolve("config.ini");
            if (!Files.exists(configFile)) {
                Files.write(configFile, DEFAULT_CONFIG.getBytes(StandardCharsets.UTF_8));
                logger.info("Created default config at {}", configFile);
            }
        } catch (IOException e) {
            logger.error("Failed to prepare Supervisor directories", e);
            return;
        }

        logExecutor = Executors.newCachedThreadPool(threadFactory("Supervisor-LogReader"));
        scheduler = Executors.newSingleThreadScheduledExecutor(threadFactory("Supervisor-Scheduler"));

        loadConfig();
        for (String name : config.keySet()) {
            servers.putIfAbsent(name, new ChildServer(name));
        }

        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("supervisor").aliases("sup", "sv").build(),
                new SupervisorCommand()
        );

        int delay = 0;
        for (Map.Entry<String, ServerConfig> entry : config.entrySet()) {
            if (!entry.getValue().enabled()) {
                continue;
            }
            scheduleStart(entry.getKey(), delay);
            delay += STARTUP_STAGGER_SEC;
        }

        logger.info("Supervisor enabled, {} server(s)", config.size());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        List<ChildServer> running = new ArrayList<>();
        synchronized (lifecycleLock) {
            shuttingDown = true;
            for (ChildServer cs : servers.values()) {
                Process process = cs.process;
                if (process != null && process.isAlive()) {
                    running.add(cs);
                }
            }
        }

        if (scheduler != null) {
            scheduler.shutdownNow();
        }

        if (running.isEmpty()) {
            shutdownExecutors();
            return;
        }

        for (ChildServer cs : running) {
            Process process = cs.process;
            if (process == null || !process.isAlive()) {
                continue;
            }
            synchronized (cs.lock) {
                if (cs.process != process) {
                    continue;
                }
                cs.manualStop = true;
                cs.state = State.STOPPING;
            }
            BufferedWriter stdin = cs.stdin;
            try {
                sendCommand(cs.name, stdin, "stop");
            } catch (IOException e) {
                logger.debug("Failed to send stop to '{}': {}", cs.name, e.getMessage());
            }
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
        }

        long deadline = System.currentTimeMillis() + SHUTDOWN_TOTAL_MS;
        for (ChildServer cs : running) {
            Process process = cs.process;
            if (process == null) {
                continue;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            try {
                process.waitFor(remaining, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        for (ChildServer cs : running) {
            Process process = cs.process;
            if (process != null && process.isAlive()) {
                logger.warn("Force-killing '{}' (PID {})", cs.name, process.pid());
                destroyTree(process);
                try {
                    process.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (process.isAlive()) {
                    try {
                        process.destroyForcibly();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        shutdownExecutors();
    }

    private void shutdownExecutors() {
        if (logExecutor != null) {
            logExecutor.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    // Config

    private void loadConfig() {
        Map<String, ServerConfig> loaded = new LinkedHashMap<>();
        if (configFile == null || !Files.exists(configFile)) {
            config.clear();
            return;
        }

        try (BufferedReader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
            String section = null;
            String command = null;
            boolean enabled = false;
            boolean verbose = DEFAULT_VERBOSE;
            boolean first = true;
            String line;

            while ((line = reader.readLine()) != null) {
                if (first) {
                    first = false;
                    if (!line.isEmpty() && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }
                }

                line = stripInlineComment(line).trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }

                if (line.startsWith("[") && line.endsWith("]")) {
                    if (section != null) {
                        flushSection(loaded, section, command, enabled, verbose);
                    }
                    section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                    command = null;
                    enabled = false;
                    verbose = DEFAULT_VERBOSE;
                    continue;
                }

                if (section == null) {
                    continue;
                }

                int eq = line.indexOf('=');
                if (eq < 0) {
                    continue;
                }

                String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(eq + 1).trim();

                switch (key) {
                    case "command":
                        command = value;
                        break;
                    case "enabled":
                        enabled = parseBool(value);
                        break;
                    case "verbose":
                        verbose = parseBool(value);
                        break;
                    default:
                        break;
                }
            }

            if (section != null) {
                flushSection(loaded, section, command, enabled, verbose);
            }
        } catch (IOException e) {
            logger.error("Failed to read config.ini", e);
            return;
        }

        config.clear();
        config.putAll(loaded);
    }

    private void flushSection(Map<String, ServerConfig> loaded, String name, String command,
                              boolean enabled, boolean verbose) {
        if (!isValidServerName(name)) {
            logger.warn("Ignoring invalid server name '{}' in config.ini", name);
            return;
        }
        if (command == null || command.trim().isEmpty()) {
            logger.warn("Server '{}' has no command; skipping", name);
            return;
        }
        if (loaded.containsKey(name)) {
            logger.warn("Duplicate server section '{}'; keeping the first entry", name);
            return;
        }
        loaded.put(name, new ServerConfig(enabled, command.trim(), verbose));
    }

    private static String stripInlineComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
            } else if (c == '#' && !inSingle && !inDouble) {
                if (i == 0 || Character.isWhitespace(line.charAt(i - 1))) {
                    return line.substring(0, i);
                }
            }
        }
        return line;
    }

    private static boolean parseBool(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1")
                || normalized.equals("yes") || normalized.equals("on");
    }

    private static boolean isValidServerName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64 || name.contains("..")) {
            return false;
        }
        char first = name.charAt(0);
        if (!((first >= 'a' && first <= 'z') || (first >= '0' && first <= '9'))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean valid = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_' || c == '-';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    // Lifecycle

    private void scheduleStart(String name, long delaySeconds) {
        try {
            scheduler.schedule(() -> startServer(name), delaySeconds, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void startServer(String name) {
        if (shuttingDown) {
            return;
        }

        ServerConfig cfg = config.get(name);
        if (cfg == null) {
            logger.warn("Cannot start unknown server '{}'", name);
            return;
        }

        ChildServer cs = servers.computeIfAbsent(name, ChildServer::new);

        synchronized (cs.lock) {
            Process existing = cs.process;
            if (existing != null && existing.isAlive()) {
                logger.warn("Server '{}' is already running", name);
                return;
            }
            if (cs.state == State.STARTING) {
                logger.warn("Server '{}' is already starting", name);
                return;
            }

            Path workDir;
            try {
                workDir = resolveWorkDir(name);
            } catch (IOException e) {
                cs.state = State.STOPPED;
                logger.error("Invalid work directory for '{}': {}", name, e.getMessage());
                return;
            }

            for (ChildServer other : servers.values()) {
                if (other == cs) {
                    continue;
                }
                Process otherProcess = other.process;
                if (otherProcess != null && otherProcess.isAlive() && workDir.equals(other.workDir)) {
                    cs.state = State.STOPPED;
                    logger.error("Work directory {} is already used by '{}'", workDir, other.name);
                    return;
                }
            }

            cs.workDir = workDir;
            cs.manualStop = false;
            cs.restartPending = false;
            cs.state = State.STARTING;

            Process process = null;
            BufferedWriter newStdin = null;
            boolean committed = false;

            try {
                Files.createDirectories(workDir);

                ProcessBuilder pb = new ProcessBuilder(splitCommand(cfg.command()));
                pb.directory(workDir.toFile());
                pb.redirectErrorStream(true);

                process = pb.start();

                final Process startedProcess = process;
                final long runId = ++cs.runId;
                newStdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

                startedProcess.onExit().thenRun(() -> handleExit(cs, startedProcess, runId));

                // avoid orphans on shutdown
                boolean aborted;
                synchronized (lifecycleLock) {
                    aborted = shuttingDown;
                    if (!aborted) {
                        cs.process = startedProcess;
                        cs.stdin = newStdin;
                        cs.startedAt = System.currentTimeMillis();

                        final Process timedProcess = startedProcess;
                        try {
                            cs.startupTimeout = scheduler.schedule(() -> {
                                synchronized (cs.lock) {
                                    if (cs.process != timedProcess) {
                                        return;
                                    }
                                    if (cs.state == State.STARTING && timedProcess.isAlive()) {
                                        cs.state = State.RUNNING;
                                        logger.warn("Server '{}' ready timeout ({}s), assuming running",
                                                cs.name, STARTUP_TIMEOUT_SECONDS);
                                    }
                                }
                            }, STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        } catch (RejectedExecutionException ignored) {
                        }

                        committed = true;
                    }
                }

                if (aborted) {
                    logger.info("Start of '{}' aborted (shutdown)", name);
                    return;
                }

                logger.info("Starting server '{}' in {}", name, workDir);
                try {
                    logExecutor.submit(() -> readLogs(cs, startedProcess));
                } catch (RejectedExecutionException e) {
                    logger.warn("Log reader for '{}' rejected", name);
                }

                if (!startedProcess.isAlive()) {
                    handleExit(cs, startedProcess, runId);
                }
            } catch (IOException e) {
                logger.error("Failed to start server '{}': {}", name, e.getMessage());
            } finally {
                if (!committed) {
                    if (newStdin != null) {
                        try {
                            newStdin.close();
                        } catch (IOException ignored) {
                        }
                    }
                    if (process != null) {
                        try {
                            if (process.getInputStream() != null) {
                                process.getInputStream().close();
                            }
                        } catch (IOException ignored) {
                        }
                        try {
                            if (process.getOutputStream() != null) {
                                process.getOutputStream().close();
                            }
                        } catch (IOException ignored) {
                        }
                        destroyTree(process);
                    }
                    if (cs.state == State.STARTING) {
                        cs.state = shuttingDown ? State.STOPPED : State.CRASHED;
                    }
                }
            }
        }
    }

    private Path resolveWorkDir(String name) throws IOException {
        Path dir = serversDir.resolve(name).normalize();
        if (!dir.startsWith(serversDir)) {
            throw new IOException("work directory escapes the servers directory");
        }
        if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Path real = dir.toRealPath();
            if (!real.startsWith(serversDir.toRealPath())) {
                throw new IOException("work directory resolves outside the servers directory");
            }
            return real;
        }
        return dir;
    }

    private void readLogs(ChildServer cs, Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("Done (") || line.contains("Started server in")) {
                    boolean ready = false;
                    synchronized (cs.lock) {
                        if (cs.process == process && cs.state == State.STARTING) {
                            cs.state = State.RUNNING;
                            if (cs.startupTimeout != null) {
                                cs.startupTimeout.cancel(false);
                                cs.startupTimeout = null;
                            }
                            ready = true;
                        }
                    }
                    if (ready) {
                        logger.info("Server '{}' is ready", cs.name);
                    }
                }

                ServerConfig cfg = config.get(cs.name);
                boolean verbose = (cfg == null) || cfg.verbose();
                if (verbose || matchesImportantLine(line)) {
                    logger.info("[{}] {}", cs.name, line);
                }
            }
        } catch (IOException e) {
            if (!shuttingDown && process.isAlive()) {
                logger.warn("Log reader for '{}' failed: {}", cs.name, e.getMessage());
            }
        } catch (Throwable t) {
            logger.error("Log reader for '{}' crashed", cs.name, t);
        }
    }

    private static boolean matchesImportantLine(String line) {
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.contains("ERROR") || upper.contains("WARN") || upper.contains("EXCEPTION")
                || upper.contains("CAUSED BY:") || upper.contains("STARTED SERVER IN")
                || upper.contains("STOPPING THE SERVER") || upper.contains("STOPPING SERVER")) {
            return true;
        }
        if (line.contains("Done (")) {
            return true;
        }
        return line.contains("Starting minecraft server");
    }

    private void handleExit(ChildServer cs, Process process, long runId) {
        boolean restart = false;

        synchronized (cs.lock) {
            if (runId != cs.runId || cs.process != process) {
                // stale callback
                closeQuietly(process.getInputStream());
                closeQuietly(process.getOutputStream());
                return;
            }

            cs.process = null;
            BufferedWriter writer = cs.stdin;
            cs.stdin = null;
            if (writer != null) {
                synchronized (writer) {
                    closeQuietly(writer);
                }
            }

            if (cs.startupTimeout != null) {
                cs.startupTimeout.cancel(false);
                cs.startupTimeout = null;
            }

            int exitCode = -1;
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException ignored) {
            }

            boolean manual = cs.manualStop;
            cs.manualStop = false;
            cs.startedAt = 0L;

            closeQuietly(process.getInputStream());
            closeQuietly(process.getOutputStream());

            boolean pendingRestart = cs.restartPending && !shuttingDown && config.containsKey(cs.name);
            cs.restartPending = false;

            if (pendingRestart) {
                cs.state = State.STOPPED;
                restart = true;
                logger.info("Server '{}' stopped (exit {}), restart scheduled", cs.name, exitCode);
            } else if (manual || shuttingDown) {
                cs.state = State.STOPPED;
                if (!shuttingDown) {
                    logger.info("Server '{}' stopped (exit {})", cs.name, exitCode);
                }
            } else {
                cs.state = State.CRASHED;
                logger.warn("Server '{}' crashed (exit {})", cs.name, exitCode);
            }

            if (!config.containsKey(cs.name) && !restart) {
                servers.remove(cs.name, cs);
            }
        }

        if (restart) {
            logger.info("Restarting '{}' in {}s", cs.name, RESTART_DELAY_SEC);
            scheduleStart(cs.name, RESTART_DELAY_SEC);
        }
    }

    private StopResult stopServer(String name, boolean forRestart) {
        ChildServer cs = servers.get(name);
        if (cs == null) {
            return StopResult.NOT_RUNNING;
        }

        boolean starting;
        Process process;
        BufferedWriter stdin;

        synchronized (cs.lock) {
            process = cs.process;
            if (process == null || !process.isAlive()) {
                return StopResult.NOT_RUNNING;
            }
            if (cs.state == State.STOPPING) {
                cs.restartPending = forRestart;
                return StopResult.ALREADY_STOPPING;
            }

            starting = cs.state == State.STARTING;
            cs.restartPending = forRestart;
            cs.manualStop = true;
            cs.state = State.STOPPING;
            stdin = cs.stdin;

            scheduleStopTimeout(cs, process);
        }

        try {
            sendCommand(name, stdin, "stop");
        } catch (IOException e) {
            logger.warn("Failed to send stop to '{}': {}", name, e.getMessage());
        }

        return starting ? StopResult.CANCELLED_START : StopResult.REQUESTED;
    }

    private void scheduleStopTimeout(ChildServer cs, Process process) {
        try {
            scheduler.schedule(() -> {
                synchronized (cs.lock) {
                    if (cs.process == process && process.isAlive()) {
                        logger.warn("Server '{}' stop timeout ({}s), destroying", cs.name, STOP_TIMEOUT_SECONDS);
                        destroyTree(process);
                    }
                }
                try {
                    scheduler.schedule(() -> {
                        synchronized (cs.lock) {
                            if (cs.process == process && process.isAlive()) {
                                logger.warn("Server '{}' still alive, force killing", cs.name);
                                process.destroyForcibly();
                            }
                        }
                    }, 5, TimeUnit.SECONDS);
                } catch (RejectedExecutionException ignored) {
                }
            }, STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void sendCommand(String name, BufferedWriter stdin, String command) throws IOException {
        if (stdin == null) {
            throw new IOException("stdin is not available");
        }
        synchronized (stdin) {
            stdin.write(command);
            stdin.newLine();
            stdin.flush();
        }
    }

    private static void destroyTree(Process process) {
        try {
            process.descendants().forEach(handle -> {
                try {
                    handle.destroy();
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }
        try {
            process.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static List<String> splitCommand(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean hasToken = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                hasToken = true;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                hasToken = true;
                continue;
            }
            if (Character.isWhitespace(c) && !inSingle && !inDouble) {
                if (hasToken) {
                    tokens.add(current.toString());
                    current.setLength(0);
                    hasToken = false;
                }
                continue;
            }
            current.append(c);
            hasToken = true;
        }

        if (hasToken) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    // Command handlers

    private boolean known(String name) {
        return config.containsKey(name) || servers.containsKey(name);
    }

    private static Component unknown(String name) {
        return Component.text("Unknown server '" + name + "'.", NamedTextColor.RED);
    }

    private static boolean hasPermission(CommandSource source) {
        return source.hasPermission(PERM_ADMIN)
                || source.hasPermission(PERM_USE)
                || source.hasPermission(PERM_LIST)
                || source.hasPermission(PERM_STATUS)
                || source.hasPermission(PERM_START)
                || source.hasPermission(PERM_STOP)
                || source.hasPermission(PERM_RESTART)
                || source.hasPermission(PERM_RELOAD)
                || source.hasPermission(PERM_CONSOLE);
    }

    private boolean can(CommandSource source, String permission) {
        if (source.hasPermission(PERM_ADMIN) || source.hasPermission(permission)) {
            return true;
        }
        source.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
        return false;
    }

    private void doList(CommandSource source) {
        if (!can(source, PERM_LIST)) {
            return;
        }

        Set<String> names = new TreeSet<>(config.keySet());
        names.addAll(servers.keySet());

        if (names.isEmpty()) {
            source.sendMessage(Component.text("No servers configured.", NamedTextColor.YELLOW));
            return;
        }

        source.sendMessage(Component.text("Servers:", NamedTextColor.GRAY));
        for (String name : names) {
            ChildServer cs = servers.get(name);
            State state = cs == null ? State.STOPPED : cs.state;
            source.sendMessage(Component.text("  - " + name + ": ", NamedTextColor.GRAY)
                    .append(Component.text(state.name(), stateColor(state))));
        }
    }

    private void doStatus(CommandSource source, String name) {
        if (!can(source, PERM_STATUS)) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (!known(key)) {
            source.sendMessage(unknown(name));
            return;
        }

        ChildServer cs = servers.get(key);
        State state = cs == null ? State.STOPPED : cs.state;
        source.sendMessage(Component.text("Server '" + key + "': ", NamedTextColor.GRAY)
                .append(Component.text(state.name(), stateColor(state))));

        Process process = cs == null ? null : cs.process;
        if (process != null && process.isAlive()) {
            long uptime = cs.startedAt > 0 ? (System.currentTimeMillis() - cs.startedAt) / 1000 : 0;
            source.sendMessage(Component.text("  PID: " + process.pid()
                    + ", uptime: " + formatDuration(uptime), NamedTextColor.DARK_GRAY));
        }

        ServerConfig cfg = config.get(key);
        if (cfg != null) {
            source.sendMessage(Component.text("  auto-start: " + cfg.enabled()
                    + ", verbose: " + cfg.verbose(), NamedTextColor.DARK_GRAY));
        }
    }

    private void doStart(CommandSource source, String name) {
        if (!can(source, PERM_START)) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (!config.containsKey(key)) {
            source.sendMessage(unknown(name));
            return;
        }

        ChildServer cs = servers.get(key);
        Process process = cs == null ? null : cs.process;
        if (process != null && process.isAlive()) {
            source.sendMessage(Component.text("Server '" + key + "' is already running.", NamedTextColor.YELLOW));
            return;
        }

        source.sendMessage(Component.text("Starting server '" + key + "'...", NamedTextColor.YELLOW));
        scheduleStart(key, 0);
    }

    private void doStop(CommandSource source, String name) {
        if (!can(source, PERM_STOP)) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (!known(key)) {
            source.sendMessage(unknown(name));
            return;
        }

        StopResult result = stopServer(key, false);
        switch (result) {
            case REQUESTED:
                source.sendMessage(Component.text("Stopping server '" + key + "'...", NamedTextColor.YELLOW));
                break;
            case ALREADY_STOPPING:
                source.sendMessage(Component.text("Server '" + key + "' is already stopping.", NamedTextColor.YELLOW));
                break;
            case CANCELLED_START:
                source.sendMessage(Component.text("Cancelled startup of '" + key + "'.", NamedTextColor.YELLOW));
                break;
            case NOT_RUNNING:
            default:
                source.sendMessage(Component.text("Server '" + key + "' is not running.", NamedTextColor.RED));
                break;
        }
    }

    private void doRestart(CommandSource source, String name) {
        if (!can(source, PERM_RESTART)) {
            return;
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (!known(key)) {
            source.sendMessage(unknown(name));
            return;
        }

        ChildServer cs = servers.get(key);
        Process process = cs == null ? null : cs.process;
        boolean running = process != null && process.isAlive();

        if (!running) {
            if (!config.containsKey(key)) {
                source.sendMessage(Component.text("Server '" + key + "' is no longer configured and cannot be started.",
                        NamedTextColor.RED));
                return;
            }
            source.sendMessage(Component.text("Server '" + key + "' is not running; starting it instead.",
                    NamedTextColor.YELLOW));
            scheduleStart(key, 0);
            return;
        }

        StopResult result = stopServer(key, true);
        switch (result) {
            case REQUESTED:
            case CANCELLED_START:
                source.sendMessage(Component.text("Restarting server '" + key + "'...", NamedTextColor.YELLOW));
                break;
            case ALREADY_STOPPING:
                source.sendMessage(Component.text("Server '" + key + "' is stopping; it will restart afterwards.",
                        NamedTextColor.YELLOW));
                break;
            case NOT_RUNNING:
            default:
                source.sendMessage(Component.text("Server '" + key + "' is not running.", NamedTextColor.RED));
                break;
        }
    }

    private void doCmd(CommandSource source, String name, String command) {
        if (!can(source, PERM_CONSOLE)) {
            return;
        }

        String key = name.toLowerCase(Locale.ROOT);
        if (!known(key)) {
            source.sendMessage(unknown(name));
            return;
        }
        if (command == null || command.trim().isEmpty()) {
            source.sendMessage(Component.text("Command must not be empty.", NamedTextColor.RED));
            return;
        }

        ChildServer cs = servers.get(key);
        Process process = cs == null ? null : cs.process;
        BufferedWriter stdin = cs == null ? null : cs.stdin;

        if (process == null || !process.isAlive() || stdin == null) {
            source.sendMessage(Component.text("Server '" + key + "' is not running; cannot send the command.",
                    NamedTextColor.RED));
            return;
        }

        boolean stopCommand = command.trim().equalsIgnoreCase("stop");
        boolean previousManualStop = cs.manualStop;
        if (stopCommand) {
            cs.manualStop = true;
        }

        try {
            sendCommand(key, stdin, command);
        } catch (IOException e) {
            if (stopCommand) {
                cs.manualStop = previousManualStop;
            }
            source.sendMessage(Component.text("Failed to send command to '" + key + "': " + e.getMessage(),
                    NamedTextColor.RED));
            logger.warn("Failed to send command to '{}': {}", key, e.getMessage());
            return;
        }

        if (stopCommand) {
            synchronized (cs.lock) {
                cs.restartPending = false;
                if (cs.process == process
                        && (cs.state == State.RUNNING || cs.state == State.STARTING)) {
                    cs.state = State.STOPPING;
                }
            }
            scheduleStopTimeout(cs, process);
        }

        Component message = Component.text("Sent command to '" + key + "': " + command, NamedTextColor.GREEN);
        source.sendMessage(message);
        logger.info("Sent command to '{}': {}", key, command);
    }

    private void doReload(CommandSource source) {
        if (!can(source, PERM_RELOAD)) {
            return;
        }

        try {
            scheduler.execute(() -> {
                loadConfig();
                for (String name : config.keySet()) {
                    servers.computeIfAbsent(name, ChildServer::new);
                }
                for (String name : new ArrayList<>(servers.keySet())) {
                    if (config.containsKey(name)) {
                        continue;
                    }
                    ChildServer cs = servers.get(name);
                    if (cs == null) {
                        continue;
                    }
                    Process process = cs.process;
                    if (process == null || !process.isAlive()) {
                        servers.remove(name, cs);
                    } else {
                        logger.info("Server '{}' removed from config, still running", name);
                    }
                }

                source.sendMessage(Component.text("Config reloaded, " + config.size() + " server(s)",
                        NamedTextColor.GREEN));
                logger.info("Config reloaded, {} server(s)", config.size());
            });
        } catch (RejectedExecutionException e) {
            source.sendMessage(Component.text("Cannot reload right now: scheduler is shutting down.",
                    NamedTextColor.RED));
        }
    }

    private void sendHelp(CommandSource source) {
        source.sendMessage(Component.text("Supervisor commands:", NamedTextColor.GOLD));
        source.sendMessage(helpLine("/supervisor list", "list servers"));
        source.sendMessage(helpLine("/supervisor status <server>", "show server status"));
        source.sendMessage(helpLine("/supervisor start <server>", "start a server"));
        source.sendMessage(helpLine("/supervisor stop <server>", "stop a server"));
        source.sendMessage(helpLine("/supervisor restart <server>", "restart a server"));
        source.sendMessage(helpLine("/supervisor cmd <server> <command>", "send command to server console"));
        source.sendMessage(helpLine("/supervisor reload", "reload config"));
    }

    private static Component helpLine(String usage, String description) {
        return Component.text("  " + usage, NamedTextColor.WHITE)
                .append(Component.text(" - " + description, NamedTextColor.GRAY));
    }

    private static NamedTextColor stateColor(State state) {
        switch (state) {
            case RUNNING:
                return NamedTextColor.GREEN;
            case STARTING:
                return NamedTextColor.YELLOW;
            case STOPPING:
                return NamedTextColor.GOLD;
            case CRASHED:
                return NamedTextColor.RED;
            case STOPPED:
            default:
                return NamedTextColor.GRAY;
        }
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    // Velocity command

    private final class SupervisorCommand implements SimpleCommand {

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            String[] args = invocation.arguments();

            if (!hasPermission(invocation)) {
                source.sendMessage(Component.text("You do not have permission to use Supervisor.",
                        NamedTextColor.RED));
                return;
            }

            if (args.length == 0) {
                sendHelp(source);
                return;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "help":
                    sendHelp(source);
                    break;
                case "list":
                    doList(source);
                    break;
                case "status":
                    if (args.length < 2) {
                        source.sendMessage(Component.text("Usage: /supervisor status <server>", NamedTextColor.RED));
                        break;
                    }
                    doStatus(source, args[1]);
                    break;
                case "start":
                    if (args.length < 2) {
                        source.sendMessage(Component.text("Usage: /supervisor start <server>", NamedTextColor.RED));
                        break;
                    }
                    doStart(source, args[1]);
                    break;
                case "stop":
                    if (args.length < 2) {
                        source.sendMessage(Component.text("Usage: /supervisor stop <server>", NamedTextColor.RED));
                        break;
                    }
                    doStop(source, args[1]);
                    break;
                case "restart":
                    if (args.length < 2) {
                        source.sendMessage(Component.text("Usage: /supervisor restart <server>", NamedTextColor.RED));
                        break;
                    }
                    doRestart(source, args[1]);
                    break;
                case "cmd":
                    if (args.length < 3) {
                        source.sendMessage(Component.text("Usage: /supervisor cmd <server> <command>",
                                NamedTextColor.RED));
                        break;
                    }
                    String command = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                    doCmd(source, args[1], command);
                    break;
                case "reload":
                    doReload(source);
                    break;
                default:
                    source.sendMessage(Component.text("Unknown subcommand. Use /supervisor help.",
                            NamedTextColor.RED));
                    break;
            }
        }

        @Override
        public List<String> suggest(Invocation invocation) {
            String[] args = invocation.arguments();

            if (args.length <= 1) {
                String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
                return SUBCOMMANDS.stream()
                        .filter(sub -> sub.startsWith(prefix))
                        .sorted()
                        .collect(Collectors.toList());
            }

            if (args.length == 2) {
                String sub = args[0].toLowerCase(Locale.ROOT);
                if (SERVER_SUBCOMMANDS.contains(sub)) {
                    String prefix = args[1].toLowerCase(Locale.ROOT);
                    return servers.keySet().stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                            .sorted()
                            .collect(Collectors.toList());
                }
            }

            return Collections.emptyList();
        }
    }

    // Types

    private enum State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
        CRASHED
    }

    private enum StopResult {
        REQUESTED,
        ALREADY_STOPPING,
        CANCELLED_START,
        NOT_RUNNING
    }

    private static final class ServerConfig {
        private final boolean enabled;
        private final String command;
        private final boolean verbose;

        private ServerConfig(boolean enabled, String command, boolean verbose) {
            this.enabled = enabled;
            this.command = command;
            this.verbose = verbose;
        }

        private boolean enabled() {
            return enabled;
        }

        private String command() {
            return command;
        }

        private boolean verbose() {
            return verbose;
        }
    }

    private static final class ChildServer {
        private final String name;
        private final Object lock = new Object();

        private volatile Process process;
        private volatile BufferedWriter stdin;
        private volatile State state = State.STOPPED;
        private volatile boolean manualStop;
        private volatile boolean restartPending;
        private volatile long startedAt;
        private volatile long runId;
        private volatile ScheduledFuture<?> startupTimeout;
        private volatile Path workDir;

        private ChildServer(String name) {
            this.name = name;
        }
    }
}