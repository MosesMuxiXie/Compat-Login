package cn.compatlogin.mixin;

import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.players.PlayerList;
import net.minecraft.stats.ServerStatsCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

@Mixin(PlayerList.class)
public interface PlayerListAccessor {
    @Accessor("stats")
    Map<UUID, ServerStatsCounter> compatLogin$getStats();

    @Accessor("advancements")
    Map<UUID, PlayerAdvancements> compatLogin$getAdvancements();
}
