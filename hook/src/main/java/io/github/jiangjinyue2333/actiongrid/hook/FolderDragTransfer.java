package io.github.jiangjinyue2333.actiongrid.hook;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

final class FolderDragTransfer {
    private final Object launcher;
    private final Object item;
    private final Object source;
    private final Method removeFromSource;
    private final Object items;
    private final Object sources;

    private FolderDragTransfer(Object launcher, Object item, Object source,
                               Method removeFromSource) {
        this.launcher = launcher;
        this.item = item;
        this.source = source;
        this.removeFromSource = removeFromSource;
        Class<?>[] parameters = removeFromSource.getParameterTypes();
        items = Array.newInstance(parameters[0].getComponentType(), 1);
        sources = Array.newInstance(parameters[1].getComponentType(), 1);
        Array.set(items, 0, item);
        Array.set(sources, 0, source);
    }

    static FolderDragTransfer prepare(Object launcher, Object item, long sourceContainer)
            throws ReflectiveOperationException {
        if (!Boolean.FALSE.equals(launcher.getClass().getMethod("isFolderOpened")
                .invoke(launcher))) {
            throw new IllegalStateException("Source folder must be closed before moving out");
        }
        Object cling = launcher.getClass().getMethod("getFolderCling").invoke(launcher);
        Object folder = cling.getClass().getMethod("getFolder").invoke(cling);
        Object container = folder.getClass().getMethod("getContainerId").invoke(folder);
        if (!(container instanceof Long) || (Long) container != sourceContainer) {
            throw new IllegalStateException("The folder drag source has changed");
        }
        for (Class<?> type = launcher.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!method.getName().equals("removeItemsFromSourceWhenMultiDrag")
                        || Modifier.isStatic(method.getModifiers()) || parameters.length != 2
                        || !parameters[0].isArray() || !parameters[1].isArray()
                        || !parameters[0].getComponentType().isInstance(item)
                        || !parameters[1].getComponentType().isInstance(folder)) {
                    continue;
                }
                method.setAccessible(true);
                return new FolderDragTransfer(launcher, item, folder, method);
            }
        }
        throw new NoSuchMethodException("removeItemsFromSourceWhenMultiDrag");
    }

    void onDragStarted(Object dragObject) throws ReflectiveOperationException {
        if (dragObject == null
                || dragObject.getClass().getMethod("getDragInfo").invoke(dragObject) != item
                || dragObject.getClass().getMethod("getDragSource").invoke(dragObject) != source
                || !Integer.valueOf(1).equals(dragObject.getClass()
                .getMethod("getDraggingSize").invoke(dragObject))) {
            throw new IllegalStateException("The native drag does not match the lifted item");
        }
        // Native drag has registered pending state, so cancel/drop can restore/finalize it.
        removeFromSource.invoke(launcher, items, sources);
    }
}
