package cn.compatlogin.migration;

import java.util.Objects;
import java.util.UUID;

public final class PlayerIdentity {
    private final String name;
    private final UUID uuid;

    public PlayerIdentity(String name, UUID uuid) {
        this.name = Objects.requireNonNull(name, "name");
        this.uuid = Objects.requireNonNull(uuid, "uuid");
    }

    public String getName() {
        return name;
    }

    public UUID getUuid() {
        return uuid;
    }

    @Override
    public String toString() {
        return name + " (" + uuid + ")";
    }
}
