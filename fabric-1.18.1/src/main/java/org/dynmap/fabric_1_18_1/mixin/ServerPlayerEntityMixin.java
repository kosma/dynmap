package org.dynmap.fabric_1_18_1.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.dynmap.fabric.event.PlayerEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public class ServerPlayerEntityMixin {
    @Inject(method = "teleport", at = @At("RETURN"))
    public void teleport(ServerLevel targetWorld, double x, double y, double z, float yaw, float pitch, CallbackInfo info) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (targetWorld != player.level) {
            PlayerEvents.PLAYER_CHANGED_DIMENSION.invoker().onPlayerChangedDimension(player);
        }
    }

    @Inject(method = "moveToWorld", at = @At("RETURN"))
    public void moveToWorld(ServerLevel destination, CallbackInfoReturnable<Entity> info) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (player.getRemovalReason() == null) {
            PlayerEvents.PLAYER_CHANGED_DIMENSION.invoker().onPlayerChangedDimension(player);
        }
    }
}
