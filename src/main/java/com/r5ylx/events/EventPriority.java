package com.r5ylx.events;

/**
 * リスナーの実行順序です。値が大きいものから先に呼ばれます。
 *
 * <p>数値を直接指定したい場合は {@link EventBus#subscribe(Object, Class, java.util.function.Consumer, int, boolean)}
 * を使ってください。この列挙型は代表的な段階に名前を付けたものです。
 */
public enum EventPriority {
    /** 最初に実行されます。イベントを打ち消す判定などに使います。 */
    HIGHEST(100),
    HIGH(75),
    /** 既定値です。 */
    NORMAL(50),
    LOW(25),
    /** 最後に実行されます。集計やログなど、結果を観測するだけの処理に使います。 */
    LOWEST(0);

    private final int value;

    EventPriority(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
