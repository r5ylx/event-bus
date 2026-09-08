package com.r5ylx.events;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;


/**
 * イベントの発行と購読をまとめる場所です。
 *
 * <h2>配送の規則</h2>
 * <ul>
 *   <li>イベントは、その型と、すべての親クラス・インターフェースに登録されたリスナーへ届きます。</li>
 *   <li>実行順は<b>型に関係なく</b>優先度だけで決まります。親型に登録された {@code HIGHEST} は、
 *       具象型に登録された {@code LOW} より先に呼ばれます。</li>
 *   <li>同じ優先度どうしは登録順に呼ばれます。</li>
 *   <li>イベントが {@link ICancellable} を実装していて打ち消された場合、そこで配送は<b>完全に</b>終わります。
 *       親型のリスナーにも届きません。</li>
 * </ul>
 *
 * <h2>スレッド安全性</h2>
 * <p>すべての操作はスレッドセーフです。{@link #post(Object)} は、配送表が温まっていればロックを取りません。
 * ただし配送中に登録・解除を行った場合、その変更がその回の配送に反映されるかは決まっていません。
 * 次回以降の {@code post} からは必ず反映されます。
 *
 * <h2>例外の扱い</h2>
 * <p>リスナーが投げた例外は {@link EventExceptionHandler} が受け取ります。既定は再送出です。
 * 握り潰したい場合は {@link #setExceptionHandler(EventExceptionHandler)} で差し替えてください。
 *
 * <h2>使い方</h2>
 * <pre>{@code
 * EventBus bus = new EventBus();
 *
 * // アノテーションで購読する
 * class Listener {
 *     @Subscribe(priority = EventPriority.HIGH)
 *     void onTick(TickEvent event) { ... }
 * }
 * bus.subscribe(new Listener());
 *
 * // ラムダで購読し、取っ手で解除する
 * Subscription sub = bus.subscribe(TickEvent.class, event -> ...);
 * sub.unsubscribe();
 *
 * bus.post(new TickEvent());
 * }</pre>
 */
public final class EventBus {
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /** 優先度の降順、同じ優先度なら登録順です。 */
    private static final Comparator<Listener> ORDER =
        Comparator.comparingInt(Listener::priority).reversed()
            .thenComparingLong(Listener::sequence);

    /** クラスごとの {@code @Subscribe} メソッド一覧です。走査結果は使い回します。 */
    private static final Map<Class<?>, List<Method>> SUBSCRIBER_CACHE = new ConcurrentHashMap<>();

    /** 登録・解除をまとめて直列化します。{@code post} は配送表が温まっていれば触りません。 */
    private final Object mutationLock = new Object();

    /** 購読対象の型ごとのリスナーです。mutationLock で保護します。 */
    private final Map<Class<?>, List<Listener>> byType = new LinkedHashMap<>();

    /** 持ち主ごとのリスナーです。同一性で引くため IdentityHashMap を使います。mutationLock で保護します。 */
    private final Map<Object, List<Listener>> byOwner = new IdentityHashMap<>();

    /** 具体的なイベント型から、優先度順に並べ終えた配送列への対応表です。 */
    private final Map<Class<?>, Listener[]> dispatchCache = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    private volatile EventExceptionHandler exceptionHandler = EventExceptionHandler.rethrowing();

    /** 既定の設定でバスを作ります。例外は再送出されます。 */
    public EventBus() {
    }

    /**
     * 例外ハンドラーを指定してバスを作ります。
     *
     * @param exceptionHandler リスナーが投げた例外の受け取り手
     */
    public EventBus(EventExceptionHandler exceptionHandler) {
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

    // ------------------------------------------------------------------ 発行

    /**
     * イベントを配送します。
     *
     * @param event 配送するイベント
     * @param <T>   イベントの型
     * @return 引数と同じインスタンス。打ち消しの結果を読み取るために返します
     * @throws NullPointerException event が null の場合
     */
    public <T> T post(T event) {
        Objects.requireNonNull(event, "event");

        Listener[] chain = dispatchCache.get(event.getClass());

        if (chain == null) {
            chain = buildChain(event.getClass());
        }

        if (chain.length == 0) {
            return event;
        }

        ICancellable cancellable = event instanceof ICancellable c ? c : null;

        for (Listener listener : chain) {
            if (!listener.isActive() || !listener.isSubscribed()) {
                continue;
            }

            if (listener.isOnce() && !listener.tryConsume()) {
                continue;
            }

            try {
                listener.invoke(event);
            } catch (Error e) {
                // VM レベルの異常はハンドラーへ渡さず、そのまま伝播させます。
                throw e;
            } catch (Throwable t) {
                exceptionHandler.handle(event, listener, t);
            } finally {
                if (listener.isOnce()) {
                    listener.unsubscribe();
                }
            }

            if (cancellable != null && cancellable.isCancelled()) {
                break;
            }
        }

        return event;
    }

    // ------------------------------------------------------------------ 購読（注釈）

    /**
     * {@link Subscribe} が付いたメソッドをすべて購読します。
     *
     * <p>親クラスやインターフェースで宣言されたメソッドも対象です。
     * 親で宣言したメソッドを子で上書きし、子にも {@code @Subscribe} を付けた場合でも、登録は 1 つだけです。
     *
     * <p>すでに購読済みのオブジェクトを渡した場合は何もしません。
     *
     * @param owner 購読するオブジェクト
     * @throws IllegalArgumentException {@code @Subscribe} の付いたメソッドの形が正しくない場合
     */
    public void subscribe(Object owner) {
        Objects.requireNonNull(owner, "owner");

        List<Method> methods = SUBSCRIBER_CACHE.computeIfAbsent(owner.getClass(), EventBus::collectSubscribers);

        synchronized (mutationLock) {
            if (byOwner.containsKey(owner)) {
                return;
            }

            List<Listener> created = new ArrayList<>(methods.size());

            for (Method method : methods) {
                Subscribe meta = method.getAnnotation(Subscribe.class);
                created.add(new MethodListener(this, owner, method, bind(method, owner),
                    meta.priority().value(), meta.once(), sequence.getAndIncrement()));
            }

            register(owner, created);
        }
    }

    // ------------------------------------------------------------------ 購読（ラムダ）

    /**
     * ラムダで購読します。持ち主は返り値の取っ手自身になります。
     *
     * @return 解除に使う取っ手
     */
    public <T> Subscription subscribe(Class<T> eventType, Consumer<? super T> action) {
        return subscribe(null, eventType, action, EventPriority.NORMAL.value(), false);
    }

    /** 優先度を指定してラムダで購読します。 */
    public <T> Subscription subscribe(Class<T> eventType, Consumer<? super T> action, EventPriority priority) {
        Objects.requireNonNull(priority, "priority");
        return subscribe(null, eventType, action, priority.value(), false);
    }

    /**
     * 持ち主と優先度を指定してラムダで購読します。
     *
     * <p>持ち主を渡しておくと {@link #unsubscribe(Object)} や {@link #deactivate(Object)} でまとめて扱えます。
     *
     * @param owner 持ち主。null の場合は取っ手自身が持ち主になります
     * @param once  true にすると 1 回実行した時点で自動的に解除されます
     */
    public <T> Subscription subscribe(Object owner, Class<T> eventType, Consumer<? super T> action,
                                        EventPriority priority, boolean once) {
        Objects.requireNonNull(priority, "priority");
        return subscribe(owner, eventType, action, priority.value(), once);
    }

    /**
     * 優先度を数値で指定してラムダで購読します。
     *
     * @param priority 大きいほど先に呼ばれます。目安は {@link EventPriority} を参照してください
     */
    public <T> Subscription subscribe(Object owner, Class<T> eventType, Consumer<? super T> action,
                                        int priority, boolean once) {
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(action, "action");

        LambdaListener<T> listener =
            new LambdaListener<>(this, owner, eventType, action, priority, once, sequence.getAndIncrement());

        synchronized (mutationLock) {
            register(owner == null ? listener : owner, List.of(listener));
        }

        return listener;
    }

    // ------------------------------------------------------------------ 解除

    /**
     * 指定した持ち主の購読をすべて解除します。
     *
     * <p>持ち主の判定は同一性（{@code ==}）で行います。{@code equals} は使いません。
     */
    public void unsubscribe(Object owner) {
        Objects.requireNonNull(owner, "owner");

        synchronized (mutationLock) {
            List<Listener> removed = byOwner.remove(owner);

            if (removed == null) {
                return;
            }

            for (Listener listener : removed) {
                listener.markUnsubscribed();
                List<Listener> bucket = byType.get(listener.eventType());

                if (bucket != null) {
                    bucket.remove(listener);

                    if (bucket.isEmpty()) {
                        byType.remove(listener.eventType());
                    }
                }
            }

            dispatchCache.clear();
        }
    }

    /** すべての購読を解除します。 */
    public void clear() {
        synchronized (mutationLock) {
            for (List<Listener> bucket : byType.values()) {
                for (Listener listener : bucket) {
                    listener.markUnsubscribed();
                }
            }

            byType.clear();
            byOwner.clear();
            dispatchCache.clear();
        }
    }

    // ------------------------------------------------------------------ 一時的な停止

    /** 指定した持ち主のリスナーを呼び出し対象に戻します。 */
    public void activate(Object owner) {
        setActive(owner, true);
    }

    /** 指定した持ち主のリスナーを、登録を残したまま呼び出し対象から外します。 */
    public void deactivate(Object owner) {
        setActive(owner, false);
    }

    /** 指定した持ち主のリスナーの有効・無効をまとめて切り替えます。 */
    public void setActive(Object owner, boolean active) {
        Objects.requireNonNull(owner, "owner");

        synchronized (mutationLock) {
            List<Listener> listeners = byOwner.get(owner);

            if (listeners == null) {
                return;
            }

            for (Listener listener : listeners) {
                listener.setActive(active);
            }
        }
    }

    // ------------------------------------------------------------------ 問い合わせ

    /**
     * そのイベント型を受け取るリスナーが 1 つでもあるかを返します。
     *
     * <p>親クラスやインターフェースへの登録も数えます。つまり {@code post} が実際に届ける相手と一致します。
     * 重いイベントの生成を避ける判定に使えます。
     */
    public boolean hasListeners(Class<?> eventType) {
        return listenerCount(eventType) > 0;
    }

    /**
     * そのイベント型を受け取るリスナーの数を返します。
     * {@link #hasListeners(Class)} と同様、親型への登録も数えます。
     */
    public int listenerCount(Class<?> eventType) {
        Objects.requireNonNull(eventType, "eventType");

        Listener[] chain = dispatchCache.get(eventType);

        return (chain == null ? buildChain(eventType) : chain).length;
    }

    /** 指定した持ち主の購読を、登録順で返します。 */
    public List<Subscription> subscriptionsOf(Object owner) {
        Objects.requireNonNull(owner, "owner");

        synchronized (mutationLock) {
            List<Listener> listeners = byOwner.get(owner);
            return listeners == null ? List.of() : List.copyOf(listeners);
        }
    }

    /** 現在の例外ハンドラーです。 */
    public EventExceptionHandler getExceptionHandler() {
        return exceptionHandler;
    }

    /** 例外ハンドラーを差し替えます。配送中に呼んでも安全です。 */
    public void setExceptionHandler(EventExceptionHandler exceptionHandler) {
        this.exceptionHandler = Objects.requireNonNull(exceptionHandler, "exceptionHandler");
    }

    // ------------------------------------------------------------------ 内部

    /** mutationLock を保持した状態で呼びます。 */
    private void register(Object owner, List<Listener> listeners) {
        assert Thread.holdsLock(mutationLock);

        if (listeners.isEmpty()) {
            byOwner.putIfAbsent(owner, new ArrayList<>());
            return;
        }

        for (Listener listener : listeners) {
            byType.computeIfAbsent(listener.eventType(), k -> new ArrayList<>()).add(listener);
        }

        byOwner.computeIfAbsent(owner, k -> new ArrayList<>()).addAll(listeners);
        dispatchCache.clear();
    }

    /** {@link Listener#unsubscribe()} から呼ばれます。 */
    void remove(Listener listener) {
        synchronized (mutationLock) {
            if (!listener.isSubscribed()) {
                return;
            }

            listener.markUnsubscribed();

            List<Listener> bucket = byType.get(listener.eventType());

            if (bucket != null) {
                bucket.remove(listener);

                if (bucket.isEmpty()) {
                    byType.remove(listener.eventType());
                }
            }

            List<Listener> owned = byOwner.get(listener.owner());

            if (owned != null) {
                owned.remove(listener);

                if (owned.isEmpty()) {
                    byOwner.remove(listener.owner());
                }
            }

            dispatchCache.clear();
        }
    }

    /**
     * そのイベント型へ届くリスナーを、型をまたいで 1 本に並べ直します。
     * 親クラスとインターフェースは {@link Class#isAssignableFrom(Class)} でまとめて拾えます。
     */
    private Listener[] buildChain(Class<?> eventType) {
        synchronized (mutationLock) {
            Listener[] cached = dispatchCache.get(eventType);

            if (cached != null) {
                return cached;
            }

            List<Listener> collected = new ArrayList<>();

            for (Map.Entry<Class<?>, List<Listener>> entry : byType.entrySet()) {
                if (entry.getKey().isAssignableFrom(eventType)) {
                    collected.addAll(entry.getValue());
                }
            }

            collected.sort(ORDER);

            Listener[] chain = collected.toArray(Listener[]::new);
            dispatchCache.put(eventType, chain);

            return chain;
        }
    }

    /**
     * {@code @Subscribe} が付いたメソッドを、派生側を優先して 1 つずつ集めます。
     * 親で宣言したメソッドを子で上書きした場合、登録されるのは子の 1 件だけです。
     */
    private static List<Method> collectSubscribers(Class<?> type) {
        Map<String, Method> unique = new LinkedHashMap<>();

        for (Class<?> current : hierarchy(type)) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(Subscribe.class) || method.isBridge() || method.isSynthetic()) {
                    continue;
                }

                validate(method);
                unique.putIfAbsent(signatureOf(method), method);
            }
        }

        return List.copyOf(unique.values());
    }

    /** 派生側から順に、クラスとインターフェースをたどります。 */
    private static List<Class<?>> hierarchy(Class<?> type) {
        Set<Class<?>> visited = new LinkedHashSet<>();
        Deque<Class<?>> queue = new ArrayDeque<>();
        queue.add(type);

        while (!queue.isEmpty()) {
            Class<?> current = queue.poll();

            if (current == null || current == Object.class || !visited.add(current)) {
                continue;
            }

            if (current.getSuperclass() != null) {
                queue.add(current.getSuperclass());
            }

            for (Class<?> each : current.getInterfaces()) {
                queue.add(each);
            }
        }

        return List.copyOf(visited);
    }

    private static void validate(Method method) {
        if (method.getParameterCount() != 1) {
            throw new IllegalArgumentException(describe(method) + " は引数がちょうど 1 つである必要があります");
        }

        if (method.getReturnType() != void.class) {
            throw new IllegalArgumentException(describe(method) + " は戻り値が void である必要があります");
        }

        if (Modifier.isStatic(method.getModifiers())) {
            throw new IllegalArgumentException(describe(method) + " は static であってはいけません");
        }

        if (Modifier.isAbstract(method.getModifiers())) {
            throw new IllegalArgumentException(describe(method) + " は abstract であってはいけません");
        }

        if (method.getParameterTypes()[0].isPrimitive()) {
            throw new IllegalArgumentException(describe(method) + " の引数はプリミティブ型にできません");
        }
    }

    private static String signatureOf(Method method) {
        StringBuilder builder = new StringBuilder(method.getName());

        for (Class<?> parameter : method.getParameterTypes()) {
            builder.append('/').append(parameter.getName());
        }

        return builder.toString();
    }

    private static String describe(Method method) {
        return "@Subscribe が付いた " + method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private static MethodHandle bind(Method method, Object owner) {
        try {
            if (!method.canAccess(owner)) {
                method.setAccessible(true);
            }

            return LOOKUP.unreflect(method).bindTo(owner);
        } catch (IllegalAccessException | InaccessibleObjectException e) {
            throw new IllegalArgumentException(describe(method) + " へアクセスできません", e);
        }
    }
}
