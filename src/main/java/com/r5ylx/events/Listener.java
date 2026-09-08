package com.r5ylx.events;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 登録された 1 件のリスナーです。購読の取っ手そのものを兼ねます。
 *
 * <p>公開 API ではありません。利用側は {@link Subscription} 越しに扱います。
 */
abstract class Listener implements Subscription {
    private final EventBus bus;
    private final Object owner;
    private final Class<?> eventType;
    private final int priority;
    private final boolean once;

    /** 同じ優先度の中では登録順を保つための通し番号です。 */
    private final long sequence;

    private final AtomicBoolean consumed = new AtomicBoolean();

    private volatile boolean active = true;
    private volatile boolean subscribed = true;

    Listener(EventBus bus, Object owner, Class<?> eventType, int priority, boolean once, long sequence) {
        this.bus = bus;
        this.owner = owner;
        this.eventType = eventType;
        this.priority = priority;
        this.once = once;
        this.sequence = sequence;
    }

    /** リスナー本体を呼びます。event は {@link #eventType()} に代入可能であることが保証されています。 */
    abstract void invoke(Object event) throws Throwable;

    /**
     * 1 回限りのリスナーの実行権を取ります。
     * 複数のスレッドが同時に配送しても、実行できるのは 1 つだけです。
     */
    boolean tryConsume() {
        return consumed.compareAndSet(false, true);
    }

    long sequence() {
        return sequence;
    }

    /** 解除済みとして印を付けます。EventBus から呼びます。 */
    void markUnsubscribed() {
        subscribed = false;
    }

    @Override
    public void unsubscribe() {
        bus.remove(this);
    }

    @Override
    public boolean isSubscribed() {
        return subscribed;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public Class<?> eventType() {
        return eventType;
    }

    /** 持ち主を指定せずに登録された場合は、この取っ手自身が持ち主になります。 */
    @Override
    public Object owner() {
        return owner == null ? this : owner;
    }

    @Override
    public int priority() {
        return priority;
    }

    @Override
    public boolean isOnce() {
        return once;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
            + "[event=" + eventType.getSimpleName()
            + ", priority=" + priority
            + (once ? ", once" : "")
            + "]";
    }
}
