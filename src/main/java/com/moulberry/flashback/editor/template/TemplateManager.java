package com.moulberry.flashback.editor.template;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.FlashbackGson;
import com.moulberry.flashback.editor.CopiedKeyframes;
import com.moulberry.flashback.editor.SavedTrack;
import com.moulberry.flashback.editor.ui.KeyframeRelativeOffsets;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.editor.ui.windows.TimelineWindow;
import com.moulberry.flashback.keyframe.Keyframe;
import com.moulberry.flashback.keyframe.KeyframeRegistry;
import com.moulberry.flashback.keyframe.KeyframeType;
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
                    KeyframeTemplate template = parseTemplate(json);
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
     * Parse a template from JSON, manually constructing SavedTrack records
     * since Gson doesn't handle Java records + KeyframeType well.
     */
    private static KeyframeTemplate parseTemplate(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        String name = root.has("name") ? root.get("name").getAsString() : "";
        String description = root.has("description") ? root.get("description").getAsString() : "";
        boolean scaleToIO = !root.has("scaleToIO") || root.get("scaleToIO").getAsBoolean();

        CopiedKeyframes keyframes = new CopiedKeyframes();

        if (root.has("keyframes")) {
            JsonObject kfObj = root.getAsJsonObject("keyframes");

            if (kfObj.has("savedTracks")) {
                JsonArray tracksArray = kfObj.getAsJsonArray("savedTracks");
                for (JsonElement trackEl : tracksArray) {
                    JsonObject trackObj = trackEl.getAsJsonObject();

                    // Resolve KeyframeType from string ID
                    String typeId = trackObj.get("type").getAsString();
                    KeyframeType<?> keyframeType = KeyframeRegistry.getByID(typeId);
                    if (keyframeType == null) {
                        LOGGER.warn("Unknown keyframe type '{}' in template '{}'", typeId, name);
                        continue;
                    }

                    int track = trackObj.has("track") ? Math.max(0, trackObj.get("track").getAsInt()) : 0;
                    boolean copiedFromDisabled = trackObj.has("copiedFromDisabled") && trackObj.get("copiedFromDisabled").getAsBoolean();

                    // Parse keyframes map
                    TreeMap<Integer, Keyframe> keyframeMap = new TreeMap<>();
                    if (trackObj.has("keyframes")) {
                        JsonObject kfMapObj = trackObj.getAsJsonObject("keyframes");
                        for (Map.Entry<String, JsonElement> entry : kfMapObj.entrySet()) {
                            int tick = Integer.parseInt(entry.getKey());
                            Keyframe kf = FlashbackGson.COMPRESSED.fromJson(entry.getValue(), Keyframe.class);
                            if (kf != null) {
                                keyframeMap.put(tick, kf);
                            }
                        }
                    }

                    keyframes.savedTracks.add(new SavedTrack(keyframeType, track, copiedFromDisabled, keyframeMap));
                }
            }
        }

        KeyframeTemplate template = new KeyframeTemplate(name, description, scaleToIO, keyframes);
        return template;
    }

    public static void saveTemplate(KeyframeTemplate template) {
        Path dir = getTemplatesDir();
        try {
            Files.createDirectories(dir);

            // Build JSON manually to match expected format
            JsonObject root = new JsonObject();
            root.addProperty("name", template.name);
            root.addProperty("description", template.description);
            root.addProperty("scaleToIO", template.scaleToIO);

            JsonObject keyframesObj = new JsonObject();
            JsonArray tracksArray = new JsonArray();

            for (SavedTrack track : template.keyframes.savedTracks) {
                JsonObject trackObj = new JsonObject();
                trackObj.addProperty("type", track.type().id());
                trackObj.addProperty("track", track.track());
                trackObj.addProperty("copiedFromDisabled", track.copiedFromDisabled());

                JsonObject kfMap = new JsonObject();
                for (Map.Entry<Integer, Keyframe> entry : track.keyframes().entrySet()) {
                    JsonElement kfJson = FlashbackGson.COMPRESSED.toJsonTree(entry.getValue(), Keyframe.class);
                    kfMap.add(String.valueOf(entry.getKey()), kfJson);
                }
                trackObj.add("keyframes", kfMap);
                tracksArray.add(trackObj);
            }

            keyframesObj.add("savedTracks", tracksArray);
            root.add("keyframes", keyframesObj);

            String safeName = template.name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
            Path file = dir.resolve(safeName + ".json");
            Files.writeString(file, FlashbackGson.PRETTY.toJson(root));
            invalidateCache();
            LOGGER.info("Saved template '{}' to {}", template.name, file);
        } catch (IOException e) {
            LOGGER.error("Failed to save template '{}'", template.name, e);
        }
    }

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
            endTick = -1;
        }

        // Find the template's total duration
        int templateDuration = 0;
        for (SavedTrack track : source.savedTracks) {
            if (track.keyframes() != null && !track.keyframes().isEmpty()) {
                templateDuration = Math.max(templateDuration, track.keyframes().lastKey());
            }
        }
        if (templateDuration == 0) templateDuration = 1;

        int totalApplied = 0;

        for (SavedTrack savedTrack : source.savedTracks) {
            if (savedTrack.keyframes() == null || savedTrack.keyframes().isEmpty()) continue;

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

            SavedTrack scaledTrack = new SavedTrack(savedTrack.type(), savedTrack.track(), false, scaledKeyframes);
            totalApplied += scaledTrack.applyToScene(scene, startTick, totalTicks, new KeyframeRelativeOffsets());
        }

        if (totalApplied > 0) {
            editorState.markDirty();
            ReplayUI.setInfoOverlay("Applied Knavish Template: " + template.name + " (" + totalApplied + " keyframes)");
        }

        return totalApplied;
    }

    public static KeyframeTemplate createFromCopied(String name, String description, CopiedKeyframes copied, UUID entityToReplace) {
        CopiedKeyframes templateCopy = new CopiedKeyframes();
        templateCopy.relativePosition = copied.relativePosition;
        templateCopy.relativeYaw = copied.relativeYaw;
        templateCopy.relativePitch = copied.relativePitch;

        for (SavedTrack track : copied.savedTracks) {
            TreeMap<Integer, Keyframe> newKeyframes = new TreeMap<>();
            for (Map.Entry<Integer, Keyframe> entry : track.keyframes().entrySet()) {
                Keyframe kf = entry.getValue().copy();
                if (kf instanceof TrackEntityKeyframe trackKf) {
                    // If entityToReplace is null, replace ALL entity UUIDs with sentinel
                    // If specified, only replace that specific UUID
                    if (entityToReplace == null || entityToReplace.equals(trackKf.target)) {
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
