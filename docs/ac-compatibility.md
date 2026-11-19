# Module behaviour and limitations

This file describes what each module does to the game and where it is likely to misbehave. It
deliberately makes no claims about any particular server, anticheat or detection system.

An earlier version of this file graded each check against named anticheats and listed "bypass
notes". That content was removed: none of it was testable here — there was no game client, no
server and no anticheat to measure against — so the verdicts were guesses presented as results.

---

## What is actually verified

- The jar compiles, packages and resolves its mixins against Minecraft 26.3.
- Every mixin target and every API call was checked against the 26.3 jar by method descriptor.
- Nothing has been run in a game. No module has been played with, on a server or in singleplayer.

So the notes below are reasoning about the code, not observations. Treat them as "where to look
first", not as a description of behaviour you have seen.

---

## Combat

### KillAura

Attacks the nearest living entity within the configured reach, respecting the vanilla attack
cooldown, at a rate drawn from a Gaussian around the configured CPS range.

Limits: reach is a client-side check, so the client will swing at whatever it picks even if the
player has no line of sight; the aim setting turns the camera. A target held from the previous tick
is kept for 500 ms to avoid flicker, which can mean attacking something already out of range.

### CrystalAura

Alternates between breaking an End Crystal near the target and placing a new one on a base block
under it. Placement scores candidates by estimated damage to the target minus estimated self damage,
filtered by a configurable height window and by an Anti Suicide threshold.

Limits: the damage estimate is a simple distance falloff, not vanilla's explosion formula, so the
scores are approximate. Aim is delivered by rewriting the outbound movement packet, which is
unverified in game; with rotation disabled the camera moves instead. Placing is skipped while a
crystal the module placed is still unbroken for longer than the configured age.

### CriticalHit

Sends a movement packet reporting the player as airborne before an attack lands, then restores
ground state, on a jittered interval and optionally only while the attack key is held.

Limits: this sends movement packets that disagree with the client's real position, which is exactly
the kind of inconsistency that server-side movement checks are built to notice. Treat it as the
riskiest module in the client.

### AutoBed

Finds a bed near the target, aims at it, and sends start/stop destroy actions a tick apart, on a
jittered delay.

Limits: the two action packets go out back to back, which assumes the server treats instant-mine
the way vanilla does. Block reach is not re-checked against the target after aiming.

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

Both auras can be enabled at once, and neither pauses the other: `InputLock` is only taken by
inventory-driving modules such as `SmarterAutoTotem`. Each keeps its own cycle on the same tick —
`AnchorAura` runs the anchor sequence while `CrystalAura` places obsidian and crystals.

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

### Velocity

Cancels the server's knockback packet and re-applies it scaled per axis.

Limits: the re-applied motion ignores the collision context vanilla would have used; with a
multiplier far from 1.0 the player's movement can visibly disagree with the server's simulation.

## Movement

### AutoSprint

Sends the start-sprint command while the player is moving with enough food, rate limited to one
packet per 250 ms.

Limits: it does not loop sprint (vanilla keeps sprinting through turns); if the server refuses the
sprint, the client shows sprinting while the server does not.

### Scaffold

Places a block underneath the player when airborne and moving, switching to a hotbar block if
needed, and briefly forcing the sneak state around the placement call.

Limits: the sneak state is toggled on the client to bypass vanilla's own requirement, which the
server does not necessarily agree with. There is no placement-rate limit beyond one per tick.

### NoFall

Cancels outbound movement packets while falling faster than the threshold and re-sends an equivalent
packet reporting the player as grounded.

Limits: it rewrites the ground flag on the packets a server uses to reconstruct fall distance, so
the reported state contradicts the client's actual position over time.

### AntiVoid

Places a water bucket under the player when the client detects a long fall, then restores the
previous slot.

Limits: reacts to client-side fall state, so on a lagging connection it may fire late; the slot swap
and restore happen within a couple of ticks.

## Player

### SmarterAutoTotem

Puts a totem in the offhand when one is missing, on a reaction delay picked at random between the
configured min and max for every refill, minus a configurable share of the reported ping. In Smart
mode it only swaps when the offhand is empty or health (health plus absorption) is at or below the
threshold; in Strict mode it keeps the offhand filled. Totems are taken from the main inventory
before the hotbar unless that is switched off. Below the instant-health threshold the delay is
skipped.

With Open Inventory on it opens the player's own inventory, clicks the totem, and closes it again,
waiting a random pause between each step. For as long as that inventory is open it holds the client's
input lock: every other module stops receiving events, module keybinds do not fire, and mouse
movement, buttons and scroll are dropped, so the game's cursor cannot move and nothing can be
clicked, dragged or scrolled mid-swap. That lock expires by itself after five seconds in case the
module fails to release it. With Open Inventory off it sends a single offhand swap click and closes
immediately, because a server treats an inventory click as an opened menu and will not accept
movement until a close arrives.

Limits: the ping share is a tuning value, not a measurement — a shorter delay for a slow connection
assumes the click is simply late, which is not verified. Closing the inventory also re-asserts the
held keys because opening a screen releases them; if the module is disabled mid-refill it closes the
inventory it opened. Nothing here has been run in game.

## Utility

### FastPlace

Clears `Minecraft#rightClickDelay` every tick so the client-side use-item throttle does not gate
placement.

Limits: it only removes the client's own delay. Any placement rate the server enforces is unaffected,
so the visible result is placements the server rejects rather than faster building.

### AutoArmor

Quick-moves the strongest armor of each slot from the inventory into the armor slots, comparing the
armor attribute each item contributes to that slot.

Limits: it acts on the player's own inventory menu with shift-click semantics; the inventory-to-menu
slot translation is derived from vanilla's menu layout, and a different open container would not
work. Rate limited to one attempted swap per second.

### AutoEat

Switches to the most nutritious food in the hotbar when hunger is below the threshold, uses it, and
waits out the eat animation before restoring the previous slot.

Limits: the 32-tick wait is a fixed guess at the animation length; a food with a longer use duration
would be interrupted, and the slot restore does not check that the food actually finished.

## Client behaviour

- **Anti-fingerprint**: the brand string is reported as vanilla, mod channel registration payloads
  are dropped, and Mojang telemetry is disabled. These are client-side only and change what the
  client reports about itself, not what it does.
- **Silent rotation**: `CrystalAura`, `AutoBed`, `AutoSafeAnchor` and `AnchorAura` can report a
  rotation to the server by rewriting the outbound movement packet while the camera stays put.
  Position-only packets are promoted to position-and-rotation, per-tick deltas are capped, and the
  queued rotation is flushed as a rotation-only packet before the action instead of waiting for a
  tick.
- **Keybinds**: each module has a Bind row at the top of its settings drawer. Click it and press a key
  to bind, right-click (or press Backspace while listening) to clear. The bound key is shown on the
  module row. Binds are stored as the scancode the key event carries, so they follow the physical
  key, not the keyboard layout's character.
- **Persistence**: module on/off state, keybinds and every setting are written to
  `config/mist-client.json` and restored at startup. A value that no longer matches its setting type
  or range is skipped and the default is kept; the rest of the file still loads.
- **Pausing under input lock**: while a module holds `InputLock` (currently `SmarterAutoTotem`),
  events are not delivered to other modules, and their `onPause()` runs: `CrystalAura` abandons its
  cycle and puts the slot back, `KillAura` drops its target lock, `AutoBed` drops the bed. Timers are
  reset rather than resumed, because a freeze of unknown length leaves them stale.

---

## Reporting problems

Because nothing here has been played, in-game reports are the only real evidence for these modules.
If a module misbehaves, describe what the player and the server saw, not just that it "flagged".
