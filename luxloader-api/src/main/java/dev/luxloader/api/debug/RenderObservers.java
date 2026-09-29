package dev.luxloader.api.debug;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Process-local optional observer registry. The inactive path is one volatile read. */
public final class RenderObservers {
    private static final CopyOnWriteArrayList<RenderObserver> OBSERVERS = new CopyOnWriteArrayList<>();

    private RenderObservers() {
    }

    public static AutoCloseable register(RenderObserver observer) {
        if (observer == null) {
            throw new NullPointerException("observer");
        }
        OBSERVERS.addIfAbsent(observer);
        return () -> OBSERVERS.remove(observer);
    }

    public static boolean active() {
        return !OBSERVERS.isEmpty();
    }

    public static List<RenderObserver> current() {
        return List.copyOf(OBSERVERS);
    }
}
