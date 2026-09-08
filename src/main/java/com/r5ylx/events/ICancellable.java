package com.r5ylx.events;

/**
 * 打ち消せるイベントです。
 *
 * <p>{@link EventBus#post(Object)} は、リスナーを 1 つ呼ぶたびに {@link #isCancelled()} を確認します。
 * 打ち消された時点で配送は完全に終了し、親クラスやインターフェースに登録されたリスナーも呼ばれません。
 */
public interface ICancellable {
    void setCancelled(boolean cancelled);

    boolean isCancelled();

    /** {@code setCancelled(true)} と同じです。 */
    default void cancel() {
        setCancelled(true);
    }
}
