package io.github.jiangjinyue2333.actiongrid.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

public final class HookMethodLookupTest {
    @Test
    public void findsProtectedInheritedImplementationWhenChildHasNoDeclaration() throws Exception {
        Method method = HookMethodLookup.find(CellLayout.class, "dispatchHoverEvent", Object.class);
        assertEquals(HostView.class, method.getDeclaringClass());
        assertTrue((boolean) method.invoke(new CellLayout(), new Object()));
    }

    @Test
    public void prefersAnOverrideAndDoesNotGuessDifferentSignatures() {
        Method method = HookMethodLookup.find(OverrideLayout.class,
                "dispatchHoverEvent", Object.class);
        assertEquals(OverrideLayout.class, method.getDeclaringClass());
        assertNull(HookMethodLookup.find(CellLayout.class, "dispatchHoverEvent", String.class));
        assertNull(HookMethodLookup.find(CellLayout.class, "missing", Object.class));
    }

    private static class HostView {
        protected boolean dispatchHoverEvent(Object event) {
            return event != null;
        }
    }

    private static class CellLayout extends HostView {
    }

    private static final class OverrideLayout extends HostView {
        @Override
        protected boolean dispatchHoverEvent(Object event) {
            return false;
        }
    }
}
