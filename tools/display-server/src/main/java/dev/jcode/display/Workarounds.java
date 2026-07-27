package dev.jcode.display;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Everything needed to make framework APIs behave inside a bare {@code app_process} running as
 * uid 2000 (shell). This is the non-obvious part of the server; the rest is ordinary code.
 */
public final class Workarounds {

    /**
     * The package that actually belongs to uid 2000. AppOpsManager validates
     * {@code checkPackage(callingUid, packageName)} on the far side of several binder calls, so
     * every package name we hand to the system must be this one or the call is rejected.
     */
    private static final String SHELL_PACKAGE = "com.android.shell";

    private static Context shellContext;

    private Workarounds() {
    }

    /**
     * A system {@link Context} that reports {@code com.android.shell} as its package.
     *
     * <p>ActivityThread's system context reports package {@code android}, which belongs to uid 1000.
     * Since we run as uid 2000, {@code DisplayManager.createVirtualDisplay} throws
     * {@code SecurityException: packageName must match the calling uid} unless the name is
     * corrected.
     */
    public static synchronized Context shellContext() throws ReflectiveOperationException {
        if (shellContext == null) {
            shellContext = new ShellContext(systemContext());
        }
        return shellContext;
    }

    /**
     * Builds a {@link DisplayManager} bound to the supplied context.
     *
     * <p>Calling {@code context.getSystemService(DisplayManager.class)} is NOT enough:
     * {@code DisplayManagerGlobal.createVirtualDisplay} reads the package name off the
     * DisplayManager's own {@code mContext}, not off whatever context the caller holds. The manager
     * itself has to be constructed with the shell-package wrapper, which its only constructor
     * (package-private) allows via reflection.
     */
    public static DisplayManager displayManager(Context context) throws ReflectiveOperationException {
        Constructor<?> constructor = DisplayManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return (DisplayManager) constructor.newInstance(context);
    }

    /** Invokes a hidden static no-arg method, used to reach binder service singletons. */
    public static Object invokeStatic(String className, String methodName)
            throws ReflectiveOperationException {
        Method method = Class.forName(className).getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(null);
    }

    /** Reads a field that may be declared anywhere up the object's class hierarchy. */
    public static Object readField(Object target, String name) throws ReflectiveOperationException {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass());
    }

    private static final class ShellContext extends ContextWrapper {

        ShellContext(Context base) {
            super(base);
        }

        @Override
        public String getPackageName() {
            return SHELL_PACKAGE;
        }

        @Override
        public String getOpPackageName() {
            return SHELL_PACKAGE;
        }
    }

    /**
     * Obtains ActivityThread's system context.
     *
     * <p>{@code ActivityThread.systemMain()} is the documented-ish route but throws on some builds
     * (it assumes it is running in system_server). The fallback that works everywhere tried so far
     * is to prepare a main Looper, construct ActivityThread through its private no-arg constructor
     * and publish it as {@code sCurrentActivityThread} before asking for the system context.
     */
    private static Context systemContext() throws ReflectiveOperationException {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method getSystemContext = activityThread.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);

        try {
            Method systemMain = activityThread.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Context context = (Context) getSystemContext.invoke(systemMain.invoke(null));
            if (context != null) {
                return context;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Fall through to the constructor route.
        }

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        Constructor<?> constructor = activityThread.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object thread = constructor.newInstance();
        Field current = activityThread.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);
        return (Context) getSystemContext.invoke(thread);
    }
}
