package com.r5ylx.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.r5ylx.events.TestEvents.BaseEvent;

/**
 * The {@code @Subscribe} scan.
 * The old implementation picked up the declared methods of every class in the hierarchy, so an
 * annotated override was called twice.
 */
class SubscriberScanTest {
    static class Parent {
        int calls;

        @Subscribe
        void onEvent(BaseEvent event) {
            calls++;
        }
    }

    /** Overrides the parent method and annotates it again. This is what registered twice before. */
    static class ChildWithAnnotation extends Parent {
        @Subscribe
        @Override
        void onEvent(BaseEvent event) {
            super.onEvent(event);
        }
    }

    /** Overrides without annotating. */
    static class ChildWithoutAnnotation extends Parent {
        @Override
        void onEvent(BaseEvent event) {
            super.onEvent(event);
        }
    }

    static class PrivateSubscriber {
        int calls;

        @Subscribe
        private void onEvent(BaseEvent event) {
            calls++;
        }
    }

    @Test
    @DisplayName("an annotated override is still called only once")
    void annotatedOverrideRegistersOnce() {
        EventBus bus = new EventBus();
        ChildWithAnnotation subscriber = new ChildWithAnnotation();
        bus.subscribe(subscriber);

        assertEquals(1, bus.listenerCount(BaseEvent.class));

        bus.post(new BaseEvent());
        assertEquals(1, subscriber.calls);
    }

    @Test
    @DisplayName("an unannotated override subscribes through the parent and runs the child body")
    void unannotatedOverrideStillSubscribes() {
        EventBus bus = new EventBus();
        ChildWithoutAnnotation subscriber = new ChildWithoutAnnotation();
        bus.subscribe(subscriber);

        assertEquals(1, bus.listenerCount(BaseEvent.class));

        bus.post(new BaseEvent());
        assertEquals(1, subscriber.calls);
    }

    @Test
    @DisplayName("a private method can subscribe")
    void supportsPrivateMethods() {
        EventBus bus = new EventBus();
        PrivateSubscriber subscriber = new PrivateSubscriber();
        bus.subscribe(subscriber);

        bus.post(new BaseEvent());
        assertEquals(1, subscriber.calls);
    }

    @Test
    @DisplayName("subscribing the same object twice adds nothing")
    void subscribingTwiceIsIdempotent() {
        EventBus bus = new EventBus();
        Parent subscriber = new Parent();

        bus.subscribe(subscriber);
        bus.subscribe(subscriber);

        assertEquals(1, bus.listenerCount(BaseEvent.class));
    }

    // ---- a malformed @Subscribe fails on the spot instead of being silently ignored ----

    static class TooManyParameters {
        @Subscribe
        void onEvent(BaseEvent event, int extra) {
        }
    }

    static class NotVoid {
        @Subscribe
        boolean onEvent(BaseEvent event) {
            return true;
        }
    }

    static class StaticSubscriber {
        @Subscribe
        static void onEvent(BaseEvent event) {
        }
    }

    static class PrimitiveParameter {
        @Subscribe
        void onEvent(int event) {
        }
    }

    @Test
    @DisplayName("a @Subscribe without exactly one parameter throws")
    void rejectsWrongParameterCount() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
            () -> new EventBus().subscribe(new TooManyParameters()));
        assertTrue(error.getMessage().contains("parameter"));
    }

    @Test
    @DisplayName("a @Subscribe with a return value throws")
    void rejectsNonVoidReturn() {
        assertThrows(IllegalArgumentException.class, () -> new EventBus().subscribe(new NotVoid()));
    }

    @Test
    @DisplayName("a static @Subscribe throws")
    void rejectsStaticMethods() {
        assertThrows(IllegalArgumentException.class, () -> new EventBus().subscribe(new StaticSubscriber()));
    }

    @Test
    @DisplayName("a @Subscribe with a primitive parameter throws")
    void rejectsPrimitiveParameter() {
        assertThrows(IllegalArgumentException.class, () -> new EventBus().subscribe(new PrimitiveParameter()));
    }
}
