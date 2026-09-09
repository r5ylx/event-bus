package com.r5ylx.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.r5ylx.events.TestEvents.BaseEvent;

/** Checks that it does not break when several threads use it at the same time. */
class ConcurrencyTest {
    private static final int THREADS = 8;
    private static final int ROUNDS = 500;

    @Test
    @Timeout(30)
    @DisplayName("once runs exactly once even under concurrent posts")
    void onceFiresExactlyOnceUnderConcurrency() throws InterruptedException {
        EventBus bus = new EventBus();
        AtomicInteger calls = new AtomicInteger();

        bus.subscribe(null, BaseEvent.class, event -> calls.incrementAndGet(), EventPriority.NORMAL, true);

        runConcurrently(() -> bus.post(new BaseEvent()));

        assertEquals(1, calls.get());
    }

    @Test
    @Timeout(30)
    @DisplayName("posting and subscribing at the same time does not break it")
    void concurrentSubscribeAndPost() throws InterruptedException {
        EventBus bus = new EventBus();
        AtomicInteger delivered = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try {
            for (int i = 0; i < THREADS; i++) {
                boolean publisher = i % 2 == 0;

                pool.execute(() -> {
                    try {
                        start.await();

                        for (int round = 0; round < ROUNDS; round++) {
                            if (publisher) {
                                bus.post(new BaseEvent());
                            } else {
                                Subscription subscription =
                                    bus.subscribe(BaseEvent.class, event -> delivered.incrementAndGet());
                                subscription.unsubscribe();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(25, TimeUnit.SECONDS), "not all threads finished");
        } finally {
            pool.shutdownNow();
        }

        // Registration and cancellation were repeated, so no listener remains at the end.
        assertEquals(0, bus.listenerCount(BaseEvent.class));
    }

    @Test
    @Timeout(30)
    @DisplayName("every listener receives every event under concurrent posts")
    void allListenersReceiveEveryEvent() throws InterruptedException {
        EventBus bus = new EventBus();
        AtomicInteger calls = new AtomicInteger();

        for (int i = 0; i < 4; i++) {
            bus.subscribe(BaseEvent.class, event -> calls.incrementAndGet());
        }

        runConcurrently(() -> bus.post(new BaseEvent()));

        assertEquals(THREADS * ROUNDS * 4, calls.get());
    }

    private static void runConcurrently(Runnable action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        try {
            for (int i = 0; i < THREADS; i++) {
                pool.execute(() -> {
                    try {
                        start.await();

                        for (int round = 0; round < ROUNDS; round++) {
                            action.run();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(25, TimeUnit.SECONDS), "not all threads finished");
        } finally {
            pool.shutdownNow();
        }
    }
}
