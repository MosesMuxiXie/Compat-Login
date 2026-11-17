package cn.compatlogin.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlayerDisconnectBridgeTest {
    private final Message reason = new Message();

    @Test
    void disconnectsOnlyTheSelectedLegacyConnection() {
        LegacyConnection target = new LegacyConnection();
        LegacyConnection bystander = new LegacyConnection();

        assertTrue(PlayerDisconnectBridge.disconnect(target, reason));

        assertSame(reason, target.reason);
        assertNull(bystander.reason);
    }

    @Test
    void usesTheInheritedCommonListenerMethodSinceMinecraft1202() {
        ModernConnection target = new ModernConnection();
        ModernConnection bystander = new ModernConnection();

        assertTrue(PlayerDisconnectBridge.disconnect(target, reason));

        assertSame(reason, target.reason);
        assertNull(bystander.reason);
    }

    @Test
    void supportsOfficialNamesAndIgnoresTheOtherDisconnectOverload() {
        OfficialConnection target = new OfficialConnection();

        assertTrue(PlayerDisconnectBridge.disconnect(target, reason));

        assertSame(reason, target.reason);
        assertFalse(target.wrongOverloadCalled);
    }

    @Test
    void reportsUnsupportedOrFailedDisconnectWithoutEscapingIntoTheServerTick() {
        assertFalse(PlayerDisconnectBridge.disconnect(new Object(), reason));
        assertFalse(PlayerDisconnectBridge.disconnect(null, reason));
        assertFalse(PlayerDisconnectBridge.disconnect(new LegacyConnection(), null));
        assertFalse(PlayerDisconnectBridge.disconnect(new ThrowingConnection(), reason));
        assertFalse(PlayerDisconnectBridge.disconnect(new WrongTypeConnection(), reason));
    }

    public static final class Message { }
    public static final class OtherMessage { }

    public static final class LegacyConnection {
        Object reason;
        public void method_14367(Message reason) { this.reason = reason; }
    }

    public static class CommonConnection {
        Object reason;
        public void method_52396(Message reason) { this.reason = reason; }
    }

    public static final class ModernConnection extends CommonConnection { }

    public static final class OfficialConnection {
        Object reason;
        boolean wrongOverloadCalled;
        public void disconnect(OtherMessage reason) { wrongOverloadCalled = true; }
        public void disconnect(Message reason) { this.reason = reason; }
    }

    public static final class ThrowingConnection {
        public void disconnect(Message reason) { throw new NoSuchMethodError("test linkage failure"); }
    }

    public static final class WrongTypeConnection {
        public void disconnect(OtherMessage reason) { fail("wrong overload must not be invoked"); }
    }
}
