package org.dynmap.fabric.mixin;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.dynmap.fabric.event.PlayerEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerList.class)
public class PlayerManagerMixin {
    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    public void onPlayerConnect(Connection connection, ServerPlayer player, CallbackInfo info) {
        PlayerEvents.PLAYER_LOGGED_IN.invoker().onPlayerLoggedIn(player);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    public void remove(ServerPlayer player, CallbackInfo info) {
        PlayerEvents.PLAYER_LOGGED_OUT.invoker().onPlayerLoggedOut(player);
    }

    @Inject(method = "respawnPlayer", at = @At("RETURN"))
    public void respawnPlayer(CallbackInfoReturnable<ServerPlayer> info) {
        PlayerEvents.PLAYER_RESPAWN.invoker().onPlayerRespawn(info.getReturnValue());
    }
}
