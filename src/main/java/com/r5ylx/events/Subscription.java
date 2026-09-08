package com.r5ylx.events;

/**
 * 1 件の購読を表す取っ手です。
 *
 * <p>ラムダで購読すると返ってきます。アノテーションで購読した場合も
 * {@link EventBus#subscriptionsOf(Object)} から取り出せます。
 *
 * <p>{@link AutoCloseable} を実装しているため、try-with-resources でも解除できます。
 */
public interface Subscription extends AutoCloseable {
    /** この購読を解除します。すでに解除済みなら何もしません。 */
    void unsubscribe();

    /** まだ購読中なら true を返します。 */
    boolean isSubscribed();

    /**
     * 一時的に呼び出しを止めているかどうかです。
     * 解除と違い、登録は残したまま呼び出しだけを飛ばします。
     */
    boolean isActive();

    void setActive(boolean active);

    /** 購読しているイベントの型です。 */
    Class<?> eventType();

    /** 購読の持ち主です。ラムダ購読で持ち主を指定しなかった場合は、この取っ手自身が返ります。 */
    Object owner();

    /** 実行順序です。大きいものから先に呼ばれます。 */
    int priority();

    /** 1 回だけ実行して自動解除する購読なら true を返します。 */
    boolean isOnce();

    /** {@link #unsubscribe()} と同じです。例外は投げません。 */
    @Override
    default void close() {
        unsubscribe();
    }
}
