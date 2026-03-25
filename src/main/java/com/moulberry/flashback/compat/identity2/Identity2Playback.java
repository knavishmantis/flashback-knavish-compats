package com.moulberry.flashback.compat.identity2;

import net.Gabou.identity2.util.EntityAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Handles restoring Identity2 morph state during replay playback.
 *
 * Identity2's PlayerEntityRendererMixin checks getCurrentIdentity() on the
 * CLIENT-SIDE player entity (RemotePlayer), not the server-side FakePlayer.
 * So we must apply the morph on the client-side entity for it to render.
 */
public class Identity2Playback {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback-identity2");

    /**
     * Apply morph on BOTH server and client side entities.
     * Server side: FakePlayer (for any server-side checks)
     * Client side: RemotePlayer (for rendering via Identity2's mixin)
     */
    public static void applyMorph(ServerPlayer serverPlayer, String entityTypeId, String variantNbt) {
        LOGGER.info("[APPLY] Applying morph {} to player {} (server-side class={})",
            entityTypeId, serverPlayer.getUUID(), serverPlayer.getClass().getSimpleName());

        // Apply on server-side player
        applyToEntity(serverPlayer, entityTypeId);

        // Apply on client-side player entity
        UUID uuid = serverPlayer.getUUID();
        Minecraft.getInstance().execute(() -> {
            ClientLevel clientLevel = Minecraft.getInstance().level;
            if (clientLevel == null) return;

            for (Player clientPlayer : clientLevel.players()) {
                if (clientPlayer.getUUID().equals(uuid)) {
                    LOGGER.info("[APPLY-CLIENT] Found client player {}, applying morph {}",
                        uuid, entityTypeId);
                    applyToEntity(clientPlayer, entityTypeId);
                    Entity result = ((EntityAccessor) clientPlayer).getCurrentIdentity();
                    LOGGER.info("[APPLY-CLIENT] Result identity: {}",
                        result != null ? EntityType.getKey(result.getType()) : "NULL");
                    return;
                }
            }
            LOGGER.info("[APPLY-CLIENT] Client player {} not found yet, will retry via tick flush", uuid);
        });
    }

    private static void applyToEntity(Entity entity, String entityTypeId) {
        EntityAccessor accessor = (EntityAccessor) entity;

        if (entityTypeId == null || entityTypeId.isEmpty()) {
            accessor.setCurrentIdentity((Entity) null);
            return;
        }

        try {
            accessor.setCurrentIdentity(entityTypeId);
        } catch (Exception e) {
            LOGGER.error("[APPLY] setCurrentIdentity('{}') failed on {}", entityTypeId, entity.getClass().getSimpleName(), e);
            try {
                applyDirectToEntity(entity, entityTypeId);
            } catch (Exception ex) {
                LOGGER.error("[APPLY] Direct fallback also failed", ex);
            }
        }
    }

    private static void applyDirectToEntity(Entity target, String entityTypeId) {
        Identifier typeId = Identifier.parse(entityTypeId);
        EntityType<?> entityType = BuiltInRegistries.ENTITY_TYPE.getValue(typeId);
        if (entityType == null) return;

        Entity identity = entityType.create(target.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (identity == null) return;

        identity.setPos(target.position());
        ((EntityAccessor) target).setCurrentIdentity(identity);
    }

    /**
     * Called from client tick to apply any pending morphs to client-side player entities.
     * This catches cases where the client entity didn't exist when applyMorph was first called.
     */
    public static void applyPendingToClientPlayers(java.util.Map<UUID, ActionIdentity2Morph.MorphData> pendingClientMorphs) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || pendingClientMorphs.isEmpty()) return;

        var iterator = pendingClientMorphs.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            UUID uuid = entry.getKey();
            String entityTypeId = entry.getValue().entityTypeId();

            for (Player player : level.players()) {
                if (player.getUUID().equals(uuid)) {
                    LOGGER.info("[CLIENT-FLUSH] Applying deferred client morph {} to {}", entityTypeId, uuid);
                    applyToEntity(player, entityTypeId);
                    iterator.remove();
                    break;
                }
            }
        }
    }
}
