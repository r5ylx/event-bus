package com.r5ylx.events;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;

/** {@code @Subscribe} が付いたメソッドから作られるリスナーです。 */
final class MethodListener extends Listener {
    private final MethodHandle handle;
    private final String methodName;

    MethodListener(EventBus bus, Object owner, Method method, MethodHandle handle,
                    int priority, boolean once, long sequence) {
        super(bus, owner, method.getParameterTypes()[0], priority, once, sequence);
        this.handle = handle;
        this.methodName = method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    @Override
    void invoke(Object event) throws Throwable {
        handle.invoke(event);
    }

    @Override
    public String toString() {
        return "MethodListener[" + methodName
            + ", priority=" + priority()
            + (isOnce() ? ", once" : "")
            + "]";
    }
}
