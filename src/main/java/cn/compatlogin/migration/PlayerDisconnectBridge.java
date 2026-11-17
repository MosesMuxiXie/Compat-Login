package cn.compatlogin.migration;

import cn.compatlogin.CompatLogin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Invokes the one connection selected by the migration, across listener API versions. */
final class PlayerDisconnectBridge {
    // Official/dev name, 1.20.2+ ServerCommonPacketListenerImpl, then 1.16-1.20.1 game listener.
    // The latter moved to a parent with a NEW intermediary name, so a compiled legacy call cannot link.
    private static final String[] METHOD_NAMES = {"disconnect", "method_52396", "method_14367"};
    private static final ClassValue<Accessors> ACCESSORS = new ClassValue<Accessors>() {
        @Override
        protected Accessors computeValue(Class<?> type) {
            List<Method> methods = new ArrayList<Method>();
            for (String name : METHOD_NAMES) {
                for (Method method : type.getMethods()) {
                    if (name.equals(method.getName()) && !Modifier.isStatic(method.getModifiers())
                        && method.getReturnType() == void.class && method.getParameterTypes().length == 1) {
                        methods.add(method);
                    }
                }
            }
            return new Accessors(methods);
        }
    };

    private PlayerDisconnectBridge() { }

    /** True means dispatched to this receiver only; the caller still waits until it is offline. */
    static boolean disconnect(Object connection, Object reason) {
        if (connection == null || reason == null) {
            return false;
        }
        try {
            Accessors accessors = ACCESSORS.get(connection.getClass());
            for (Method method : accessors.methods) {
                if (method.getParameterTypes()[0].isInstance(reason)) {
                    method.invoke(connection, reason);
                    return true;
                }
            }
            throw new NoSuchMethodException("No compatible disconnect(Component) on " + connection.getClass().getName());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            Throwable cause = failure instanceof InvocationTargetException && failure.getCause() != null
                ? failure.getCause() : failure;
            CompatLogin.LOGGER.error("Cannot disconnect the migration target; this migration will be cancelled", cause);
        }
        return false;
    }

    private static final class Accessors {
        final List<Method> methods;
        Accessors(List<Method> methods) { this.methods = methods; }
    }
}
