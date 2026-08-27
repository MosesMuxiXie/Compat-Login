package cn.compatlogin.migration;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationLoginLockTest {
    private static final UUID SOURCE = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID TARGET = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void locksOnlyTheTargetWhileDisconnectingOrMigrating() {
        MigrationSession session = session();

        session.state = MigrationSession.AWAITING_CONFIRMATION;
        assertFalse(MigrationManager.blocksLogin(session, TARGET));

        session.state = MigrationSession.WAITING_FOR_DISCONNECT;
        assertTrue(MigrationManager.blocksLogin(session, TARGET));
        assertFalse(MigrationManager.blocksLogin(session, SOURCE));

        session.state = MigrationSession.MIGRATING;
        assertTrue(MigrationManager.blocksLogin(session, TARGET));

        session.state = MigrationSession.COMPLETED;
        assertFalse(MigrationManager.blocksLogin(session, TARGET));
    }

    @Test
    void malformedOrMissingSessionsDoNotBlockUnrelatedLogins() {
        assertFalse(MigrationManager.blocksLogin(null, TARGET));
        assertFalse(MigrationManager.blocksLogin(session(), null));

        MigrationSession malformed = session();
        malformed.state = MigrationSession.MIGRATING;
        malformed.targetUuid = "not-a-uuid";
        assertFalse(MigrationManager.blocksLogin(malformed, TARGET));
    }

    private static MigrationSession session() {
        return new MigrationSession(
            "ABCDEFGH",
            new PlayerIdentity("Source", SOURCE),
            new PlayerIdentity("Target", TARGET),
            "Server",
            1L,
            2L
        );
    }
}
