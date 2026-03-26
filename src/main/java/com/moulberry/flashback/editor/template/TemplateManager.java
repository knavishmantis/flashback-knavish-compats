package com.moulberry.flashback.editor.template;

import com.moulberry.flashback.FlashbackGson;
import com.moulberry.flashback.editor.CopiedKeyframes;
import com.moulberry.flashback.editor.SavedTrack;
import com.moulberry.flashback.editor.ui.KeyframeRelativeOffsets;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.editor.ui.windows.TimelineWindow;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.impl.TrackEntityKeyframe;
import com.moulberry.flashback.state.EditorScene;
import com.moulberry.flashback.state.EditorState;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Manages loading, saving, and applying keyframe templates.
 * Templates are stored as JSON files in the flashback/templates/ config directory.
 */
public class TemplateManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("flashback-templates");
    private static final UUID TEMPLATE_TARGET_SENTINEL = new UUID(0, 0);

    private static List<KeyframeTemplate> cachedTemplates = null;
    private static long lastCacheTime = 0;
    private static final long CACHE_TTL_MS = 5000;

    public static Path getTemplatesDir() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        return configDir.resolve("flashback").resolve("templates");
    }

    /**
     * Get all available templates, with caching.
     */
    public static List<KeyframeTemplate> getTemplates() {
        long now = System.currentTimeMillis();
        if (cachedTemplates != null && (now - lastCacheTime) < CACHE_TTL_MS) {
            return cachedTemplates;
        }
        cachedTemplates = loadAllTemplates();
        lastCacheTime = now;
        return cachedTemplates;
    }

    public static void invalidateCache() {
        cachedTemplates = null;
    }

    private static List<KeyframeTemplate> loadAllTemplates() {
        Path dir = getTemplatesDir();
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }

        List<KeyframeTemplate> templates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            for (Path file : stream) {
                try {
                    String json = Files.readString(file);
                    KeyframeTemplate template = FlashbackGson.COMPRESSED.fromJson(json, KeyframeTemplate.class);
                    if (template != null && template.name != null && !template.name.isEmpty()) {
                        templates.add(template);
                    }
                } catch (Exception e) {
                    LOGGER.warn("Failed to load template: {}", file.getFileName(), e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read templates directory", e);
        }

        templates.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return templates;
    }

    /**
     * Save a template to disk.
     */
    public static void saveTemplate(KeyframeTemplate template) {
        Path dir = getTemplatesDir();
        try {
            Files.createDirectories(dir);
            String safeName = template.name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            Path file = dir.resolve(safeName + ".json");
            String json = FlashbackGson.PRETTY.toJson(template);
            Files.writeString(file, json);
            invalidateCache();
            LOGGER.info("Saved template '{}' to {}", template.name, file);
        } catch (IOException e) {
            LOGGER.error("Failed to save template '{}'", template.name, e);
        }
    }

    /**
     * Apply a template to the given editor scene, targeting a specific entity.
     *
     * @param template     The template to apply
     * @param targetUuid   The entity UUID to target (replaces TEMPLATE_TARGET placeholders)
     * @param editorState  The current editor state (for I/O markers and dirty marking)
     * @param scene        The editor scene to apply keyframes to
     * @param totalTicks   Total replay ticks
     * @return Number of keyframes applied
     */
    public static int applyTemplate(KeyframeTemplate template, UUID targetUuid, EditorState editorState, EditorScene scene, int totalTicks) {
        CopiedKeyframes source = template.keyframes;
        if (source == null || source.savedTracks.isEmpty()) {
            return 0;
        }

        // Determine placement range
        int startTick;
        int endTick;

        EditorState.StartAndEnd ioRange = editorState.getExportStartAndEnd();
        if (template.scaleToIO && ioRange.start() >= 0 && ioRange.end() > ioRange.start()) {
            startTick = ioRange.start();
            endTick = ioRange.end();
        } else {
            startTick = TimelineWindow.getCursorTick();
            endTick = -1; // No scaling
        }

        // Find the template's total duration (max tick across all tracks)
        int templateDuration = 0;
        for (SavedTrack track : source.savedTracks) {
            if (track.keyframes() != null && !track.keyframes().isEmpty()) {
                templateDuration = Math.max(templateDuration, track.keyframes().lastKey());
            }
        }
        if (templateDuration == 0) templateDuration = 1;

        // Build the substituted and scaled tracks
        int totalApplied = 0;

        for (SavedTrack savedTrack : source.savedTracks) {
            if (savedTrack.keyframes() == null || savedTrack.keyframes().isEmpty()) continue;

            // Scale and substitute keyframes
            TreeMap<Integer, Keyframe> scaledKeyframes = new TreeMap<>();
            for (Map.Entry<Integer, Keyframe> entry : savedTrack.keyframes().entrySet()) {
                int relativeTick = entry.getKey();
                Keyframe keyframe = entry.getValue().copy();

                // UUID substitution
                if (keyframe instanceof TrackEntityKeyframe trackKeyframe) {
                    if (TEMPLATE_TARGET_SENTINEL.equals(trackKeyframe.target)) {
                        trackKeyframe.target = targetUuid;
                    }
                }

                // Time scaling
                int placedTick;
                if (endTick > startTick && templateDuration > 0) {
                    double scale = (double)(endTick - startTick) / templateDuration;
                    placedTick = (int) Math.round(relativeTick * scale);
                } else {
                    placedTick = relativeTick;
                }

                scaledKeyframes.put(placedTick, keyframe);
            }

            // Apply using existing SavedTrack infrastructure
            SavedTrack scaledTrack = new SavedTrack(savedTrack.type(), savedTrack.track(), false, scaledKeyframes);
            totalApplied += scaledTrack.applyToScene(scene, startTick, totalTicks, new KeyframeRelativeOffsets());
        }

        if (totalApplied > 0) {
            editorState.markDirty();
            ReplayUI.setInfoOverlay("Applied template: " + template.name + " (" + totalApplied + " keyframes)");
        }

        return totalApplied;
    }

    /**
     * Create a template from selected keyframes, replacing the given entity UUID
     * with the TEMPLATE_TARGET sentinel so it can be reused on other entities.
     */
    public static KeyframeTemplate createFromCopied(String name, String description, CopiedKeyframes copied, UUID entityToReplace) {
        CopiedKeyframes templateCopy = new CopiedKeyframes();
        templateCopy.relativePosition = copied.relativePosition;
        templateCopy.relativeYaw = copied.relativeYaw;
        templateCopy.relativePitch = copied.relativePitch;

        for (SavedTrack track : copied.savedTracks) {
            TreeMap<Integer, Keyframe> newKeyframes = new TreeMap<>();
            for (Map.Entry<Integer, Keyframe> entry : track.keyframes().entrySet()) {
                Keyframe kf = entry.getValue().copy();

                // Replace specific entity UUID with sentinel
                if (kf instanceof TrackEntityKeyframe trackKf && entityToReplace != null) {
                    if (entityToReplace.equals(trackKf.target)) {
                        trackKf.target = TEMPLATE_TARGET_SENTINEL;
                    }
                }

                newKeyframes.put(entry.getKey(), kf);
            }
            templateCopy.savedTracks.add(new SavedTrack(track.type(), track.track(), false, newKeyframes));
        }

        return new KeyframeTemplate(name, description, true, templateCopy);
    }
}
