package com.r5ylx.events;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;


/**
 * The place where posting and subscribing to events come together.
 *
 * <h2>Dispatch rules</h2>
 * <ul>
 *   <li>An event reaches the listeners registered on its own type and on every supertype
 *       and interface.</li>
 *   <li>The order is decided by priority alone, <b>regardless of type</b>. A {@code HIGHEST}
 *       registered on a supertype runs before a {@code LOW} registered on the concrete type.</li>
 *   <li>Listeners of the same priority run in registration order.</li>
 *   <li>If the event implements {@link ICancellable} and is cancelled, dispatch ends
 *       <b>completely</b> at that point. Listeners on supertypes are not reached either.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * <p>Every operation is thread-safe. {@link #post(Object)} takes no lock once the dispatch table
 * is warm. If you subscribe or unsubscribe during a dispatch, whether that change is seen by that
 * dispatch is unspecified. It is always seen from the next {@code post} onwards.
 *
 * <h2>Exceptions</h2>
 * <p>An exception thrown by a listener is handed to the {@link EventExceptionHandler}, which
 * rethrows it by default. To swallow it instead, replace the handler with
 * {@link #setExceptionHandler(EventExceptionHandler)}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * EventBus bus = new EventBus();
 *
 * // subscribe with an annotation
 * class Listener {
 *     @Subscribe(priority = EventPriority.HIGH)
 *     void onTick(TickEvent event) { ... }
 * }
 * bus.subscribe(new Listener());
 *
 * // subscribe with a lambda and unsubscribe through the handle
 * Subscription sub = bus.subscribe(TickEvent.class, event -> ...);
 * sub.unsubscribe();
 *
 * bus.post(new TickEvent());
 * }</pre>
 */
public final class EventBus {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /** Descending priority, and registration order within the same priority. */
    private static final Comparator<Listener> ORDER =
        Comparator.comparingInt(Listener::priority).reversed()
            .thenComparingLong(Listener::sequence);

    /** The {@code @Subscribe} methods of each class. The scan result is reused. */
    private static final Map<Class<?>, List<Method>> SUBSCRIBER_CACHE = new ConcurrentHashMap<>();

    /** Serializes every subscribe and unsubscribe. {@code post} does not touch it once the chain is warm. */
    private final Object mutationLock = new Object();

    /** The listeners of each subscribed type. Guarded by mutationLock. */
    private final Map<Class<?>, List<Listener>> byType = new LinkedHashMap<>();

    /** The listeners of each owner. IdentityHashMap, because lookup is by identity. Guarded by mutationLock. */
    private final Map<Object, List<Listener>> byOwner = new IdentityHashMap<>();

    /** Maps a concrete event type to the dispatch chain already sorted by priority. */
    private final Map<Class<?>, Listener[]> dispatchCache = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    private volatile EventExceptionHandler exceptionHandler = EventExceptionHandler.rethrowing();

    /** Creates a bus with the default settings. Exceptions are rethrown. */
    public EventBus() {
    }

    /**
     * Creates a bus with the given exception handler.
     *
     * @param exceptionHandler where exceptions thrown by listeners are sent
     */
    public EventBus(EventExceptionHandler exceptionHandler) {
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

    // ------------------------------------------------------------------ posting

    /**
     * Dispatches an event.
     *
     * @param event the event to dispatch
     * @param <T>   the type of the event
     * @return the same instance that was passed in, so the cancellation result can be read
     * @throws NullPointerException if event is null
     */
    public <T> T post(T event) {
        Objects.requireNonNull(event, "event");

        Listener[] chain = dispatchCache.get(event.getClass());

        if (chain == null) {
            chain = buildChain(event.getClass());
        }

        if (chain.length == 0) {
            return event;
        }

        ICancellable cancellable = event instanceof ICancellable c ? c : null;

        for (Listener listener : chain) {
            if (!listener.isActive() || !listener.isSubscribed()) {
                continue;
            }

            if (listener.isOnce() && !listener.tryConsume()) {
                continue;
            }

            try {
                listener.invoke(event);
            } catch (Error e) {
                // A VM level failure is propagated as it is, without going through the handler.
                throw e;
            } catch (Throwable t) {
                exceptionHandler.handle(event, listener, t);
            } finally {
                if (listener.isOnce()) {
                    listener.unsubscribe();
                }
            }

            if (cancellable != null && cancellable.isCancelled()) {
                break;
            }
        }

        return event;
    }

    // ------------------------------------------------------------------ subscribing (annotation)

    /**
     * Subscribes every method annotated with {@link Subscribe}.
     *
     * <p>Methods declared in supertypes and interfaces are included as well. Even when a method
     * declared in a supertype is overridden and the override is annotated too, only one is registered.
     *
     * <p>Does nothing if the object is already subscribed.
     *
     * @param owner the object to subscribe
     * @throws IllegalArgumentException if a method annotated with {@code @Subscribe} has the wrong shape
     */
    public void subscribe(Object owner) {
        Objects.requireNonNull(owner, "owner");

        List<Method> methods = SUBSCRIBER_CACHE.computeIfAbsent(owner.getClass(), EventBus::collectSubscribers);

        synchronized (mutationLock) {
            if (byOwner.containsKey(owner)) {
                return;
            }

            List<Listener> created = new ArrayList<>(methods.size());

            for (Method method : methods) {
                Subscribe meta = method.getAnnotation(Subscribe.class);
                created.add(new MethodListener(this, owner, method, bind(method, owner),
                    meta.priority().value(), meta.once(), sequence.getAndIncrement()));
            }

            register(owner, created);
        }
    }

    // ------------------------------------------------------------------ subscribing (lambda)

    /**
     * Subscribes with a lambda. The returned handle itself becomes the owner.
     *
     * @return the handle used to unsubscribe
     */
    public <T> Subscription subscribe(Class<T> eventType, Consumer<? super T> action) {
        return subscribe(null, eventType, action, EventPriority.NORMAL.value(), false);
    }

    /** Subscribes with a lambda at the given priority. */
    public <T> Subscription subscribe(Class<T> eventType, Consumer<? super T> action, EventPriority priority) {
        Objects.requireNonNull(priority, "priority");
        return subscribe(null, eventType, action, priority.value(), false);
    }

    /**
     * Subscribes with a lambda at the given owner and priority.
     *
     * <p>Passing an owner lets you handle the subscriptions together through
     * {@link #unsubscribe(Object)} and {@link #deactivate(Object)}.
     *
     * @param owner the owner. If null, the handle itself becomes the owner
     * @param once  when true, the subscription is cancelled automatically once it has run
     */
    public <T> Subscription subscribe(Object owner, Class<T> eventType, Consumer<? super T> action,
                                        EventPriority priority, boolean once) {
        Objects.requireNonNull(priority, "priority");
        return subscribe(owner, eventType, action, priority.value(), once);
    }

    /**
     * Subscribes with a lambda at the given numeric priority.
     *
     * @param priority the higher it is, the earlier it runs. See {@link EventPriority} for the usual steps
     */
    public <T> Subscription subscribe(Object owner, Class<T> eventType, Consumer<? super T> action,
                                        int priority, boolean once) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(action, "action");

        LambdaListener<T> listener =
            new LambdaListener<>(this, owner, eventType, action, priority, once, sequence.getAndIncrement());

        synchronized (mutationLock) {
            register(owner == null ? listener : owner, List.of(listener));
        }

        return listener;
    }

    // ------------------------------------------------------------------ unsubscribing

    /**
     * Cancels every subscription of the given owner.
     *
     * <p>The owner is matched by identity ({@code ==}). {@code equals} is not used.
     */
    public void unsubscribe(Object owner) {
        Objects.requireNonNull(owner, "owner");

        synchronized (mutationLock) {
            List<Listener> removed = byOwner.remove(owner);

            if (removed == null) {
                return;
            }

            for (Listener listener : removed) {
                listener.markUnsubscribed();
                List<Listener> bucket = byType.get(listener.eventType());

                if (bucket != null) {
                    bucket.remove(listener);

                    if (bucket.isEmpty()) {
                        byType.remove(listener.eventType());
                    }
                }
            }

            dispatchCache.clear();
        }
    }

    /** Cancels every subscription. */
    public void clear() {
        synchronized (mutationLock) {
            for (List<Listener> bucket : byType.values()) {
                for (Listener listener : bucket) {
                    listener.markUnsubscribed();
                }
            }

            byType.clear();
            byOwner.clear();
            dispatchCache.clear();
        }
    }

    // ------------------------------------------------------------------ suspending

    /** Brings the listeners of the given owner back into the dispatch. */
    public void activate(Object owner) {
        setActive(owner, true);
    }

    /** Takes the listeners of the given owner out of the dispatch, while keeping them registered. */
    public void deactivate(Object owner) {
        setActive(owner, false);
    }

    /** Switches the listeners of the given owner on or off together. */
    public void setActive(Object owner, boolean active) {
        Objects.requireNonNull(owner, "owner");

        synchronized (mutationLock) {
            List<Listener> listeners = byOwner.get(owner);

            if (listeners == null) {
                return;
            }

            for (Listener listener : listeners) {
                listener.setActive(active);
            }
        }
    }

    // ------------------------------------------------------------------ queries

    /**
     * Returns whether there is at least one listener that receives the given event type.
     *
     * <p>Registrations on supertypes and interfaces are counted too, so this agrees with who
     * {@code post} would actually reach. Useful for skipping the creation of an expensive event.
     */
    public boolean hasListeners(Class<?> eventType) {
        return listenerCount(eventType) > 0;
    }

    /**
     * Returns how many listeners receive the given event type.
     * As with {@link #hasListeners(Class)}, registrations on supertypes are counted too.
     */
    public int listenerCount(Class<?> eventType) {
        Objects.requireNonNull(eventType, "eventType");

        Listener[] chain = dispatchCache.get(eventType);

        return (chain == null ? buildChain(eventType) : chain).length;
    }

    /** Returns the subscriptions of the given owner, in registration order. */
    public List<Subscription> subscriptionsOf(Object owner) {
        Objects.requireNonNull(owner, "owner");

        synchronized (mutationLock) {
            List<Listener> listeners = byOwner.get(owner);
            return listeners == null ? List.of() : List.copyOf(listeners);
        }
    }

    /** The current exception handler. */
    public EventExceptionHandler getExceptionHandler() {
        return exceptionHandler;
    }

    /** Replaces the exception handler. Safe to call during a dispatch. */
    public void setExceptionHandler(EventExceptionHandler exceptionHandler) {
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

    // ------------------------------------------------------------------ internals

    /** Call this while holding mutationLock. */
    private void register(Object owner, List<Listener> listeners) {
        assert Thread.holdsLock(mutationLock);

        if (listeners.isEmpty()) {
            byOwner.putIfAbsent(owner, new ArrayList<>());
            return;
        }

        for (Listener listener : listeners) {
            byType.computeIfAbsent(listener.eventType(), k -> new ArrayList<>()).add(listener);
        }

        byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).addAll(listeners);
        dispatchCache.clear();
    }

    /** Called from {@link Listener#unsubscribe()}. */
    void remove(Listener listener) {
        synchronized (mutationLock) {
            if (!listener.isSubscribed()) {
                return;
            }

            listener.markUnsubscribed();

            List<Listener> bucket = byType.get(listener.eventType());

            if (bucket != null) {
                bucket.remove(listener);

                if (bucket.isEmpty()) {
                    byType.remove(listener.eventType());
                }
            }

            List<Listener> owned = byOwner.get(listener.owner());

            if (owned != null) {
                owned.remove(listener);

                if (owned.isEmpty()) {
                    byOwner.remove(listener.owner());
                }
            }

            dispatchCache.clear();
        }
    }

    /**
     * Lines the listeners that receive the given event type into a single chain across types.
     * Supertypes and interfaces are picked up in one pass with {@link Class#isAssignableFrom(Class)}.
     */
    private Listener[] buildChain(Class<?> eventType) {
        synchronized (mutationLock) {
            Listener[] cached = dispatchCache.get(eventType);

            if (cached != null) {
                return cached;
            }

            List<Listener> collected = new ArrayList<>();

            for (Map.Entry<Class<?>, List<Listener>> entry : byType.entrySet()) {
                if (entry.getKey().isAssignableFrom(eventType)) {
                    collected.addAll(entry.getValue());
                }
            }

            collected.sort(ORDER);

            Listener[] chain = collected.toArray(Listener[]::new);
            dispatchCache.put(eventType, chain);

            return chain;
        }
    }

    /**
     * Collects the methods annotated with {@code @Subscribe} one by one, preferring the derived side.
     * When a method declared in a supertype is overridden, only the one on the subclass is registered.
     */
    private static List<Method> collectSubscribers(Class<?> type) {
        Map<String, Method> unique = new LinkedHashMap<>();

        for (Class<?> current : hierarchy(type)) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Subscribe.class) || method.isBridge() || method.isSynthetic()) {
                    continue;
                }

                validate(method);
                unique.putIfAbsent(signatureOf(method), method);
            }
        }

        return List.copyOf(unique.values());
    }

    /** Walks the classes and interfaces, starting from the derived side. */
    private static List<Class<?>> hierarchy(Class<?> type) {
        Set<Class<?>> visited = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(type);

        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();

            if (current == null || current == Object.class || !visited.add(current)) {
                continue;
            }

            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }

            for (Class<?> each : current.getInterfaces()) {
                queue.add(each);
            }
        }

        return List.copyOf(visited);
    }

    private static void validate(Method method) {
        if (method.getParameterCount() != 1) {
            throw new IllegalArgumentException(describe(method) + " must take exactly one parameter");
        }

        if (method.getReturnType() != void.class) {
            throw new IllegalArgumentException(describe(method) + " must return void");
        }

        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(describe(method) + " must not be static");
        }

        if (Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalArgumentException(describe(method) + " must not be abstract");
        }

        if (method.getParameterTypes()[0].isPrimitive()) {
            throw new IllegalArgumentException(describe(method) + " must not take a primitive parameter");
        }
    }

    private static String signatureOf(Method method) {
        StringBuilder builder = new StringBuilder(method.getName());

        for (Class<?> parameter : method.getParameterTypes()) {
            builder.append('/').append(parameter.getName());
        }

        return builder.toString();
    }

    private static String describe(Method method) {
        return "@Subscribe " + method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private static MethodHandle bind(Method method, Object owner) {
        try {
            if (!method.canAccess(owner)) {
                method.setAccessible(true);
            }

            return LOOKUP.unreflect(method).bindTo(owner);
        } catch (IllegalAccessException | InaccessibleObjectException e) {
            throw new IllegalArgumentException(describe(method) + " is not accessible", e);
        }
    }
}
