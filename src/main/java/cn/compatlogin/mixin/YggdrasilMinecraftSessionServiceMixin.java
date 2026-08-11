package cn.compatlogin.mixin;

import cn.compatlogin.CompatLogin;
import cn.compatlogin.auth.AuthenticatedProfile;
import cn.compatlogin.auth.AuthenticationServiceUnavailableException;
import cn.compatlogin.auth.AuthlibProfileAdapter;
import cn.compatlogin.auth.MultiAuthService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetAddress;

@Mixin(targets = "com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService", remap = false)
public abstract class YggdrasilMinecraftSessionServiceMixin {
    @Group(name = "compatLogin$hasJoinedServer", min = 1, max = 1)
    @Inject(
        method = "hasJoinedServer(Lcom/mojang/authlib/GameProfile;Ljava/lang/String;Ljava/net/InetAddress;)Lcom/mojang/authlib/GameProfile;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void compatLogin$hasJoinedServerGameProfile(
        @Coerce Object profile,
        String serverId,
        InetAddress address,
        CallbackInfoReturnable<Object> callback
    ) {
        authenticate(AuthlibProfileAdapter.readProfileName(profile), serverId, address, callback, false);
    }

    @Group(name = "compatLogin$hasJoinedServer")
    @Inject(
        method = "hasJoinedServer(Ljava/lang/String;Ljava/lang/String;Ljava/net/InetAddress;)Lcom/mojang/authlib/GameProfile;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void compatLogin$hasJoinedServerLegacy(
        String username,
        String serverId,
        InetAddress address,
        CallbackInfoReturnable<Object> callback
    ) {
        authenticate(username, serverId, address, callback, false);
    }

    @Group(name = "compatLogin$hasJoinedServer")
    @Inject(
        method = "hasJoinedServer(Ljava/lang/String;Ljava/lang/String;Ljava/net/InetAddress;)Lcom/mojang/authlib/yggdrasil/ProfileResult;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false,
        require = 0
    )
    private void compatLogin$hasJoinedServerModern(
        String username,
        String serverId,
        InetAddress address,
        CallbackInfoReturnable<Object> callback
    ) {
        authenticate(username, serverId, address, callback, true);
    }

    private static void authenticate(
        String username,
        String serverId,
        InetAddress address,
        CallbackInfoReturnable<Object> callback,
        boolean modernResult
    ) {
        MultiAuthService authenticator = CompatLogin.authenticator();
        if (authenticator == null) {
            return;
        }

        try {
            AuthenticatedProfile profile = authenticator.hasJoinedServer(username, serverId, address);
            if (profile == null) {
                callback.setReturnValue(null);
                return;
            }

            Object gameProfile = AuthlibProfileAdapter.createGameProfile(profile);
            callback.setReturnValue(modernResult
                ? AuthlibProfileAdapter.createProfileResult(gameProfile)
                : gameProfile);
        } catch (AuthenticationServiceUnavailableException exception) {
            AuthlibProfileAdapter.throwAuthenticationUnavailable(exception);
        }
    }
}
