package cn.compatlogin.mixin;

import cn.compatlogin.CompatLogin;
import cn.compatlogin.auth.MultiAuthService;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilMinecraftSessionService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetAddress;

@Mixin(value = YggdrasilMinecraftSessionService.class, remap = false)
public abstract class YggdrasilMinecraftSessionServiceMixin {
    @Inject(
        method = "hasJoinedServer(Ljava/lang/String;Ljava/lang/String;Ljava/net/InetAddress;)Lcom/mojang/authlib/yggdrasil/ProfileResult;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void compatLogin$hasJoinedServer(
        String username,
        String serverId,
        InetAddress address,
        CallbackInfoReturnable<ProfileResult> callback
    ) throws AuthenticationUnavailableException {
        MultiAuthService authenticator = CompatLogin.authenticator();
        if (authenticator != null) {
            callback.setReturnValue(authenticator.hasJoinedServer(username, serverId, address));
        }
    }
}
