package com.moulberry.flashback.visuals;

import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Renders entity hitboxes for all entities, including during export.
 * Clean boxes only: no eye-level lines, no view vectors. Controlled by
 * ReplayVisuals.renderHitboxes, checked per-frame (no refresh needed).
 */
public class FlashbackHitboxesDebugRenderer implements DebugRenderer.SimpleDebugRenderer {
    private static final int COLOUR = 0xFFFFFFFF;
    private final Minecraft minecraft;

    public FlashbackHitboxesDebugRenderer(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public void emitGizmos(double d, double e, double f, DebugValueAccess debugValueAccess, Frustum frustum, float partialTick) {
        if (this.minecraft.level == null) {
            return;
        }
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState == null || !editorState.replayVisuals.renderHitboxes) {
            return;
        }
        for (Entity entity : this.minecraft.level.entitiesForRendering()) {
            Vec3 position = entity.position();
            Vec3 interpPosition = entity.getPosition(partialTick);
            Vec3 interpDelta = interpPosition.subtract(position);
            Gizmos.cuboid(entity.getBoundingBox().move(interpDelta), GizmoStyle.stroke(COLOUR));
        }
    }
}
