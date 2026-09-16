package cn.compatlogin.mixin;

import cn.compatlogin.CompatLogin;
import cn.compatlogin.auth.AuthlibProfileAdapter;
import cn.compatlogin.migration.MigrationManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;
import java.util.UUID;

/**
 * Rejects logins of a UUID that a running migration has locked.
 *
 * <p>The second parameter of {@code PlayerList.canPlayerLogin} drifted across the supported range and
 * therefore cannot be typed here: 1.16-1.21.8 pass an authlib {@code GameProfile} (an anonymous
 * {@code UserIdentity} implementation), while 1.21.9+ pass a {@code NameAndId} record that only exposes
 * {@code id()}/{@code name()}. {@link AuthlibProfileAdapter#readProfileId(Object)} absorbs both shapes;
 * anything it cannot read only disables the migration lock for that login instead of killing the
 * connection.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {
    private static volatile boolean compatLogin$identityReadFailureReported;

    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void compatLogin$denyLoginDuringMigration(
        SocketAddress address,
        @Coerce Object identity,
        CallbackInfoReturnable<Component> callback
    ) {
        UUID uuid = compatLogin$readIdentity(identity);
        if (!MigrationManager.isLoginLocked(uuid)) {
            return;
        }
        Component message = MigrationManager.loginLockMessage();
        if (message != null) {
            callback.setReturnValue(message);
        }
    }

    /**
     * Reads the UUID defensively: a migration lock must never be the reason a player cannot connect.
     * An unreadable identity is reported once and then treated as "no lock".
     */
    private static UUID compatLogin$readIdentity(Object identity) {
        if (identity == null) {
            return null;
        }
        try {
            return AuthlibProfileAdapter.readProfileId(identity);
        } catch (RuntimeException | LinkageError failure) {
            if (!compatLogin$identityReadFailureReported) {
                compatLogin$identityReadFailureReported = true;
                CompatLogin.LOGGER.error(
                    "Cannot read the player UUID from the login identity of type {}, so the migration login lock is disabled for this server",
                    identity.getClass().getName(),
                    failure
                );
            }
            return null;
        }
    }
}
