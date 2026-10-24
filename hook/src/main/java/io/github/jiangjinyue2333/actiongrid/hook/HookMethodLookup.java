package io.github.jiangjinyue2333.actiongrid.hook;

import java.lang.reflect.Method;

/** Optional hook entry points may move into a non-public superclass between launcher builds. */
final class HookMethodLookup {
    private HookMethodLookup() {
    }

    static Method find(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                // Continue to the actual implementation inherited by this class.
            } catch (RuntimeException | LinkageError ignored) {
                return null;
            }
        }
        return null;
    }
}
