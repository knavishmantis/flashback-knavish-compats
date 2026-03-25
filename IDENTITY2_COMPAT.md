# Flashback Identity2 Compat Module

Compatibility module for [Flashback](https://github.com/Moulberry/Flashback) to properly record and replay [Identity2](https://github.com/xGabou/Identity2) morph states.

## Problem

Flashback records vanilla Minecraft network packets. Identity2 communicates morph state via custom mod packets (`identity2:set_custom_data_string`, etc.) using Architectury networking. Without this compat module, replays show all players in their normal (unmorphed) form.

## Solution

This module:
1. **Records** identity morph state changes as custom Flashback actions during gameplay
2. **Injects** identity state into periodic snapshots via `Recorder.writeCustomSnapshot()`
3. **Replays** the recorded morph state during playback, restoring it on `FakePlayer` entities
4. Identity2's existing `PlayerEntityRendererMixin` then handles the visual rendering automatically

## Requirements

- Flashback (fork with this module applied)
- Identity2 must be installed both when recording AND when viewing replays
- Minecraft 1.21.11, Fabric

## Installation

These files need to be added to a Flashback fork:

### Source Files

Copy into `src/main/java/com/moulberry/flashback/`:

- `compat/identity2/Identity2Recorder.java` - Captures morph state changes during recording
- `compat/identity2/Identity2Playback.java` - Restores morph state during replay playback
- `compat/identity2/ActionIdentity2Morph.java` - Custom Flashback action for morph state
- `compat/identity2/Identity2Support.java` - Initialization and action registration

### Mixin Files

Copy into `src/main/java/com/moulberry/flashback/mixin/compat/identity2/`:

- `MixinIdentity2Recorder.java` - Hooks into Flashback's recorder to capture morph packets
- `MixinIdentity2Snapshot.java` - Injects morph state into periodic snapshots

### Configuration Changes

1. **`flashback.mixins.json`** - Add to the `"mixins"` array:
   ```json
   "compat.identity2.MixinIdentity2Recorder",
   "compat.identity2.MixinIdentity2Snapshot"
   ```

2. **`build.gradle`** - Add compile-only dependency:
   ```gradle
   modCompileOnly("maven.modrinth:identity-fix:<version-hash>")
   ```
   Or add Identity2 as a local dependency:
   ```gradle
   modCompileOnly(files("libs/identity2.jar"))
   ```

3. **`fabric.mod.json`** - Add optional dependency:
   ```json
   "suggests": {
     "identity2": "*"
   }
   ```
