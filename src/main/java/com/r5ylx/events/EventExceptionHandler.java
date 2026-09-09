package com.r5ylx.events;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decides what happens to an exception thrown by a listener.
 *
 * <p>The default is {@link #rethrowing()}. Not swallowing exceptions is the default because
 * swallowing them produces the hardest kind of bug to track down: the event arrives, and
 * nothing happens.
 *
 * <p>Use {@link #logging(Consumer)} when one failing listener should not stop the whole dispatch.
 * Note that an {@link Error} is always propagated as it is, whichever handler is set.
 */
@FunctionalInterface
public interface EventExceptionHandler {
    /**
     * @param event        the event that was being dispatched
     * @param subscription the listener that threw
     * @param error        the exception that was thrown. An {@link Error} never reaches here
     */
    void handle(Object event, Subscription subscription, Throwable error);

    /** Wraps the exception in an {@link EventDispatchException} and throws it. This is the default. */
    static EventExceptionHandler rethrowing() {
        return (event, subscription, error) -> {
            throw new EventDispatchException(
                "Listener " + subscription.owner().getClass().getName()
                    + " threw while handling " + event.getClass().getName(),
                event, subscription, error);
        };
    }

    /**
     * Hands the exception to the given sink and lets dispatch continue.
     *
     * @param sink for example {@code e -> LOGGER.error("...", e)}
     */
    static EventExceptionHandler logging(Consumer<? super Throwable> sink) {
        Objects.requireNonNull(sink, "sink");
        return (event, subscription, error) -> sink.accept(error);
    }
}
