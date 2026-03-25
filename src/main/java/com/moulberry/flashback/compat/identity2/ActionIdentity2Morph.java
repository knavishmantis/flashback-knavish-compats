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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom Flashback action that stores and replays Identity2 morph state.
 *
 * During snapshot processing, player entities may not exist yet when morph
 * actions fire. We buffer pending morphs and apply them when the player
 * is found or when explicitly flushed after snapshot completes.
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

    // Pending morphs that couldn't be applied because the player wasn't spawned yet
    private static final Map<UUID, MorphData> pendingMorphs = new ConcurrentHashMap<>();

    private ActionIdentity2Morph() {}

    @Override
    public Identifier name() {
        return NAME;
    }

    @Override
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf friendlyByteBuf) {
        MorphData data = STREAM_CODEC.decode(friendlyByteBuf);

        UUID uuid;
        try {
            uuid = UUID.fromString(data.playerUuid());
        } catch (IllegalArgumentException e) {
            return;
        }

        // Try to apply immediately
        for (ServerPlayer player : replayServer.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(uuid)) {
                Identity2Playback.applyMorph(player, data.entityTypeId(), data.variantNbt());
                pendingMorphs.remove(uuid);
                return;
            }
        }

        // Player not found yet (common during snapshot processing) — buffer it
        pendingMorphs.put(uuid, data);
    }

    /**
     * Try to apply any pending morphs. Called after snapshot processing completes
     * and periodically during replay to catch late-spawning players.
     */
    public static void flushPendingMorphs(ReplayServer replayServer) {
        if (pendingMorphs.isEmpty()) return;

        var iterator = pendingMorphs.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            UUID uuid = entry.getKey();
            MorphData data = entry.getValue();

            for (ServerPlayer player : replayServer.getPlayerList().getPlayers()) {
                if (player.getUUID().equals(uuid)) {
                    Identity2Playback.applyMorph(player, data.entityTypeId(), data.variantNbt());
                    iterator.remove();
                    break;
                }
            }
        }
    }

    public static void clearPending() {
        pendingMorphs.clear();
    }
}
