package com.r5ylx.events;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * イベントを受け取るメソッドに付けます。
 *
 * <p>対象のメソッドは次の条件を満たす必要があります。満たさないものに付けると
 * {@code EventBus#subscribe(Object)} が例外を投げます。黙って無視はしません。
 *
 * <ul>
 *   <li>引数がちょうど 1 つ</li>
 *   <li>戻り値が {@code void}</li>
 *   <li>{@code static} でも {@code abstract} でもない</li>
 * </ul>
 *
 * <p>アノテーションはメソッドへ継承されません。親クラスのメソッドに付いていれば、
 * 子クラスで付け直さなくても購読されます。付け直しても二重には呼ばれません。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Subscribe {
    /** 実行順序です。大きいものから先に呼ばれます。 */
    EventPriority priority() default EventPriority.NORMAL;

    /** true にすると、1 回呼ばれた時点で自動的に解除されます。 */
    boolean once() default false;
}
