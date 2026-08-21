package cn.compatlogin.mixin;

import cn.compatlogin.migration.MigrationManager;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "tickServer", at = @At("TAIL"))
    private void compatLogin$tickMigrations(BooleanSupplier shouldKeepTicking, CallbackInfo callback) {
        MigrationManager.tick((MinecraftServer) (Object) this);
    }
}
