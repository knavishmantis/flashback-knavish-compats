package com.moulberry.flashback.mixin.compat.identity2;

import com.moulberry.flashback.compat.identity2.Identity2Recorder;
import com.moulberry.flashback.record.Recorder;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks into Flashback's Recorder to poll for Identity2 morph state changes each tick.
 *
 * Identity2 communicates morph state via custom Architectury packets (identity2:set_custom_data_string,
 * identity2:identity_morph_request, etc.) which are NOT captured by Flashback's vanilla packet
 * recording. Instead, we poll the EntityAccessor.getCurrentIdentity() field each tick and write
 * a custom action when the morph state changes.
 *
 * This approach is more robust than trying to intercept Identity2's Architectury packets directly,
 * because:
 * 1. It works regardless of Identity2's internal packet format changes
 * 2. It captures the final resolved state, not intermediate packets
 * 3. It doesn't require hooking into Architectury's networking layer
 */
@IfModLoaded("identity2")
@Mixin(Recorder.class)
public class MixinIdentity2Recorder {

    /**
     * Hook into Recorder.endTick() which is called each tick during recording.
     * We piggyback on this to poll morph state changes.
     * When close=true, recording is ending so we clear tracking state.
     */
    @Inject(method = "endTick", at = @At("RETURN"))
    private void flashback$tickIdentity2MorphTracking(boolean close, CallbackInfo ci) {
        if (close) {
            Identity2Recorder.clearTracking();
        } else {
            Identity2Recorder.tickMorphTracking();
        }
    }
}
