package dev.jcode.display;

import android.content.ComponentName;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Launches the guest app onto our display and reports when it stops being there. */
public final class AppController {

    public interface Listener {
        void onAppGone(String packageName);
    }

    private static final int MAX_TASKS = 64;
    private static final long POLL_INTERVAL_MS = 1000L;

    /** Two consecutive misses before reporting, so a task transition is not mistaken for a death. */
    private static final int MISSES_BEFORE_GONE = 2;

    private final int displayId;
    private final Listener listener;

    private volatile String packageName;
    private volatile String component;
    private volatile boolean running;
    private Thread monitor;

    private Object activityTaskManager;
    private Method getTasks;
    private Object[] getTasksArgs;

    public AppController(int displayId, Listener listener) {
        this.displayId = displayId;
        this.listener = listener;
    }

    public String getPackageName() {
        return packageName;
    }

    public synchronized void launch(String pkg) throws IOException {
        String resolved = resolveLauncherActivity(pkg);
        if (resolved == null) {
            throw new IOException("no launcher activity for " + pkg);
        }
        stopMonitor();
        packageName = pkg;
        component = resolved;
        start();
        startMonitor();
    }

    public synchronized void restart() throws IOException {
        String pkg = packageName;
        if (pkg == null) {
            return;
        }
        stopMonitor();
        forceStop();
        start();
        startMonitor();
    }

    public synchronized void forceStop() throws IOException {
        String pkg = packageName;
        if (pkg != null) {
            exec("am", "force-stop", pkg);
        }
    }

    public synchronized void close() {
        stopMonitor();
    }

    private void start() throws IOException {
        String output = exec("am", "start",
                "-a", "android.intent.action.MAIN",
                "-c", "android.intent.category.LAUNCHER",
                "-n", component,
                "--display", Integer.toString(displayId),
                "-f", "0x10200000");
        if (output.contains("Error:")) {
            throw new IOException("am start failed: " + output.trim());
        }
    }

    private void startMonitor() {
        running = true;
        monitor = new Thread(this::poll, "jcode-app-monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    private void stopMonitor() {
        running = false;
        Thread current = monitor;
        monitor = null;
        if (current != null) {
            current.interrupt();
        }
    }

    private void poll() {
        String watched = packageName;
        int misses = 0;
        boolean reportedUnavailable = false;
        while (running) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!running) {
                return;
            }
            Boolean present = isOnDisplay(watched);
            if (present == null) {
                if (!reportedUnavailable) {
                    reportedUnavailable = true;
                    System.err.println("jcode-display: task monitoring unavailable");
                }
                continue;
            }
            misses = present ? 0 : misses + 1;
            if (misses >= MISSES_BEFORE_GONE) {
                running = false;
                listener.onAppGone(watched);
                return;
            }
        }
    }

    /** {@code null} means the task list could not be read, which is not evidence of anything. */
    private Boolean isOnDisplay(String pkg) {
        try {
            for (Object task : tasks()) {
                Object taskDisplay = Workarounds.readField(task, "displayId");
                if (!(taskDisplay instanceof Integer) || (Integer) taskDisplay != displayId) {
                    continue;
                }
                if (pkg.equals(packageOf(task, "topActivity"))
                        || pkg.equals(packageOf(task, "baseActivity"))) {
                    return Boolean.TRUE;
                }
            }
            return Boolean.FALSE;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String packageOf(Object task, String field) throws ReflectiveOperationException {
        Object value = Workarounds.readField(task, field);
        return value instanceof ComponentName ? ((ComponentName) value).getPackageName() : null;
    }

    private synchronized List<?> tasks() throws ReflectiveOperationException {
        if (getTasks == null) {
            bindActivityTaskManager();
        }
        return (List<?>) getTasks.invoke(activityTaskManager, getTasksArgs);
    }

    /**
     * {@code IActivityTaskManager.getTasks} has picked up extra parameters over time
     * ({@code keepIntentExtra} in API 33, {@code displayId} in API 34), so the overload is selected
     * at runtime and its arguments are filled by type: the first int is the task limit, any further
     * int is a display filter left at INVALID_DISPLAY, and every boolean defaults to false.
     */
    private void bindActivityTaskManager() throws ReflectiveOperationException {
        Object service = Workarounds.invokeStatic("android.app.ActivityTaskManager", "getService");
        Method chosen = null;
        for (Method method : service.getClass().getMethods()) {
            if (!"getTasks".equals(method.getName())
                    || !List.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            if (chosen == null
                    || method.getParameterTypes().length < chosen.getParameterTypes().length) {
                chosen = method;
            }
        }
        if (chosen == null) {
            throw new NoSuchMethodException("IActivityTaskManager.getTasks");
        }
        Class<?>[] parameters = chosen.getParameterTypes();
        Object[] args = new Object[parameters.length];
        boolean firstInt = true;
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i] == int.class) {
                args[i] = firstInt ? MAX_TASKS : -1;
                firstInt = false;
            } else if (parameters[i] == boolean.class) {
                args[i] = Boolean.FALSE;
            } else {
                args[i] = null;
            }
        }
        chosen.setAccessible(true);
        activityTaskManager = service;
        getTasks = chosen;
        getTasksArgs = args;
    }

    private static String resolveLauncherActivity(String pkg) throws IOException {
        String output = exec("cmd", "package", "resolve-activity", "--brief", pkg);
        String resolved = null;
        for (String line : output.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.indexOf('/') > 0 && !trimmed.contains(" ")) {
                resolved = trimmed;
            }
        }
        return resolved;
    }

    private static String exec(String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        process.getOutputStream().close();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out.toString();
    }
}
