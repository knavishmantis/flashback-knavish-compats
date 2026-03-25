package com.moulberry.flashback.compat.identity2;

import com.moulberry.flashback.action.ActionRegistry;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Entry point for Identity2 compatibility.
 *
 * Call Identity2Support.initialize() during Flashback's mod initialization.
 * This registers the custom action with Flashback's ActionRegistry so that
 * replay files containing identity morph data can be properly deserialized.
 *
 * The action registration must happen regardless of whether Identity2 is
 * installed, so that replay files recorded WITH Identity2 can at least be
 * loaded (the morph actions will simply be no-ops if Identity2 isn't present
 * at playback time).
 */
public class Identity2Support {

    private static boolean initialized = false;

    public static void initialize() {
        if (initialized) return;
        initialized = true;

        // Register the action so Flashback can deserialize morph data from replay files
        ActionRegistry.register(ActionIdentity2Morph.INSTANCE);
    }

    /**
     * Check if Identity2 is loaded at runtime.
     * Used by mixins to conditionally enable recording/playback hooks.
     */
    public static boolean isIdentity2Loaded() {
        return FabricLoader.getInstance().isModLoaded("identity2");
    }
}
