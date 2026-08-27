package cn.compatlogin.mixin;

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

@Mixin(PlayerList.class)
public abstract class PlayerListLoginMixin {
    @Inject(method = "canPlayerLogin", at = @At("HEAD"), cancellable = true)
    private void compatLogin$denyLoginDuringMigration(
        SocketAddress address,
        @Coerce Object identity,
        CallbackInfoReturnable<Component> callback
    ) {
        UUID uuid = AuthlibProfileAdapter.readProfileId(identity);
        if (!MigrationManager.isLoginLocked(uuid)) {
            return;
        }
        Component message = MigrationManager.loginLockMessage();
        if (message != null) {
            callback.setReturnValue(message);
        }
    }
}
