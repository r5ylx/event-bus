package com.r5ylx.events;

/** The event types reused across the tests. */
final class TestEvents {
    private TestEvents() {
    }

    /** The root of the hierarchy. */
    static class BaseEvent {
    }

    /** Extends BaseEvent and implements an interface as well. */
    static class ChildEvent extends BaseEvent implements Marker {
    }

    /** A marker for checking the non-class path through the type hierarchy. */
    interface Marker {
    }

    /** An event that can be cancelled. */
    static class CancellableEvent implements ICancellable {
        private boolean cancelled;

        @Override
        public void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** An event that can be cancelled and also takes part in a hierarchy. */
    static class CancellableChildEvent extends CancellableEvent {
    }

    /** An event that carries a value back out. */
    static class CounterEvent {
        int count;
    }
}
