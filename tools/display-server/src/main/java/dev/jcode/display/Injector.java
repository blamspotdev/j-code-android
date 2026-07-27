package dev.jcode.display;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.lang.reflect.Method;

/**
 * Injects touch, key and text events into one specific display.
 *
 * <p>The client only ever says "pointer 7 went down here". Pointer slots, indices and the
 * ACTION_POINTER_DOWN/UP encoding are computed here, because a client that gets those wrong can
 * wedge the system's input dispatcher for every app on the device.
 */
public final class Injector {

    private static final int INJECT_INPUT_EVENT_MODE_ASYNC = 0;

    private final int displayId;
    private final Object inputManager;
    private final Method injectInputEvent;
    private final Method setDisplayId;
    private final KeyCharacterMap keyCharacterMap =
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);

    private final MotionEvent.PointerProperties[] properties =
            new MotionEvent.PointerProperties[Protocol.MAX_POINTERS];
    private final MotionEvent.PointerCoords[] coordinates =
            new MotionEvent.PointerCoords[Protocol.MAX_POINTERS];
    private final int[] clientIds = new int[Protocol.MAX_POINTERS];

    private int pointerCount;
    private long downTime;

    public Injector(int displayId) throws ReflectiveOperationException {
        this.displayId = displayId;
        this.inputManager = inputManager();
        this.injectInputEvent = inputManager.getClass()
                .getMethod("injectInputEvent", InputEvent.class, int.class);
        this.injectInputEvent.setAccessible(true);
        this.setDisplayId = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
        this.setDisplayId.setAccessible(true);
        for (int i = 0; i < Protocol.MAX_POINTERS; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            coordinates[i] = new MotionEvent.PointerCoords();
        }
    }

    /**
     * API 34 moved {@code injectInputEvent} from {@code InputManager} to {@code InputManagerGlobal}
     * and turned the old accessor into a no-longer-present static. Try the new home first so this
     * keeps working when the device updates.
     */
    private static Object inputManager() throws ReflectiveOperationException {
        try {
            return Workarounds.invokeStatic(
                    "android.hardware.input.InputManagerGlobal", "getInstance");
        } catch (ReflectiveOperationException e) {
            return Workarounds.invokeStatic("android.hardware.input.InputManager", "getInstance");
        }
    }

    public synchronized void touch(int action, int clientPointerId, int x, int y,
                                   float pressure, int buttonState) {
        switch (action) {
            case Protocol.TOUCH_DOWN:
                pointerDown(clientPointerId, x, y, pressure, buttonState);
                break;
            case Protocol.TOUCH_MOVE:
                pointerMove(clientPointerId, x, y, pressure, buttonState);
                break;
            case Protocol.TOUCH_UP:
                pointerUp(clientPointerId, x, y, pressure, buttonState);
                break;
            case Protocol.TOUCH_CANCEL:
                cancelAll();
                break;
            default:
                break;
        }
    }

    public synchronized void key(int action, int keyCode, int repeat, int metaState) {
        long now = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(now, now,
                action == Protocol.KEY_UP ? KeyEvent.ACTION_UP : KeyEvent.ACTION_DOWN,
                keyCode, repeat, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD);
        inject(event);
    }

    public synchronized void text(String text) {
        KeyEvent[] events = keyCharacterMap.getEvents(text.toCharArray());
        if (events == null) {
            return;
        }
        for (KeyEvent event : events) {
            inject(event);
        }
    }

    /** Synthesises ACTION_CANCEL for anything still down, so the guest app never sees a stuck touch. */
    public synchronized void cancelAll() {
        if (pointerCount == 0) {
            return;
        }
        send(MotionEvent.ACTION_CANCEL, SystemClock.uptimeMillis(), 0);
        pointerCount = 0;
    }

    private void pointerDown(int clientPointerId, int x, int y, float pressure, int buttonState) {
        int index = indexOf(clientPointerId);
        if (index < 0) {
            if (pointerCount == Protocol.MAX_POINTERS) {
                return;
            }
            index = pointerCount++;
            clientIds[index] = clientPointerId;
            properties[index].id = nextLocalId(index);
            properties[index].toolType = MotionEvent.TOOL_TYPE_FINGER;
        }
        set(index, x, y, pressure);
        long now = SystemClock.uptimeMillis();
        if (pointerCount == 1) {
            downTime = now;
            send(MotionEvent.ACTION_DOWN, now, buttonState);
        } else {
            send(MotionEvent.ACTION_POINTER_DOWN
                    | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT), now, buttonState);
        }
    }

    private void pointerMove(int clientPointerId, int x, int y, float pressure, int buttonState) {
        int index = indexOf(clientPointerId);
        if (index < 0) {
            return;
        }
        set(index, x, y, pressure);
        send(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), buttonState);
    }

    private void pointerUp(int clientPointerId, int x, int y, float pressure, int buttonState) {
        int index = indexOf(clientPointerId);
        if (index < 0) {
            return;
        }
        set(index, x, y, pressure);
        long now = SystemClock.uptimeMillis();
        if (pointerCount == 1) {
            send(MotionEvent.ACTION_UP, now, buttonState);
        } else {
            send(MotionEvent.ACTION_POINTER_UP
                    | (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT), now, buttonState);
        }
        remove(index);
    }

    private void send(int action, long eventTime, int buttonState) {
        MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, pointerCount,
                properties, coordinates, 0, buttonState, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            inject(event);
        } finally {
            event.recycle();
        }
    }

    private void inject(InputEvent event) {
        try {
            setDisplayId.invoke(event, displayId);
            injectInputEvent.invoke(inputManager, event, INJECT_INPUT_EVENT_MODE_ASYNC);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("input injection failed", e);
        }
    }

    private void set(int index, int x, int y, float pressure) {
        MotionEvent.PointerCoords coords = coordinates[index];
        coords.clear();
        coords.x = x;
        coords.y = y;
        coords.pressure = pressure;
        coords.size = 1f;
    }

    private void remove(int index) {
        MotionEvent.PointerProperties props = properties[index];
        MotionEvent.PointerCoords coords = coordinates[index];
        for (int i = index; i < pointerCount - 1; i++) {
            properties[i] = properties[i + 1];
            coordinates[i] = coordinates[i + 1];
            clientIds[i] = clientIds[i + 1];
        }
        // Recycle the removed slot objects at the tail so the arrays stay fully populated.
        properties[pointerCount - 1] = props;
        coordinates[pointerCount - 1] = coords;
        pointerCount--;
    }

    private int indexOf(int clientPointerId) {
        for (int i = 0; i < pointerCount; i++) {
            if (clientIds[i] == clientPointerId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Pointer ids must stay stable for the lifetime of a gesture even when an earlier pointer is
     * lifted and the remaining ones shift down an index, so a free id is picked rather than reusing
     * the slot index.
     */
    private int nextLocalId(int upTo) {
        for (int candidate = 0; candidate < Protocol.MAX_POINTERS; candidate++) {
            boolean used = false;
            for (int i = 0; i < upTo; i++) {
                if (properties[i].id == candidate) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return candidate;
            }
        }
        return upTo;
    }
}
