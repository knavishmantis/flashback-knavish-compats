package com.moulberry.flashback.compat.identity2;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.action.Action;
import com.moulberry.flashback.playback.ReplayServer;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ActionIdentity2Morph implements Action {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback-identity2");
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

    // Pending morphs for server-side players not yet spawned
    private static final Map<UUID, MorphData> pendingMorphs = new ConcurrentHashMap<>();
    // Pending morphs for client-side players not yet available
    private static final Map<UUID, MorphData> pendingClientMorphs = new ConcurrentHashMap<>();

    private ActionIdentity2Morph() {}

    @Override
    public Identifier name() {
        return NAME;
    }

    @Override
    public void handle(ReplayServer replayServer, RegistryFriendlyByteBuf friendlyByteBuf) {
        MorphData data = STREAM_CODEC.decode(friendlyByteBuf);

        LOGGER.info("[PLAYBACK] Morph action: player={} type={} snapshot={}",
            data.playerUuid(), data.entityTypeId(), replayServer.isProcessingSnapshot);

        UUID uuid;
        try {
            uuid = UUID.fromString(data.playerUuid());
        } catch (IllegalArgumentException e) {
            return;
        }

        // Always queue for client-side application (the rendering side)
        pendingClientMorphs.put(uuid, data);

        // Try to apply server-side immediately
        for (ServerPlayer player : replayServer.getPlayerList().getPlayers()) {
            if (player.getUUID().equals(uuid)) {
                Identity2Playback.applyMorph(player, data.entityTypeId(), data.variantNbt());
                pendingMorphs.remove(uuid);
                return;
            }
        }

        // Server player not found yet — buffer for later
        pendingMorphs.put(uuid, data);
    }

    /**
     * Flush pending server-side morphs. Called from server tick.
     */
    public static void flushPendingMorphs(ReplayServer replayServer) {
        if (!pendingMorphs.isEmpty()) {
            var iterator = pendingMorphs.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                UUID uuid = entry.getKey();
                MorphData data = entry.getValue();

                for (ServerPlayer player : replayServer.getPlayerList().getPlayers()) {
                    if (player.getUUID().equals(uuid)) {
                        LOGGER.info("[FLUSH-SERVER] Applying deferred morph {} to {}", data.entityTypeId(), uuid);
                        Identity2Playback.applyMorph(player, data.entityTypeId(), data.variantNbt());
                        iterator.remove();
                        break;
                    }
                }
            }
        }

        // Also flush client-side pending morphs on the render thread
        if (!pendingClientMorphs.isEmpty()) {
            Minecraft.getInstance().execute(() -> {
                Identity2Playback.applyPendingToClientPlayers(pendingClientMorphs);
            });
        }
    }

    public static void clearPending() {
        pendingMorphs.clear();
        pendingClientMorphs.clear();
    }
}
