package cn.compatlogin.migration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Raised when one player name maps to several UUIDs, which happens as soon as two identity providers
 * own the same name. A migration must never guess which of them the administrator meant.
 */
final class AmbiguousPlayerNameException extends Exception {
    private static final long serialVersionUID = 1L;

    private final List<PlayerIdentity> candidates;

    AmbiguousPlayerNameException(String input, List<PlayerIdentity> candidates) {
        super("Player name '" + input + "' matches " + candidates.size() + " UUIDs: " + describe(candidates));
        this.candidates = Collections.unmodifiableList(new ArrayList<PlayerIdentity>(candidates));
    }

    List<PlayerIdentity> getCandidates() {
        return candidates;
    }

    private static String describe(List<PlayerIdentity> candidates) {
        StringBuilder description = new StringBuilder();
        for (PlayerIdentity candidate : candidates) {
            if (description.length() > 0) {
                description.append(", ");
            }
            description.append(candidate.getUuid());
        }
        return description.toString();
    }
}
