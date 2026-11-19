// lang: Java, file: EventBus.java, target: Fabric MC 26.3
// Annotation-driven event bus. Listeners register with @EventHandler on methods.
package com.veil.event;

import com.veil.Veil;
import com.veil.util.InputLock;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventBus {

    private final Map<Class<?>, CopyOnWriteArrayList<ListenerEntry>> listeners = new ConcurrentHashMap<>();

    /** Handlers that have already thrown, so a broken per-tick handler is reported once, not 20×/s. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /**
     * Register all @EventHandler methods on the given subscriber.
     *
     * <p>Registering the same subscriber twice is a no-op for the handlers it already has. Without
     * that, a module that also called {@code subscribe} from its own {@code onEnable} got every
     * handler installed twice and ran its tick body twice on every tick.
     */
    public void subscribe(Object subscriber) {
        for (Method method : subscriber.getClass().getDeclaredMethods()) {
            if (!method.isAnnotationPresent(EventHandler.class)) continue;
            if (method.getParameterCount() != 1) continue;
            Class<?> eventType = method.getParameterTypes()[0];
            method.setAccessible(true);
            CopyOnWriteArrayList<ListenerEntry> entries =
                    listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>());
            boolean present = false;
            for (ListenerEntry entry : entries) {
                if (entry.subscriber == subscriber && entry.method.equals(method)) { present = true; break; }
            }
            if (!present) entries.add(new ListenerEntry(subscriber, method));
        }
    }

    /** Unregister all @EventHandler methods on the given subscriber. */
    public void unsubscribe(Object subscriber) {
        for (List<ListenerEntry> entries : listeners.values()) {
            entries.removeIf(e -> e.subscriber == subscriber);
        }
    }

    /**
     * Fire an event, calling all registered handlers. Returns the event.
     *
     * <p>The listener list is copied before dispatch: a handler is allowed to toggle a module, and
     * toggling subscribes or unsubscribes listeners on this same bus. Iterating the live list would
     * throw {@link java.util.ConcurrentModificationException} mid-tick and crash the client.
     *
     * <p>While a module holds {@link InputLock}, every module except the holder is skipped: it stops
     * ticking, stops seeing packets and stops reacting to keys, so nothing competes with the holder's
     * own inventory clicks. Non-module subscribers still receive events.
     */
    public <T> T post(T event) {
        CopyOnWriteArrayList<ListenerEntry> current = listeners.get(event.getClass());
        if (current == null || current.isEmpty()) return event;
        for (ListenerEntry entry : current) {   // iterates snapshot — zero allocation
            if (InputLock.blocks(entry.subscriber)) continue;
            try {
                entry.method.invoke(entry.subscriber, event);
            } catch (Exception ex) {
                // One handler throwing must not stop the others, but it must also not vanish into
                // stderr with no idea which module it was: name it, and say it once.
                String who = entry.subscriber.getClass().getSimpleName() + "#" + entry.method.getName();
                if (reported.add(who)) {
                    Veil.LOGGER.error("{} failed while handling {}", who, event.getClass().getSimpleName(), ex);
                }
            }
        }
        return event;
    }

    private record ListenerEntry(Object subscriber, Method method) {}
}
