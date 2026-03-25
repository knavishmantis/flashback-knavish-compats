package com.moulberry.flashback.compat.identity2;

import net.Gabou.identity2.util.EntityAccessor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

/**
 * Handles restoring Identity2 morph state during replay playback.
 *
 * When Flashback replays a recording, FakePlayer entities are created for each
 * recorded player. This class applies the recorded morph state to those FakePlayer
 * entities using Identity2's EntityAccessor interface (injected via EntityMixin).
 *
 * Identity2's client-side PlayerEntityRendererMixin will then automatically
 * render the morphed entity model instead of the player model, as long as
 * Identity2 is installed on the client viewing the replay.
 */
public class Identity2Playback {

    /**
     * Apply a morph state to a player entity during replay.
     *
     * @param player      The FakePlayer (or any ServerPlayer) in the replay world
     * @param entityTypeId The entity type identifier (e.g. "minecraft:creeper"), or "" to clear
     * @param variantNbt   The variant NBT data as a string, or "" for default variant
     */
    public static void applyMorph(ServerPlayer player, String entityTypeId, String variantNbt) {
        EntityAccessor accessor = (EntityAccessor) player;

        if (entityTypeId == null || entityTypeId.isEmpty()) {
            // Clear the morph - player returns to normal form
            accessor.setCurrentIdentity((Entity) null);
            return;
        }

        try {
            // Use Identity2's setCurrentIdentity(String id) which handles
            // entity creation, dimension sync, trait application, etc.
            accessor.setCurrentIdentity(entityTypeId);
        } catch (Exception e) {
            // Fallback: create the entity directly and set it
            try {
                applyMorphDirect(player, entityTypeId);
            } catch (Exception ex) {
                // Silently fail - the player will just appear unmorphed
            }
        }
    }

    /**
     * Direct fallback: creates the identity entity manually and sets it on the player.
     * Used if Identity2's higher-level API isn't available or fails.
     */
    private static void applyMorphDirect(ServerPlayer player, String entityTypeId) {
        Identifier typeId = Identifier.parse(entityTypeId);
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(typeId);
        if (entityType == null) return;

        Entity entity = entityType.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (entity == null) return;

        // Position the identity entity at the player's location
        entity.setPos(player.position());

        EntityAccessor accessor = (EntityAccessor) player;
        accessor.setCurrentIdentity(entity);
    }
}
