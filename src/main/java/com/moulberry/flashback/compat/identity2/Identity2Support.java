package com.moulberry.flashback.compat.identity2;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.action.ActionRegistry;
import com.moulberry.flashback.playback.ReplayServer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * Entry point for Identity2 compatibility.
 *
 * Registers the custom morph action and a server tick handler that flushes
 * any pending morphs (deferred because the player entity wasn't spawned yet
 * when the morph action fired during snapshot processing).
 */
public class Identity2Support {

    private static boolean initialized = false;

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        // Register the action so Flashback can deserialize morph data from replay files
        ActionRegistry.register(ActionIdentity2Morph.INSTANCE);

        // Flush pending morphs each server tick during replay playback
        ServerTickEvents.END_SERVER_TICK.register((MinecraftServer server) -> {
            if (server instanceof ReplayServer replayServer) {
                ActionIdentity2Morph.flushPendingMorphs(replayServer);
            }
        });
    }

    public static boolean isIdentity2Loaded() {
        return FabricLoader.getInstance().isModLoaded("identity2");
    }
}
