package com.r5ylx.events;

/**
 * An event that can be cancelled.
 *
 * <p>{@link EventBus#post(Object)} checks {@link #isCancelled()} after every single listener.
 * Once the event is cancelled, dispatch ends completely, and listeners registered on
 * supertypes and interfaces are not called either.
 */
public interface ICancellable {
    void setCancelled(boolean cancelled);

    boolean isCancelled();

    /** Same as {@code setCancelled(true)}. */
    default void cancel() {
        setCancelled(true);
    }
}
