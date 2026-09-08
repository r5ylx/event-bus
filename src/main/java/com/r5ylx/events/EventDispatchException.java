package com.r5ylx.events;

/**
 * リスナーが投げた例外を包んで再送出するための例外です。
 *
 * @see EventExceptionHandler#rethrowing()
 */
public class EventDispatchException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient Object event;
    private final transient Subscription subscription;

    public EventDispatchException(String message, Object event, Subscription subscription, Throwable cause) {
        super(message, cause);
        this.event = event;
        this.subscription = subscription;
    }

    /** 配送中だったイベントです。直列化された場合は null になります。 */
    public Object getEvent() {
        return event;
    }

    /** 例外を投げたリスナーの購読情報です。直列化された場合は null になります。 */
    public  Subscription getSubscription() {
        return subscription;
    }
}
