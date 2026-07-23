# `/viewec` — admin ender-chest viewer/editor (Skript)

A drop-in Skript that lets admins open **anyone's** ender chest — including
offline players — and freely add or remove items. On Purpur configured for
6-row ender chests, it opens the full 54-slot chest.

## Install

1. Install the addons on your **Purpur/Paper 1.20.5 – 1.21.x** server:
   - [Skript](https://github.com/SkriptLang/Skript)
   - [skript-reflect](https://github.com/SkriptLang/skript-reflect)
   - *(SkBee is **not** required.)*
2. Copy `viewec.sk` into `plugins/Skript/scripts/`.
3. Reload: `/sk reload viewec` (or restart the server).

## Grant the permission

The command is denied to everyone by default — grant the node to your admins.
With LuckPerms:

```
/lp group admin permission set viewenderchest true
# or per-player:
/lp user <name> permission set viewenderchest true
```

## Usage

```
/viewec <player>
```

- **Online target** → opens the player's live ender chest. Every change you
  make saves instantly (it *is* the real inventory).
- **Offline target** → loads the player's saved data via NMS, opens their
  ender chest, and writes it back to their `.dat` file **when you close the
  window**. The player must have joined the server at least once.

## Getting 6 rows on Purpur

The script opens whatever ender chest Purpur gives the player, so enable 6
rows in `purpur.yml` (path under `world-settings` → `default` →
`gameplay-mechanics` → `ender_chest`):

```yaml
ender_chest:
  six-rows: true
  use-permissions-for-rows: false
```

With `use-permissions-for-rows: true`, online rows come from
`purpur.enderchest.rows.<1-6>`. Offline chests are sized by the config
default in that mode, so `six-rows: true` is the reliable way to see all 6
rows for everyone.

## Notes / limits

- Two admins can't edit the *same* offline player at once (the second is told
  to wait) — this prevents a last-writer-wins overwrite.
- If an admin disconnects with an offline chest open, it's flushed and the
  lock released automatically.
- Offline access uses runtime **Mojang mappings** (Purpur/Paper 1.20.5+). On
  much older versions the NMS class/method names differ and the offline path
  would need adjusting; online editing is unaffected.
