package com.r5ylx.events;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/**
 * Marks a method that receives events.
 *
 * <p>The annotated method must meet the following conditions. Annotating anything else makes
 * {@code EventBus#subscribe(Object)} throw. It is never silently ignored.
 *
 * <ul>
 *   <li>exactly one parameter</li>
 *   <li>a return type of {@code void}</li>
 *   <li>neither {@code static} nor {@code abstract}</li>
 * </ul>
 *
 * <p>Annotations are not inherited by methods. A method annotated in a supertype is subscribed
 * without annotating it again in the subclass, and annotating it again does not register it twice.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Subscribe {
    /** The order in which it runs. Higher values run first. */
    EventPriority priority() default EventPriority.NORMAL;

    /** When true, the subscription is cancelled automatically once it has been called. */
    boolean once() default false;
}
