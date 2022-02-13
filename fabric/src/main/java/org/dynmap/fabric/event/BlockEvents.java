package org.dynmap.fabric.event;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Material;

public class BlockEvents {
    private BlockEvents() {
    }

    public static Event<BlockCallback> BLOCK_EVENT = EventFactory.createArrayBacked(BlockCallback.class,
            (listeners) -> (world, pos) -> {
                for (BlockCallback callback : listeners) {
                    callback.onBlockEvent(world, pos);
                }
            }
    );

    public static Event<SignChangeCallback> SIGN_CHANGE_EVENT = EventFactory.createArrayBacked(SignChangeCallback.class,
            (listeners) -> (world, pos, lines, material, player) -> {
                for (SignChangeCallback callback : listeners) {
                    callback.onSignChange(world, pos, lines, material, player);
                }
            }
    );

    @FunctionalInterface
    public interface BlockCallback {
        void onBlockEvent(Level world, BlockPos pos);
    }

    @FunctionalInterface
    public interface SignChangeCallback {
        void onSignChange(ServerLevel world, BlockPos pos, String[] lines, Material material, ServerPlayer player);
    }
}
