package com.moulberry.flashback.editor.template;

import com.moulberry.flashback.editor.CopiedKeyframes;
import com.moulberry.flashback.editor.SavedTrack;

import java.util.ArrayList;
import java.util.List;

/**
 * A named keyframe template that can be applied to entities.
 * Extends the clipboard CopiedKeyframes format with metadata.
 */
public class KeyframeTemplate {

    public String name = "";
    public String description = "";
    public boolean scaleToIO = true;
    public CopiedKeyframes keyframes = new CopiedKeyframes();

    public KeyframeTemplate() {}

    public KeyframeTemplate(String name, String description, boolean scaleToIO, CopiedKeyframes keyframes) {
        this.name = name;
        this.description = description;
        this.scaleToIO = scaleToIO;
        this.keyframes = keyframes;
    }
}
