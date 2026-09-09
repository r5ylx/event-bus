package com.r5ylx.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.r5ylx.events.TestEvents.BaseEvent;
import com.r5ylx.events.TestEvents.ChildEvent;
import com.r5ylx.events.TestEvents.CounterEvent;
import com.r5ylx.events.TestEvents.Marker;

class EventBusTest {
    private EventBus bus;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
    }

    static class Counter {
        int calls;

        @Subscribe
        void onCounter(CounterEvent event) {
            calls++;
            event.count++;
        }
    }

    @Test
    @DisplayName("post returns the event unchanged")
    void postReturnsTheEvent() {
        CounterEvent event = new CounterEvent();
        assertSame(event, bus.post(event));
    }

    @Test
    @DisplayName("posting with no listeners is safe")
    void postWithoutListenersIsSafe() {
        assertEquals(0, bus.post(new CounterEvent()).count);
    }

    @Test
    @DisplayName("null is rejected")
    void rejectsNull() {
        assertThrows(NullPointerException.class, () -> bus.post(null));
        assertThrows(NullPointerException.class, () -> bus.subscribe(null));
        assertThrows(NullPointerException.class, () -> bus.subscribe(CounterEvent.class, null));
    }

    @Test
    @DisplayName("subscribing and unsubscribing")
    void subscribeAndUnsubscribe() {
        Counter counter = new Counter();

        bus.subscribe(counter);
        bus.post(new CounterEvent());
        assertEquals(1, counter.calls);

        bus.unsubscribe(counter);
        bus.post(new CounterEvent());
        assertEquals(1, counter.calls, "it must not be called after unsubscribing");

        assertFalse(bus.hasListeners(CounterEvent.class));
    }

    @Test
    @DisplayName("the owner is matched by identity")
    void ownerIsMatchedByIdentity() {
        // Even when equals always returns true, another instance is another owner.
        class Sloppy {
            @Subscribe
            void onCounter(CounterEvent event) {
                event.count++;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof Sloppy;
            }

            @Override
            public int hashCode() {
                return 1;
            }
        }

        Sloppy first = new Sloppy();
        Sloppy second = new Sloppy();

        bus.subscribe(first);
        bus.subscribe(second);

        assertEquals(2, bus.listenerCount(CounterEvent.class));

        bus.unsubscribe(first);
        assertEquals(1, bus.listenerCount(CounterEvent.class));
    }

    @Test
    @DisplayName("deactivate stops the calls while keeping the registration")
    void deactivateKeepsRegistration() {
        Counter counter = new Counter();
        bus.subscribe(counter);

        bus.deactivate(counter);
        bus.post(new CounterEvent());
        assertEquals(0, counter.calls);
        assertTrue(bus.hasListeners(CounterEvent.class), "the registration itself remains");

        bus.activate(counter);
        bus.post(new CounterEvent());
        assertEquals(1, counter.calls);
    }

    @Test
    @DisplayName("clear removes everything")
    void clearRemovesEverything() {
        bus.subscribe(new Counter());
        bus.subscribe(CounterEvent.class, event -> event.count++);

        assertEquals(2, bus.listenerCount(CounterEvent.class));

        bus.clear();

        assertEquals(0, bus.listenerCount(CounterEvent.class));
        assertEquals(0, bus.post(new CounterEvent()).count);
    }

    // ---- lambda subscriptions and handles ----

    @Test
    @DisplayName("a lambda subscription can be cancelled on its own through the handle")
    void lambdaSubscriptionCanBeCancelledIndividually() {
        List<String> calls = new ArrayList<>();

        Subscription first = bus.subscribe(BaseEvent.class, event -> calls.add("first"));
        bus.subscribe(BaseEvent.class, event -> calls.add("second"));

        bus.post(new BaseEvent());
        assertEquals(List.of("first", "second"), calls);

        calls.clear();
        first.unsubscribe();

        bus.post(new BaseEvent());
        assertEquals(List.of("second"), calls, "only one of them was cancelled");

        assertFalse(first.isSubscribed());
    }

    @Test
    @DisplayName("a handle can also be released with try-with-resources")
    void subscriptionIsAutoCloseable() {
        AtomicInteger calls = new AtomicInteger();

        try (Subscription subscription = bus.subscribe(BaseEvent.class, event -> calls.incrementAndGet())) {
            assertTrue(subscription.isSubscribed());
            bus.post(new BaseEvent());
        }

        bus.post(new BaseEvent());
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("unsubscribing twice is safe")
    void unsubscribeIsIdempotent() {
        Subscription subscription = bus.subscribe(BaseEvent.class, event -> { });

        subscription.unsubscribe();
        subscription.unsubscribe();

        assertFalse(subscription.isSubscribed());
    }

    @Test
    @DisplayName("lambdas that share an owner can be cancelled together")
    void lambdaWithOwnerIsRemovedByOwner() {
        Object owner = new Object();

        bus.subscribe(owner, BaseEvent.class, event -> { }, EventPriority.NORMAL, false);
        bus.subscribe(owner, BaseEvent.class, event -> { }, EventPriority.HIGH, false);

        assertEquals(2, bus.subscriptionsOf(owner).size());

        bus.unsubscribe(owner);

        assertEquals(0, bus.listenerCount(BaseEvent.class));
        assertEquals(0, bus.subscriptionsOf(owner).size());
    }

    // ---- once ----

    @Test
    @DisplayName("once runs a single time and then cancels itself")
    void onceFiresExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();

        Subscription subscription =
            bus.subscribe(null, BaseEvent.class, event -> calls.incrementAndGet(), EventPriority.NORMAL, true);

        bus.post(new BaseEvent());
        bus.post(new BaseEvent());
        bus.post(new BaseEvent());

        assertEquals(1, calls.get());
        assertFalse(subscription.isSubscribed());
        assertEquals(0, bus.listenerCount(BaseEvent.class));
    }

    static class OnceSubscriber {
        int calls;

        @Subscribe(once = true)
        void onBase(BaseEvent event) {
            calls++;
        }
    }

    @Test
    @DisplayName("once works with the annotation too")
    void onceWorksWithAnnotation() {
        OnceSubscriber subscriber = new OnceSubscriber();
        bus.subscribe(subscriber);

        bus.post(new BaseEvent());
        bus.post(new BaseEvent());

        assertEquals(1, subscriber.calls);
    }

    // ---- queries ----

    @Test
    @DisplayName("hasListeners counts registrations on supertypes")
    void hasListenersIsHierarchyAware() {
        bus.subscribe(BaseEvent.class, event -> { });

        assertTrue(bus.hasListeners(BaseEvent.class));
        assertTrue(bus.hasListeners(ChildEvent.class), "a listener on a supertype receives subtype events");
        assertEquals(1, bus.listenerCount(ChildEvent.class));
    }

    @Test
    @DisplayName("hasListeners counts registrations on interfaces")
    void hasListenersCountsInterfaces() {
        bus.subscribe(Marker.class, event -> { });

        assertTrue(bus.hasListeners(ChildEvent.class));
        assertFalse(bus.hasListeners(BaseEvent.class), "BaseEvent does not implement Marker");
    }

    @Test
    @DisplayName("registering rebuilds the dispatch chain")
    void dispatchCacheIsInvalidatedOnChange() {
        assertEquals(0, bus.listenerCount(ChildEvent.class));

        bus.subscribe(BaseEvent.class, event -> { });
        assertEquals(1, bus.listenerCount(ChildEvent.class));

        bus.subscribe(ChildEvent.class, event -> { });
        assertEquals(2, bus.listenerCount(ChildEvent.class));
    }
}
