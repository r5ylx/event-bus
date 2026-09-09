package com.r5ylx.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.r5ylx.events.TestEvents.BaseEvent;

/**
 * How exceptions are handled.
 * The old implementation caught Throwable and called printStackTrace, so failures vanished into
 * standard error.
 */
class ExceptionHandlingTest {
    @Test
    @DisplayName("by default the exception is wrapped and rethrown")
    void rethrowsByDefault() {
        EventBus bus = new EventBus();
        RuntimeException boom = new IllegalStateException("boom");

        bus.subscribe(BaseEvent.class, event -> {
            throw boom;
        });

        EventDispatchException error =
            assertThrows(EventDispatchException.class, () -> bus.post(new BaseEvent()));

        assertSame(boom, error.getCause());
        assertInstanceOf(BaseEvent.class, error.getEvent());
    }

    @Test
    @DisplayName("replacing the handler lets dispatch continue")
    void continuesWithCustomHandler() {
        List<Throwable> caught = new ArrayList<>();
        EventBus bus = new EventBus(EventExceptionHandler.logging(caught::add));
        AtomicInteger reached = new AtomicInteger();

        bus.subscribe(null, BaseEvent.class, event -> {
            throw new IllegalStateException("boom");
        }, EventPriority.HIGH, false);

        bus.subscribe(null, BaseEvent.class, event -> reached.incrementAndGet(), EventPriority.LOW, false);

        bus.post(new BaseEvent());

        assertEquals(1, caught.size());
        assertEquals(1, reached.get(), "later listeners still run when an earlier one fails");
    }

    @Test
    @DisplayName("an Error propagates as it is, without going through the handler")
    void errorsAreNeverSwallowed() {
        List<Throwable> caught = new ArrayList<>();
        EventBus bus = new EventBus(EventExceptionHandler.logging(caught::add));

        bus.subscribe(BaseEvent.class, event -> {
            throw new StackOverflowError("simulated");
        });

        assertThrows(StackOverflowError.class, () -> bus.post(new BaseEvent()));
        assertEquals(0, caught.size(), "an Error is never handed to the handler");
    }

    @Test
    @DisplayName("the handler can be replaced later")
    void handlerCanBeReplaced() {
        EventBus bus = new EventBus();
        AtomicInteger caught = new AtomicInteger();

        bus.subscribe(BaseEvent.class, event -> {
            throw new IllegalStateException("boom");
        });

        assertThrows(EventDispatchException.class, () -> bus.post(new BaseEvent()));

        bus.setExceptionHandler(EventExceptionHandler.logging(error -> caught.incrementAndGet()));
        bus.post(new BaseEvent());

        assertEquals(1, caught.get());
    }

    @Test
    @DisplayName("the handler receives the subscription that threw")
    void handlerReceivesSubscription() {
        EventBus bus = new EventBus();
        List<Subscription> seen = new ArrayList<>();

        bus.setExceptionHandler((event, subscription, error) -> seen.add(subscription));

        Subscription subscription = bus.subscribe(BaseEvent.class, event -> {
            throw new IllegalStateException("boom");
        });

        bus.post(new BaseEvent());

        assertEquals(1, seen.size());
        assertSame(subscription, seen.get(0));
    }
}
