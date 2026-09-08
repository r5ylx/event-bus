package com.r5ylx.events;

import java.util.function.Consumer;

/** ラムダで登録されたリスナーです。 */
final class LambdaListener<T> extends Listener {
    private final Consumer<? super T> action;

    LambdaListener(EventBus bus, Object owner, Class<T> eventType, Consumer<? super T> action,
                    int priority, boolean once, long sequence) {
        super(bus, owner, eventType, priority, once, sequence);
        this.action = action;
    }

    @SuppressWarnings("unchecked")
    @Override
    void invoke(Object event) {
        action.accept((T) event);
    }
}
