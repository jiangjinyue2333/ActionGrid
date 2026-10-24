package io.github.jiangjinyue2333.actiongrid.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class FolderDragTransferTest {
    @Test
    public void movingOutOfClosedFolderLeavesNoDuplicate() throws Exception {
        Launcher launcher = new Launcher();
        Item moved = launcher.folder.contents.get(0);
        Item remaining = launcher.folder.contents.get(1);
        FolderDragTransfer transfer = FolderDragTransfer.prepare(launcher, moved, 42);
        DragObject drag = launcher.startDrag(moved);

        transfer.onDragStarted(drag);
        launcher.dropOnDesktop(drag);

        assertEquals(List.of(remaining), launcher.folder.contents);
        assertEquals(List.of(moved), launcher.desktop);
        assertEquals(1, launcher.removeCalls);
        assertEquals(0, launcher.pending);
    }

    @Test
    public void cancelledDropRestoresTheOriginalFolderOnce() throws Exception {
        Launcher launcher = new Launcher();
        Item moved = launcher.folder.contents.get(0);
        FolderDragTransfer transfer = FolderDragTransfer.prepare(launcher, moved, 42);
        DragObject drag = launcher.startDrag(moved);

        transfer.onDragStarted(drag);
        launcher.cancelDrag(drag);

        assertEquals(2, launcher.folder.contents.size());
        assertEquals(1, launcher.folder.contents.stream().filter(item -> item == moved).count());
        assertTrue(launcher.desktop.isEmpty());
        assertEquals(42, moved.container);
        assertEquals(0, launcher.pending);
    }

    @Test
    public void folderDeletionRemainsDeferredUntilNativeDropFinishes() throws Exception {
        Launcher launcher = new Launcher();
        launcher.folder.deleteWhenOne = true;
        Item moved = launcher.folder.contents.get(0);
        FolderDragTransfer transfer = FolderDragTransfer.prepare(launcher, moved, 42);
        DragObject drag = launcher.startDrag(moved);

        transfer.onDragStarted(drag);
        assertFalse(launcher.folder.deleted);
        launcher.dropOnDesktop(drag);

        assertTrue(launcher.folder.deleted);
    }

    @Test
    public void cancellationKeepsTwoItemFolderIntact() throws Exception {
        Launcher launcher = new Launcher();
        launcher.folder.deleteWhenOne = true;
        Item moved = launcher.folder.contents.get(0);
        FolderDragTransfer transfer = FolderDragTransfer.prepare(launcher, moved, 42);
        DragObject drag = launcher.startDrag(moved);

        transfer.onDragStarted(drag);
        launcher.cancelDrag(drag);

        assertEquals(2, launcher.folder.contents.size());
        assertFalse(launcher.folder.deleted);
    }

    @Test
    public void rejectedOrChangedDragDoesNotRemoveAnyItem() throws Exception {
        Launcher launcher = new Launcher();
        Item moved = launcher.folder.contents.get(0);
        FolderDragTransfer transfer = FolderDragTransfer.prepare(launcher, moved, 42);

        assertThrows(IllegalStateException.class, () -> transfer.onDragStarted(null));
        assertThrows(IllegalStateException.class, () -> transfer.onDragStarted(
                new DragObject(launcher.folder.contents.get(1), launcher.folder, 1)));
        assertThrows(IllegalStateException.class, () -> transfer.onDragStarted(
                new DragObject(moved, new Folder(), 1)));
        assertThrows(IllegalStateException.class, () -> transfer.onDragStarted(
                new DragObject(moved, launcher.folder, 2)));
        assertEquals(2, launcher.folder.contents.size());
        assertEquals(0, launcher.removeCalls);
    }

    @Test
    public void openOrReboundFolderFailsBeforeStartingTheDrag() {
        Launcher launcher = new Launcher();
        Item moved = launcher.folder.contents.get(0);
        launcher.opened = true;
        assertThrows(IllegalStateException.class,
                () -> FolderDragTransfer.prepare(launcher, moved, 42));
        launcher.opened = false;
        assertThrows(IllegalStateException.class,
                () -> FolderDragTransfer.prepare(launcher, moved, 99));
        assertEquals(0, launcher.pending);
        assertEquals(0, launcher.removeCalls);
    }

    @Test
    public void missingNativeCleanupFailsBeforeStartingTheDrag() {
        MissingCleanupLauncher launcher = new MissingCleanupLauncher();
        assertThrows(NoSuchMethodException.class, () -> FolderDragTransfer.prepare(
                launcher, launcher.folder.contents.get(0), 42));
        assertEquals(2, launcher.folder.contents.size());
    }

    public static final class Item {
        long container = 42;
    }

    public static final class Folder {
        final List<Item> contents = new ArrayList<>(List.of(new Item(), new Item()));
        boolean deleteWhenOne;
        boolean deleted;

        public long getContainerId() { return 42; }
    }

    public static final class Cling {
        private final Folder folder;

        Cling(Folder folder) { this.folder = folder; }

        public Folder getFolder() { return folder; }
    }

    public static class MissingCleanupLauncher {
        final Folder folder = new Folder();
        boolean opened;

        public boolean isFolderOpened() { return opened; }

        public Cling getFolderCling() { return new Cling(folder); }
    }

    // Model only the native pending/source protocol observed in the 7.x APK.
    public static class NativeLauncher extends MissingCleanupLauncher {
        final List<Item> desktop = new ArrayList<>();
        int pending;
        int removeCalls;

        private void removeItemsFromSourceWhenMultiDrag(Item[] items, Folder[] sources) {
            assertEquals("Pending state must exist before source cleanup", 1, pending);
            for (int index = 0; index < items.length; index++) {
                assertEquals(sources[index].getContainerId(), items[index].container);
                sources[index].contents.remove(items[index]);
                removeCalls++;
            }
        }

        DragObject startDrag(Item item) {
            pending++;
            return new DragObject(item, folder, 1);
        }

        void dropOnDesktop(DragObject drag) {
            drag.item.container = StableItemRef.CONTAINER_DESKTOP;
            desktop.add(drag.item);
            finishPending();
        }

        void cancelDrag(DragObject drag) {
            folder.contents.add(drag.item);
            finishPending();
        }

        private void finishPending() {
            pending--;
            if (pending == 0 && (folder.contents.isEmpty()
                    || (folder.deleteWhenOne && folder.contents.size() == 1))) {
                folder.deleted = true;
            }
        }
    }

    public static final class Launcher extends NativeLauncher {}

    public static final class DragObject {
        final Item item;
        final Folder source;
        final int size;

        DragObject(Item item, Folder source, int size) {
            this.item = item;
            this.source = source;
            this.size = size;
        }

        public Item getDragInfo() { return item; }

        public Folder getDragSource() { return source; }

        public int getDraggingSize() { return size; }
    }
}
