package com.r5ylx.events;

/**
 * Wraps an exception thrown by a listener so that it can be rethrown.
 *
 * @see EventExceptionHandler#rethrowing()
 */
public class EventDispatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient Object event;
    private final transient Subscription subscription;

    public EventDispatchException(String message, Object event, Subscription subscription, Throwable cause) {
        super(message, cause);
        this.event = event;
        this.subscription = subscription;
    }

    /** The event that was being dispatched. Becomes null once the exception is serialized. */
    public Object getEvent() {
        return event;
    }

    /** The subscription whose listener threw. Becomes null once the exception is serialized. */
    public Subscription getSubscription() {
        return subscription;
    }
}
