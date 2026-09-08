package com.r5ylx.events;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * リスナーが投げた例外の扱いを決めます。
 *
 * <p>既定は {@link #rethrowing()} です。例外を握り潰さないことを既定にしているのは、
 * 握り潰すと「イベントは届いているのに何も起きない」という最も追いにくい不具合になるためです。
 *
 * <p>1 つのリスナーの失敗で配送全体を止めたくない場合は {@link #logging(Consumer)} を使ってください。
 * なお {@link Error} は、どのハンドラーを設定していても常にそのまま送出されます。
 */
@FunctionalInterface
public interface EventExceptionHandler {
    /**
     * @param event        配送中だったイベント
     * @param subscription 例外を投げたリスナー
     * @param error        投げられた例外。{@link Error} は渡ってきません
     */
    void handle(Object event, Subscription subscription, Throwable error);

    /** 例外を {@link EventDispatchException} に包んで送出します。既定の挙動です。 */
    static EventExceptionHandler rethrowing() {
        return (event, subscription, error) -> {
            throw new EventDispatchException(
                "リスナー " + subscription.owner().getClass().getName()
                    + " が " + event.getClass().getName() + " の処理中に例外を投げました",
                event, subscription, error);
        };
    }

    /**
     * 例外を受け取り手へ渡し、配送は続行します。
     *
     * @param sink 例えば {@code e -> LOGGER.error("...", e)}
     */
    static EventExceptionHandler logging(Consumer<? super Throwable> sink) {
        Objects.requireNonNull(sink, "sink");
        return (event, subscription, error) -> sink.accept(error);
    }
}
