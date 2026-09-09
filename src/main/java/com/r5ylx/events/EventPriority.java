package com.r5ylx.events;

/**
 * The order in which listeners run. Higher values run first.
 *
 * <p>To pass a number directly, use {@link EventBus#subscribe(Object, Class, java.util.function.Consumer, int, boolean)}.
 * This enum only puts names on the common steps.
 */
public enum EventPriority {
    /** Runs first. Use it for deciding whether to cancel an event. */
    HIGHEST(100),
    HIGH(75),
    /** The default. */
    NORMAL(50),
    LOW(25),
    /** Runs last. Use it for work that only observes the outcome, such as metrics or logging. */
    LOWEST(0);

    private final int value;

    EventPriority(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
