# CrystalAura — Crystal PvP Behavioral Spec

## What "useful in cpvp" means

Crystal PvP is a 1.9+ combat discipline where players fight on obsidian floors. The core loop:

1. Sword W-tap → enemy gets knocked slightly upward (vertical component of knockback)
2. During the 150–300ms window the enemy is elevated: place a crystal on the base block BELOW
   their floating position
3. Immediately break the crystal → explosion damage is maximized because the target's body is at
   crystal-center height
4. When the target is standing flat on the ground: crystal explosions hit from the side or at an
   angle — still damage, but less efficient than a direct vertical hit

---

## The height window (core feature)

An end crystal placed on a base block at `baseY` has its explosion center at approximately
`baseY + 2.0` (the crystal entity is 2 blocks tall, center at Y+1 above the face, plus the block).

A player's body center is at their feet Y + 0.9 (they are 1.8 blocks tall).

For maximum damage, body center should be close to explosion center:

```
playerFeetY + 0.9 ≈ baseY + 2.0
playerFeetY ≈ baseY + 1.1
```

The "same height" window is centered around this. Using feet position relative to block top
(`blockTop = baseY + 1`):

```
relativeY = playerFeetY - blockTop = playerFeetY - (baseY + 1)
Optimal:  relativeY ≈ +0.1   (feet just above block top)
Window:   relativeY ∈ [+0.3, +2.0]  (default — post-knockback only)
```

| relativeY range | meaning |
|---|---|
| below 0.0 | target at or below block level — suboptimal |
| 0.0 – 0.3 | target at floor level — crystal hits from the side |
| 0.3 – 2.0 | **target elevated — column placement effective** (default window) |
| above 2.0 | target too high — explosion won't reach well |

`heightMin` default = **0.3** so the module only crystals while the target is meaningfully elevated
after knockback. Lower `heightMin` to 0.0 or negative in settings to also crystal at floor level.

---

## Timing

| parameter | value | Gaussian call |
|---|---|---|
| Place delay | 85ms ± 25% | `RotationUtil.gaussianDelay(85L, 0.25f)` |
| Break delay | 75ms ± 25% | `RotationUtil.gaussianDelay(75L, 0.25f)` |

The delays are jittered rather than fixed so the pacing is not a constant interval. Nothing here
was measured against a server, so the numbers are tuning targets: they describe the intended feel of
the module, not an observed result.

---

## Rotation safety

- **Never** call `self.setYRot()` or `self.setXRot()` — moves the camera visibly and is unnatural
- Use silent rotation: write to static pending fields in CrystalAura, inject via packet mixin
- Inject point: `ServerboundMovePlayerPacket` (Rot and PosRot subtypes) in `Connection.send()`
- Maximum rotation per tick: **45° yaw, 30° pitch** — cap deltas before writing to pending fields
- If mixin unavailable, skip rotation entirely rather than snap camera

---

## Illegal action prevention

| condition | check | action |
|---|---|---|
| GUI / inventory open | `Mist.mc.gui.screen() != null` | return from onTick() |
| Self dead | `self.isDeadOrDying()` | return from onTick() |
| Target just died | `target.isDeadOrDying()` | skip target |

---

## Anti-spam / over-crystal prevention

Track `lastCrystalTick` — the game tick when the last crystal was placed.

If a placed crystal wasn't broken in `maxCrystalAge` ticks (default 10 = 500ms), skip placing
another. Prevents filling the arena with unbroken crystals, which is a visible tell and wastes the
player's crystals.

Reset `lastCrystalTick = -1` after breaking a crystal.

---

## Same-tick ordering

`aim()` queues a rotation and `flushRotation(self)` sends it as a rotation-only packet before the
action in the same tick. This replaces an earlier plan to hold the action back a tick: waiting for
vanilla to flush the movement packet first was an assumption about tick ordering, and sending the
packet ourselves removes it. No `lastRotateTick` bookkeeping is needed, and the action is not delayed
a tick, which matters when the target is falling.

It must be rotation-only. A client may send one position-carrying move packet per server tick;
`ServerGamePacketListenerImpl.handleMovePlayer` tracks `receivedPositionThisTick` and disconnects with
"Invalid move player packet received" on a second one. The client sends its own position packet every
tick it moves, so a `PosRot` here would consume the slot and the player would be kicked as soon as
they walked while a module was aiming.

| action | order |
|---|---|
| `aim()` sets the pending rotation | 1 |
| `flushRotation(self)` writes `ServerboundMovePlayerPacket.Rot` | 2 |
| attack or place | 3 |

---

## Behaviour summary

| case | behaviour |
|---|---|
| Constant-interval timing | Delays drawn from a Gaussian around the configured mean (±25%) |
| Camera movement while crystaling | Silent rotation: the rotation goes out in the movement packet |
| Large rotation jumps | Delta capped at 45° yaw and 30° pitch per tick |
| Acting while inventory open | `mc.gui.screen() != null` guard |
| Rotate and attack on the same tick | Rotation is queued, the action waits for the next tick |

---

## RotationUtil.gaussianDelay() (SA4 — already in RotationUtil.java)

```java
public static long gaussianDelay(long mean, float jitter) {
    double sigma = mean * jitter;
    double raw   = mean + ThreadLocalRandom.current().nextGaussian() * sigma;
    return (long) Math.max(mean / 2.0, Math.min(mean * 2.0, raw));
}
```
