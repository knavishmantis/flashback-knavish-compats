package com.moulberry.flashback.compat.identity2;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.action.Action;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Custom Flashback action that stores and replays Identity2 morph state.
 *
 * Packet format:
 *   - UUID (player uuid)
 *   - String (entity type id, e.g. "minecraft:creeper", or "" for unmorph)
 *   - String (variant NBT as string, or "" for none)
 */
public class ActionIdentity2Morph implements Action {

    private static final Identifier NAME = Flashback.createIdentifier("action/identity2_morph");
    public static final ActionIdentity2Morph INSTANCE = new ActionIdentity2Morph();

    public static final StreamCodec<RegistryFriendlyByteBuf, MorphData> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8,
        MorphData::playerUuid,
        ByteBufCodecs.STRING_UTF8,
        MorphData::entityTypeId,
        ByteBufCodecs.STRING_UTF8,
        MorphData::variantNbt,
        MorphData::new
    );

    public record MorphData(String playerUuid, String entityTypeId, String variantNbt) {}

    private ActionIdentity2Morph() {}

    @Override
    public Identifier name() {
        return NAME;
    }

    @Override
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf friendlyByteBuf) {
        MorphData data = STREAM_CODEC.decode(friendlyByteBuf);

        if (replayServer.isProcessingSnapshot) {
            // During snapshot processing, still apply morph state to keep entities in sync
        }

        // Find the player entity in the replay world by UUID
        UUID uuid;
        try {
            uuid = UUID.fromString(data.playerUuid());
        } catch (IllegalArgumentException e) {
            return;
        }

        // Apply the morph on the main thread
        for (ServerPlayer player : replayServer.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(uuid)) {
                Identity2Playback.applyMorph(player, data.entityTypeId(), data.variantNbt());
                return;
            }
        }
    }
}
