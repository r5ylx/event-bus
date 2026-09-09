package com.r5ylx.events;

/**
 * A handle to a single subscription.
 *
 * <p>Returned when you subscribe with a lambda. Subscriptions made with an annotation can also
 * be obtained from {@link EventBus#subscriptionsOf(Object)}.
 *
 * <p>Because it implements {@link AutoCloseable}, it can be released with try-with-resources.
 */
public interface Subscription extends AutoCloseable {
    /** Cancels this subscription. Does nothing if it is already cancelled. */
    void unsubscribe();

    /** Returns true while this subscription is still registered. */
    boolean isSubscribed();

    /**
     * Whether calls are temporarily suspended.
     * Unlike unsubscribing, the registration is kept and only the call is skipped.
     */
    boolean isActive();

    void setActive(boolean active);

    /** The event type this subscription listens for. */
    Class<?> eventType();

    /** The owner of this subscription. If no owner was given for a lambda, the handle itself is returned. */
    Object owner();

    /** The order in which it runs. Higher values run first. */
    int priority();

    /** Returns true if this subscription runs once and then cancels itself. */
    boolean isOnce();

    /** Same as {@link #unsubscribe()}. Never throws. */
    @Override
    default void close() {
        unsubscribe();
    }
}
