package cn.compatlogin.migration;

import java.util.UUID;

final class MigrationSession {
    static final String AWAITING_CONFIRMATION = "awaiting_confirmation";
    static final String WAITING_FOR_DISCONNECT = "waiting_for_disconnect";
    static final String MIGRATING = "migrating";
    static final String COMPLETED = "completed";

    String code;
    String sourceName;
    String sourceUuid;
    String targetName;
    String targetUuid;
    String requestedBy;
    String state;
    long createdAtMillis;
    long codeExpiresAtMillis;
    long promptedAtMillis;
    long confirmationExpiresAtMillis;
    long disconnectDeadlineMillis;
    /** Kept only to detect and clean up migration bans written by versions before the UUID login lock. */
    long unbanAtMillis;
    String backupPath;

    MigrationSession() {
    }

    MigrationSession(
        String code,
        PlayerIdentity source,
        PlayerIdentity target,
        String requestedBy,
        long now,
        long codeExpiresAtMillis
    ) {
        this.code = code;
        this.sourceName = source.getName();
        this.sourceUuid = source.getUuid().toString();
        this.targetName = target.getName();
        this.targetUuid = target.getUuid().toString();
        this.requestedBy = requestedBy;
        this.state = AWAITING_CONFIRMATION;
        this.createdAtMillis = now;
        this.codeExpiresAtMillis = codeExpiresAtMillis;
    }

    UUID sourceUuid() {
        return UUID.fromString(sourceUuid);
    }

    UUID targetUuid() {
        return UUID.fromString(targetUuid);

    }

    PlayerIdentity sourceIdentity() {
        return new PlayerIdentity(sourceName, sourceUuid());
    }

    PlayerIdentity targetIdentity() {
        return new PlayerIdentity(targetName, targetUuid());
    }
}
