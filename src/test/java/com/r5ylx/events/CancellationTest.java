package com.r5ylx.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.r5ylx.events.TestEvents.CancellableChildEvent;
import com.r5ylx.events.TestEvents.CancellableEvent;

/**
 * How cancellation behaves.
 * The old implementation only broke out of the inner loop, so listeners on supertypes still ran
 * after the event had been cancelled.
 */
class CancellationTest {
    @Test
    @DisplayName("cancelling stops later listeners of the same type")
    void stopsLaterListenersOfSameType() {
        EventBus bus = new EventBus();
        List<String> calls = new ArrayList<>();

        bus.subscribe(null, CancellableEvent.class, e -> {
            calls.add("first");
            e.cancel();
        }, EventPriority.HIGH, false);

        bus.subscribe(null, CancellableEvent.class, e -> calls.add("second"), EventPriority.LOW, false);

        bus.post(new CancellableEvent());

        assertEquals(List.of("first"), calls);
    }

    @Test
    @DisplayName("cancelling stops listeners registered on supertypes too")
    void stopsListenersRegisteredOnSupertypes() {
        EventBus bus = new EventBus();
        List<String> calls = new ArrayList<>();

        // Cancel from the concrete type.
        bus.subscribe(null, CancellableChildEvent.class, e -> {
            calls.add("child");
            e.cancel();
        }, EventPriority.HIGH, false);

        // A listener on the supertype. Its priority is lower, so it comes after the cancellation.
        bus.subscribe(null, CancellableEvent.class, e -> calls.add("parent"), EventPriority.LOW, false);

        CancellableChildEvent event = bus.post(new CancellableChildEvent());

        assertTrue(event.isCancelled());
        assertEquals(List.of("child"), calls, "a listener on a supertype must not run after cancellation");
    }

    @Test
    @DisplayName("a cancelled event is still returned as it is")
    void returnsTheSameInstance() {
        EventBus bus = new EventBus();
        bus.subscribe(CancellableEvent.class, ICancellable::cancel);

        CancellableEvent event = new CancellableEvent();

        assertTrue(bus.post(event) == event);
        assertTrue(event.isCancelled());
    }
}
