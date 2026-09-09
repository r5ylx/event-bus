package com.r5ylx.events;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single registered listener. It doubles as the subscription handle itself.
 *
 * <p>Not public API. Callers work with it through {@link Subscription}.
 */
abstract class Listener implements Subscription {
    private final EventBus bus;
    private final Object owner;
    private final Class<?> eventType;
    private final int priority;
    private final boolean once;

    /** A running number that keeps registration order within the same priority. */
    private final long sequence;

    private final AtomicBoolean consumed = new AtomicBoolean();

    private volatile boolean active = true;
    private volatile boolean subscribed = true;

    Listener(EventBus bus, Object owner, Class<?> eventType, int priority, boolean once, long sequence) {
        this.bus = bus;
        this.owner = owner;
        this.eventType = eventType;
        this.priority = priority;
        this.once = once;
        this.sequence = sequence;
    }

    /** Calls the listener itself. event is guaranteed to be assignable to {@link #eventType()}. */
    abstract void invoke(Object event) throws Throwable;

    /**
     * Claims the right to run a one-shot listener.
     * Even when several threads dispatch at the same time, only one of them may run it.
     */
    boolean tryConsume() {
        return consumed.compareAndSet(false, true);
    }

    long sequence() {
        return sequence;
    }

    /** Marks this listener as unsubscribed. Called from EventBus. */
    void markUnsubscribed() {
        subscribed = false;
    }

    @Override
    public void unsubscribe() {
        bus.remove(this);
    }

    @Override
    public boolean isSubscribed() {
        return subscribed;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public Class<?> eventType() {
        return eventType;
    }

    /** If the listener was registered without an owner, this handle itself is the owner. */
    @Override
    public Object owner() {
        return owner == null ? this : owner;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isOnce() {
        return once;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
            + "[event=" + eventType.getSimpleName()
            + ", priority=" + priority
            + (once ? ", once" : "")
            + "]";
    }
}
