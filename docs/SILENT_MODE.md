# Silent Mode & Server Profiles

## What Silent Mode Does

Silent mode separates the rotation the **server sees** from the rotation the **camera shows**.

Without silent mode, KillAura and AimAssist correct your camera toward the target — a spectator or screen recorder sees the aim snap. With silent mode enabled, the camera stays exactly where you left it. The spoofed yaw/pitch is injected into the outgoing `ServerboundMovePlayerPacket` by `SilentRotationMixin` before it leaves the network layer. The server registers a valid aimed rotation; a replay shows no movement.

**KillAura → Silent** (`Silent: true`): rotation goes through SilentRotationManager instead of `setYRot`/`setXRot`. The camera is frozen.

**AimAssist → Silent** (`Silent: true`): `tickSilent()` routes the corrected angle through SilentRotationManager, measuring the error from the angle that module last put on the wire and flushing each step, so the pull accumulates toward the target instead of being re-measured every tick from a camera silent mode never moves. Every silent-aiming module keeps its own base angle, so two of them aiming at once do not walk each other's view. The existing GCD quantization, burst/pause rhythm, and micro-saccade layers all apply before the value reaches the wire.

**CrystalAura → Silent** (`Silent: true`, default): the aim is queued in `SilentRotationManager` and flushed before the click, so the server sees the crystal hit with the camera untouched. The turn is walked in over several ticks along a minimum-jerk trajectory, timed by Fitts's Law and carrying signal-dependent noise, so the server sees a movement that accelerates and settles instead of arriving in one snapped step. `Pacing` and `Saccade` shape the timing and the angle before it goes out; with `Silent` off the same trajectory turns the camera itself, a step per tick, so a visible aim still accelerates and settles rather than snapping, and the place or break waits for the turn to arrive so a click never goes out while the crosshair is still travelling.

**AutoSafeAnchor / AnchorAura → Silent** (`Silent: true`, default): place, charge, shield and detonate are aimed the same way. `AnchorAura` inherits `Rotate` and `Silent` from `AutoSafeAnchor`. With `Silent` off the camera turns along the same trajectory, and a click waits for it to arrive as it does in Silent. A config written before this rename still has `Visual Rot`; it is read as the inverse of `Silent`, so the old behaviour carries over and the next save drops the old key.

**Macro Mode** (`Macro Mode: false`, off by default — on CrystalAura, AutoSafeAnchor and AnchorAura): the module's bind key becomes a hold key, read from the keyboard every tick instead of from key events.

- **Hold** (about a fifth of a second or longer) to fight: the module arms itself and works for as long as the key is down. Letting go stops it working but leaves it armed, so the next hold carries straight on without arming it again.
- **Tap** the key to put the module away. A tap never lasts long enough to arm it, so putting a module away cannot place or break anything on the way out. A tap on an armed module switches it off, exactly as the GUI switch does; turning Macro Mode off also leaves it acting as a normal toggle again.
- During the first fifth of a second of a press the module is deliberately idle: that is what makes a tap distinct from a hold. It is not a delay you will notice between crystals, but do not read the first few ticks of a hold as the module missing a click.
- **CrystalAura** runs its cycles only while the key is held. A cycle in flight is dropped rather than frozen when the key comes up, and the next hold starts a fresh one. Under Macro Mode a cycle also has about a one-in-seven chance of waiting an extra one to four seconds before the next one, instead of starting on a metronome.
- **AutoSafeAnchor** places, charges, shields and detonates while the key is down; holding it past the end of one sequence runs the next from wherever you are looking, and letting go abandons a half-finished sequence rather than letting it land on a plan from before the release.
- **AnchorAura** cycles for as long as the key is held; letting go drops the running sequence, the re-arm and the target.
- Because the module is not switched off between holds, its idle ticks keep walking the server's silent view back to the camera and keep Silent, Swap Back and Session Fatigue state intact. Silent and Macro Mode are meant to be used together: hold the key, fight silently, let go, and the view comes back.

To put a macro-gated module away: **tap its bind key**, or switch it off with its GUI switch. Turning Macro Mode off is the other way out, and leaves the module behaving as a normal key-toggled module again.

With no key bound there is nothing to hold, so Macro Mode never lets a module act at all — with it on, bind a key. Modules without Macro Mode are toggled by their key as before.

**Session Fatigue** (always on while a module is enabled): configured delays in CrystalAura, AutoSafeAnchor and AnchorAura are multiplied by a factor that starts at 1.0, reaches 1.4 after twenty minutes of session and stays there. It is reset when the module is switched on. AnchorAura's re-arm gap is drawn from a Gaussian around `Re-arm Delay` with the server preset's jitter fraction as the spread, so consecutive re-arms are not evenly spaced.

**Conflict Guard** (`Conflict Guard: true` on KillAura): when AimAssist is also enabled, KillAura skips its own rotation and lets AimAssist own the aim. KillAura handles timing and attacks; AimAssist handles the angle. Use this combination for the most human-like aim profile.

---

## Per-Server Preset Guide

Set the `Server` dropdown on each combat module. `Auto` detects the connected server by address.

| Server | Preset | Reach (max) | Velocity H | CPS (max) | W-Tap | Crit Spoof |
|--------|--------|-------------|------------|-----------|-------|------------|
| Hypixel | `Hypixel` | 3.05 blocks | 0.92× | 16 | ✓ | ✓ |
| mcpvp.com / mcpvp.club | `Grim` | 2.94 blocks | 1.00× | 14 | ✗ | ✓ |
| minemen.net / MinemenClub | `Intave` | 2.90 blocks | 0.97× | 12 | ✗ | ✗ |
| Everything else | `Default` | 2.95 blocks | 0.92× | 14 | ✗ | ✓ |

**Velocity** on Grim and Intave: the module hard-enforces the safe floor. Setting Horizontal below the floor value is overridden at runtime; the suffix shows `[floored X%]` as a warning.

**CriticalHit** on Intave: the module skips the StatusOnly spoof entirely. Intave's movement simulation detects the packet timing pattern. Use sprint-reset crits manually instead.

---

## Panic Key Usage

`SSPanic` (category: Misc) is a one-way kill switch for screenshare situations.

1. Bind it to a key in the module list (e.g. END).
2. Before accepting the screenshare, press the key.
3. All modules disable, settings lists are cleared from heap, the game log is scrubbed of mist signatures, and a `[Perf] GC cycle complete` message appears in chat.

**After panic**, the client behaves as vanilla until the next restart. Settings are **not** saved after panic — the config file retains its pre-panic state.

`LogScrubber` (category: Misc) can run persistently during a session. When enabled it installs a Log4j2 filter that drops log records naming mist classes, so the log file never accumulates stack traces during normal play.

---

## Detection Risk Table

| Module | Hypixel | Grim | Intave | Notes |
|--------|---------|------|--------|-------|
| KillAura (Silent off) | Low | Medium | High | Camera moves, replay-visible |
| KillAura (Silent on) | Very Low | Low | Medium | Camera frozen, GCD-aligned |
| AimAssist (Silent on) | Very Low | Low | Low | Burst/pause masks pattern |
| Velocity (Hypixel preset) | Very Low | — | — | 92% horizontal inside window |
| Velocity (Grim/Intave preset) | — | Very Low | Very Low | 100%/97% forced by module |
| CriticalHit | Low | Low | Disabled | StatusOnly skipped on Intave |
| CrystalAura (Silent on) | Medium | High | High | Camera frozen; Pacing and Saccade spread the timing |

**General rule:** run at most two combat modules simultaneously. KillAura + AimAssist is the designed combination. Adding Velocity and CriticalHit on top raises the combined detection surface; on Intave, consider running only KillAura (Silent) with Velocity at 97%.
