package cn.compatlogin.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** A Minecraft profile represented without linking against a specific authlib version. */
public final class AuthenticatedProfile {
    private final UUID id;
    private final String name;
    private final List<ProfileProperty> properties;

    public AuthenticatedProfile(UUID id, String name, List<ProfileProperty> properties) {
        this.id = id;
        this.name = name;
        this.properties = Collections.unmodifiableList(new ArrayList<ProfileProperty>(properties));
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<ProfileProperty> getProperties() {
        return properties;
    }

    public static final class ProfileProperty {
        private final String name;
        private final String value;
        private final String signature;

        public ProfileProperty(String name, String value, String signature) {
            this.name = name;
            this.value = value;
            this.signature = signature;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public String getSignature() {
            return signature;
        }
    }
}
