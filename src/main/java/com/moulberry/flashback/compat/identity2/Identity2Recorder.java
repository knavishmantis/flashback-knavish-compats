package com.moulberry.flashback.compat.identity2;

import com.moulberry.flashback.Flashback;
import net.Gabou.identity2.util.EntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

/**
 * Handles recording Identity2 morph state into Flashback replay files.
 *
 * Two recording paths:
 * 1. Per-tick polling: checks all players for morph changes each tick
 * 2. Snapshot injection: writes full morph state for all players during periodic snapshots
 *
 * Identity2 stores morph state on entities via its EntityMixin (EntityAccessor interface).
 * The morph type is identified by entity type ID string + variant NBT.
 */
public class Identity2Recorder {

    /**
     * Called during periodic snapshots (via MixinIdentity2Snapshot hooking into
     * Recorder.writeCustomSnapshot()). Writes the current morph state for all
     * visible players to ensure snapshot consistency.
     */
    /**
     * Called from snapshot path — does NOT check readyToWrite() since the snapshot
     * path has its own gating. This is critical for capturing initial morph state
     * when the player was already morphed before recording started.
     */
    public static void writeSnapshotMorphStates() {
        if (Flashback.RECORDER == null) return;

        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        for (Player player : level.players()) {
            Entity identity = ((EntityAccessor) player).getCurrentIdentity();
            if (identity == null) continue;

            String entityTypeId = EntityType.getKey(identity.getType()).toString();
            String playerUuid = player.getUUID().toString();

            // Write morph state directly via the recorder's custom task system
            Flashback.RECORDER.submitCustomTask(writer -> {
                writer.startAction(ActionIdentity2Morph.INSTANCE);
                ActionIdentity2Morph.STREAM_CODEC.encode(
                    writer.friendlyByteBuf(),
                    new ActionIdentity2Morph.MorphData(playerUuid, entityTypeId, "")
                );
                writer.finishAction(ActionIdentity2Morph.INSTANCE);
            });

            // Update tracking so the per-tick poller doesn't re-write this
            lastKnownMorphs.put(playerUuid, entityTypeId);
        }
    }

    /**
     * Called when a morph change is detected for a player.
     * Writes the morph state as a custom Flashback action.
     */
    public static void writeMorphState(Player player) {
        if (!shouldWrite()) return;

        String entityTypeId = "";
        String variantNbt = "";

        Entity identity = ((EntityAccessor) player).getCurrentIdentity();
        if (identity != null) {
            entityTypeId = EntityType.getKey(identity.getType()).toString();
            // Variant NBT is left empty for now — entity type alone is sufficient
            // for most morphs. Variant-specific data (e.g. cat type, sheep color)
            // can be added later if needed.
        }

        String finalEntityTypeId = entityTypeId;
        String finalVariantNbt = variantNbt;
        String playerUuid = player.getUUID().toString();

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

    /**
     * Tracks previous morph states per player UUID to detect changes.
     * Called each tick to compare current vs last known state.
     */
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
