package com.moulberry.flashback.compat.identity2;

import com.moulberry.flashback.Flashback;
import net.Gabou.identity2.util.EntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Identity2Recorder {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback-identity2");

    public static void writeSnapshotMorphStates() {
        if (Flashback.RECORDER == null) return;

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        LOGGER.info("[SNAPSHOT] Writing morph states for {} players", level.players().size());

        for (Player player : level.players()) {
            Entity identity = ((EntityAccessor) player).getCurrentIdentity();
            if (identity == null) {
                LOGGER.info("[SNAPSHOT] Player {} has no identity", player.getUUID());
                continue;
            }

            String entityTypeId = EntityType.getKey(identity.getType()).toString();
            String playerUuid = player.getUUID().toString();

            LOGGER.info("[SNAPSHOT] Recording morph: player={} type={}", playerUuid, entityTypeId);

            Flashback.RECORDER.submitCustomTask(writer -> {
                writer.startAction(ActionIdentity2Morph.INSTANCE);
                ActionIdentity2Morph.STREAM_CODEC.encode(
                    writer.friendlyByteBuf(),
                    new ActionIdentity2Morph.MorphData(playerUuid, entityTypeId, "")
                );
                writer.finishAction(ActionIdentity2Morph.INSTANCE);
            });

            lastKnownMorphs.put(playerUuid, entityTypeId);
        }
    }

    public static void writeMorphState(Player player) {
        if (!shouldWrite()) return;

        String entityTypeId = "";
        String variantNbt = "";

        Entity identity = ((EntityAccessor) player).getCurrentIdentity();
        if (identity != null) {
            entityTypeId = EntityType.getKey(identity.getType()).toString();
        }

        String finalEntityTypeId = entityTypeId;
        String finalVariantNbt = variantNbt;
        String playerUuid = player.getUUID().toString();

        LOGGER.info("[RECORD] Writing morph change: player={} type={}", playerUuid, finalEntityTypeId);

        Minecraft.getInstance().submit(() -> {
            if (!shouldWrite()) return;

            Flashback.RECORDER.submitCustomTask(writer -> {
                writer.startAction(ActionIdentity2Morph.INSTANCE);
                ActionIdentity2Morph.STREAM_CODEC.encode(
                    writer.friendlyByteBuf(),
                    new ActionIdentity2Morph.MorphData(playerUuid, finalEntityTypeId, finalVariantNbt)
                );
                writer.finishAction(ActionIdentity2Morph.INSTANCE);
            });
        });
    }

    private static final java.util.Map<String, String> lastKnownMorphs = new java.util.concurrent.ConcurrentHashMap<>();

    public static void tickMorphTracking() {
        if (!shouldWrite()) return;

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        for (Player player : level.players()) {
            String uuid = player.getUUID().toString();
            Entity identity = ((EntityAccessor) player).getCurrentIdentity();

            String currentMorph = "";
            if (identity != null) {
                currentMorph = EntityType.getKey(identity.getType()).toString();
            }

            String previous = lastKnownMorphs.get(uuid);
            if (previous == null || !previous.equals(currentMorph)) {
                lastKnownMorphs.put(uuid, currentMorph);
                writeMorphState(player);
            }
        }
    }

    public static void clearTracking() {
        lastKnownMorphs.clear();
    }

    private static boolean shouldWrite() {
        return Flashback.RECORDER != null && Flashback.RECORDER.readyToWrite();
    }
}
