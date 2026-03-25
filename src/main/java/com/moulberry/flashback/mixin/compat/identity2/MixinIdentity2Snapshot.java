package com.moulberry.flashback.mixin.compat.identity2;

import com.moulberry.flashback.compat.identity2.Identity2Recorder;
import com.moulberry.flashback.record.Recorder;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * Hooks into Flashback's Recorder.writeCustomSnapshot() to inject Identity2 morph
 * state into periodic snapshots.
 *
 * Flashback's writeCustomSnapshot() is explicitly documented as a mixin target:
 * "Mods can mixin here if they want to add custom actions or packets"
 *
 * This ensures that when a snapshot is taken (every 5 minutes, on dimension change,
 * on unpause), all players' current morph states are captured. This is critical for
 * seeking within replays - without snapshot data, seeking to a point before a morph
 * change was recorded would show the wrong form.
 */
@IfModLoaded("identity2")
@Mixin(Recorder.class)
public class MixinIdentity2Snapshot {

    @Inject(method = "writeCustomSnapshot", at = @At("RETURN"))
    private void flashback$writeIdentity2Snapshot(Consumer<Packet<? super ClientGamePacketListener>> consumer, CallbackInfo ci) {
        Identity2Recorder.writeSnapshotMorphStates();
    }
}
