package com.r5ylx.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.r5ylx.events.TestEvents.BaseEvent;
import com.r5ylx.events.TestEvents.ChildEvent;
import com.r5ylx.events.TestEvents.Marker;

/**
 * The order in which listeners run.
 * The old implementation walked one bucket per type, so priority only applied inside a bucket.
 */
class DispatchOrderTest {
    @Test
    @DisplayName("priority applies across types")
    void priorityBeatsTypeDistance() {
        EventBus bus = new EventBus();
        List<String> calls = new ArrayList<>();

        // Registered on the concrete type at a low priority.
        bus.subscribe(null, ChildEvent.class, e -> calls.add("child-LOW"), EventPriority.LOW, false);
        // Registered on the supertype at a high priority.
        bus.subscribe(null, BaseEvent.class, e -> calls.add("base-HIGHEST"), EventPriority.HIGHEST, false);
        // Registered on the interface at a priority in between.
        bus.subscribe(null, Marker.class, e -> calls.add("marker-NORMAL"), EventPriority.NORMAL, false);

        bus.post(new ChildEvent());

        assertEquals(List.of("base-HIGHEST", "marker-NORMAL", "child-LOW"), calls);
    }

    @Test
    @DisplayName("equal priorities keep registration order")
    void equalPrioritiesKeepRegistrationOrder() {
        EventBus bus = new EventBus();
        List<String> calls = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            String name = "listener-" + i;
            bus.subscribe(BaseEvent.class, e -> calls.add(name));
        }

        bus.post(new BaseEvent());

        assertEquals(
            List.of("listener-0", "listener-1", "listener-2", "listener-3",
                "listener-4", "listener-5", "listener-6", "listener-7"),
            calls);
    }

    @Test
    @DisplayName("priority can be given as a number")
    void acceptsRawIntPriority() {
        EventBus bus = new EventBus();
        List<String> calls = new ArrayList<>();

        bus.subscribe(null, BaseEvent.class, e -> calls.add("low"), 10, false);
        bus.subscribe(null, BaseEvent.class, e -> calls.add("high"), 1000, false);

        bus.post(new BaseEvent());

        assertEquals(List.of("high", "low"), calls);
    }

    @Test
    @DisplayName("a listener on a supertype receives subtype events")
    void supertypeListenersReceiveSubtypes() {
        EventBus bus = new EventBus();
        List<String> calls = new ArrayList<>();

        bus.subscribe(BaseEvent.class, e -> calls.add("base"));
        bus.subscribe(Marker.class, e -> calls.add("marker"));
        bus.subscribe(ChildEvent.class, e -> calls.add("child"));

        bus.post(new ChildEvent());
        assertEquals(3, calls.size());

        calls.clear();

        // A supertype event does not reach a listener on the subtype.
        bus.post(new BaseEvent());
        assertEquals(List.of("base"), calls);
    }
}
