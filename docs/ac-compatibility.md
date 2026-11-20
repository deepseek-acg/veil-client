# Module behaviour and limitations

This file describes what each module does to the game and where it is likely to misbehave. It
deliberately makes no claims about any particular server, anticheat or detection system.

An earlier version of this file graded each check against named anticheats and listed "bypass
notes". That content was removed: none of it was testable here — there was no game client, no
server and no anticheat to measure against — so the verdicts were guesses presented as results.

Veil ships three modules — `CrystalAura`, `AutoSafeAnchor` and `AnchorAura` — and nothing else.

---

## What is actually verified

- The jar compiles, packages and resolves its mixins against Minecraft 26.3.
- Every mixin target and every API call was checked against the 26.3 jar by method descriptor.
- Nothing has been run in a game. No module has been played with, on a server or in singleplayer.

So the notes below are reasoning about the code, not observations. Treat them as "where to look
first", not as a description of behaviour you have seen.

---

## Combat

### CrystalAura

Alternates between breaking an End Crystal near the target and placing a new one on a base block
under it. Placement scores candidates by estimated damage to the target minus estimated self damage,
filtered by a configurable height window and by an Anti Suicide threshold.

Limits: the damage estimate is a simple distance falloff, not vanilla's explosion formula, so the
scores are approximate. Aim is delivered by rewriting the outbound movement packet, which is
unverified in game; with rotation disabled the camera moves instead. Placing is skipped while a
crystal the module placed is still unbroken for longer than the configured age.

### AutoSafeAnchor

Ported from nyx. One press runs one sequence on the block you are looking at: place a respawn anchor,
charge it with glowstone, place a glowstone shield between the anchor and your body, detonate, then
the module switches itself off. Every stage is a normal right-click on a block face; the delays are
configured in ticks with a random extra, and the detonate is verified after a delay and retried (and
re-charged if the server never registered the charge).

The shield spot is chosen from the blocks the blast would cross between the anchor and your body and
eyes, nearest the anchor first, so it is in the way rather than beside it. If the anchor is the only
support for that spot, it crouches for a tick, places against the anchor, then stands back up: a
crouched click on an anchor would place the held item instead of detonating it.

Limits: the whole sequence is timed in ticks, so with a slow server a stage can run before the
previous one has landed; the retries are what recover from that. If no shield spot exists at all it
detonates straight after charging, and if a spot existed but the glowstone never went down it cancels
by default (`Detonate If Shield Fails` overrides that). Releasing the crouch is key-state based: if
the module took a crouch from you, it gives the same key state back when the sequence ends.

### AnchorAura

`AutoSafeAnchor`'s sequence driven automatically, with the spot chosen rather than aimed at: for the
closest player within `Target Range` it scores every placeable spot in a box around them, estimates
the blast damage with `DamageUtil` (anchor power 5, anchor block treated as air) and keeps the best
`damage dealt − half the damage taken` that passes `Min Damage`, `Max Self Damage` and
`Anti Suicide`. After each detonation it re-arms after `Re-arm Delay` ticks.

A spot is rejected outright if any entity hitbox overlaps the anchor block — the opponent's first,
but also any other player, mob or crystal — so it never tries to place inside someone.

Limits: the estimate ignores the glowstone shield the sequence places before detonating, so it is
pessimistic about self damage and will skip a spot that the shield would actually have made safe.
The damage model itself is the crystal estimator with a different power, which uses a sampled
exposure fraction rather than vanilla's exact ray set. Targets are players only; the spot search is a
fixed box (place range clamped to 2..6 blocks) and does not path around obstacles, it just requires
the spot to be in reach with a clickable support.

### AnchorAura and CrystalAura together

Both auras can be enabled at once, and neither pauses the other. Each keeps its own cycle on the same
tick — `AnchorAura` runs the anchor sequence while `CrystalAura` places obsidian and crystals.

What they do share is the hotbar and the silent rotation:

- The hotbar is claimed through `HotbarSlots`. The player's own slot is captured by whichever module
  claims first and restored when the last claim is released, so a cycle ending on one side no longer
  puts a borrowed slot back into the other's sequence, and the player ends up holding their own item
  rather than a crystal or a stack of glowstone. A claim never outlives the player it was taken for:
  after a world change the captured slot is dropped and taken again, instead of restoring an index
  the new player never selected.
- Rotation needs no further coordination: `flushRotation` sends a rotation-only packet, and a client
  may send those any number of times per tick, so both modules can aim and click on the same tick.
  A position-carrying flush would have made the second one of the tick a disconnect.

Limits: the two are not aware of each other's blocks or explosions. A crystal detonated next to an
anchor destroys it, which ends that anchor sequence early (it re-arms and tries again), and both
estimate damage against the same opponent without counting the other's blast.

## Client behaviour

- **Anti-fingerprint**: the brand string is reported as vanilla, mod channel registration payloads
  are dropped, and Mojang telemetry is disabled. These are client-side only and change what the
  client reports about itself, not what it does.
- **Silent rotation**: `CrystalAura`, `AutoSafeAnchor` and `AnchorAura` can report a rotation to the
  server by rewriting the outbound movement packet while the camera stays put. Position-only packets
  are promoted to position-and-rotation, per-tick deltas are capped, and the queued rotation is
  flushed as a rotation-only packet before the action instead of waiting for a tick.
- **Keybinds**: each module has a Bind row at the top of its settings drawer. Click it and press a key
  to bind, right-click (or press Backspace while listening) to clear. The bound key is shown on the
  module row. Binds are stored as the scancode the key event carries, so they follow the physical
  key, not the keyboard layout's character.
- **Persistence**: module on/off state, keybinds and every setting are written to
  `config/veil-client.json` and restored at startup. A value that no longer matches its setting type
  or range is skipped and the default is kept; the rest of the file still loads. Entries for modules
  this build does not have are ignored on load and dropped on the next save.

---

## Reporting problems

Because nothing here has been played, in-game reports are the only real evidence for these modules.
If a module misbehaves, describe what the player and the server saw, not just that it "flagged".
