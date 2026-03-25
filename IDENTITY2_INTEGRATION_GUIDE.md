# Integration Guide: Adding Identity2 Support to Flashback Fork

## Overview

This module adds 6 new files and requires 3 modifications to existing Flashback files.

## Step-by-Step

### 1. Add new source files

Copy the following into your Flashback fork:

```
src/main/java/com/moulberry/flashback/
  compat/identity2/
    ActionIdentity2Morph.java      -- Custom action for serializing morph state
    Identity2Recorder.java         -- Records morph changes during gameplay
    Identity2Playback.java         -- Restores morph state during replay
    Identity2Support.java          -- Init + mod detection
  mixin/compat/identity2/
    MixinIdentity2Recorder.java    -- Hooks recorder tick for morph polling
    MixinIdentity2Snapshot.java    -- Hooks writeCustomSnapshot() for morph snapshots
```

### 2. Modify `flashback.mixins.json`

Add these two entries to the `"mixins"` array (alongside the other `compat.*` entries):

```json
"compat.identity2.MixinIdentity2Recorder",
"compat.identity2.MixinIdentity2Snapshot"
```

### 3. Modify `build.gradle`

Add Identity2 as a compile-only dependency. Two options:

**Option A: From Modrinth** (once a version hash is available)
```gradle
modCompileOnly("maven.modrinth:identity-fix:<version-hash>")
```

**Option B: From local JAR**
```gradle
modCompileOnly(files("libs/identity2.jar"))
```

Download the Identity2 JAR from https://modrinth.com/mod/identity-fix and place it
in a `libs/` directory in the project root.

### 4. Modify `Flashback.java` (main mod class)

In the mod initializer, add the action registration. Find where other compat
modules are initialized and add:

```java
// Identity2 morph recording support
// Register unconditionally so replay files with morph data can always be loaded
Identity2Support.initialize();
```

This should be called early, alongside any other ActionRegistry.register() calls.

### 5. Verify mixin constraints

The mixins use `@IfModLoaded("identity2")` from moulberry's mixin-constraints library.
Verify that Identity2's mod ID is indeed `"identity2"` by checking its `fabric.mod.json`.
If the mod ID differs (e.g., `"identity-fix"` or `"identity"`), update the annotation
in both mixin files.

To check: look at Identity2's `fabric.mod.json` for the `"id"` field.

## How It Works

### Recording Flow

```
Game Tick
  -> Recorder.flushPackets()
    -> MixinIdentity2Recorder intercepts at RETURN
      -> Identity2Recorder.tickMorphTracking()
        -> For each player, check EntityAccessor.getCurrentIdentity()
        -> If morph changed since last tick, write ActionIdentity2Morph to replay file
```

### Snapshot Flow

```
Snapshot (every 5min / dimension change / unpause)
  -> Recorder.writeCustomSnapshot()
    -> MixinIdentity2Snapshot intercepts at RETURN
      -> Identity2Recorder.writeSnapshotMorphStates()
        -> For each player, write current morph state as ActionIdentity2Morph
```

### Playback Flow

```
Replay Playback
  -> ReplayServer reads action from replay file
    -> ActionRegistry resolves "flashback:action/identity2_morph"
      -> ActionIdentity2Morph.handle() decodes MorphData
        -> Identity2Playback.applyMorph() sets identity on FakePlayer
          -> Identity2's PlayerEntityRendererMixin sees identity -> renders mob model
```

## Testing Checklist

- [ ] Record gameplay with Identity2 installed, morph into several mobs
- [ ] Verify replay file size increased (morph data is being written)
- [ ] Play back replay with Identity2 installed, verify morphed players render correctly
- [ ] Test seeking in replay - morphs should be correct after seeking due to snapshot data
- [ ] Test with Identity2 NOT installed during playback - should show normal players without errors
- [ ] Test recording without Identity2 installed - should record normally with no errors

## Architecture Notes

### Why polling instead of packet interception?

Identity2 uses Architectury networking (not raw Fabric networking), making packet
interception harder. The polling approach:

1. Checks `EntityAccessor.getCurrentIdentity()` each tick (the field Identity2's
   EntityMixin injects into all entities)
2. Compares against last known state per player
3. Writes a custom action only when state changes

This is simpler, more robust against Identity2 internal changes, and captures the
final resolved state rather than intermediate packets.

### Why not record Identity2's custom packets directly?

Identity2 uses multiple packet types for morph state:
- `identity2:set_custom_data_string` (S2C, entity data sync)
- `identity2:set_custom_data_double` (S2C, entity data sync)
- `identity2:set_custom_data_bool` (S2C, entity data sync)
- `identity2:identity_morph_request` (C2S, morph request)
- `identity2:morph_acquisition` (S2C, acquisition animation)

Capturing and replaying all of these would require:
- Hooking into Architectury's NetworkManager
- Replaying packets through Architectury's deserialization
- Handling packet format changes across Identity2 versions

The polling approach avoids all of this complexity.
