# DisableLockedMapDupe (Skript)

Prevents players from cloning/duping **locked** map art while leaving normal
(non-locked) map cloning working like vanilla.

- **Cartography table** — putting a locked map + another map to clone it is blocked
  (the copy can't be pulled from the result slot).
- **Crafting table** — cloning a locked map (filled map + empty maps) is also blocked,
  since it's the same dupe vector.
- Non-locked maps are untouched and clone normally.

## How it works

A map's *locked* state is not stored on the item or its NBT — it lives on the
map data and is only exposed via Bukkit's `MapView#isLocked()`. So SkBee's NBT
tools can't detect it; the script uses **skript-reflect** to read it directly.

## Requirements

- [Skript](https://github.com/SkriptLang/Skript)
- [skript-reflect](https://github.com/SkriptLang/skript-reflect)
- Paper/Spigot 1.20.x – 1.21.x (tested)

## Install

1. Install Skript and skript-reflect into `plugins/`.
2. Copy `DisableLockedMapDupe.sk` into `plugins/Skript/scripts/`.
3. Run `/sk reload DisableLockedMapDupe` (or restart).

## Bypass

Give staff the permission `quicklogin.maps.dupebypass` to let them still copy
locked map art.
