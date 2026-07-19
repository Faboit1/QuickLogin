# QuickLogin

**One backend plugin. Auto-registers and auto-logs premium Java players and
Bedrock (Floodgate) players into [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded)** so they never type `/register` or `/login`. Drag-and-drop.

- **Premium Java** → auto-registered (random password stored in SQLite) on first
  join, auto-logged-in every time after.
- **Bedrock (Floodgate)** → same, auto-registered/auto-logged-in.
- **Cracked / unverified** → left to AuthMe's normal `/register` + `/login`.
- New accounts get a strong random password, stored in SQLite; players never see it.

## Install

1. Have **AuthMeReloaded** on the server (required). Add **Floodgate** if you use
   Bedrock.
2. Drop `QuickLogin-x.y.z.jar` into `plugins/`.
3. Start the server. Done — defaults are sane.

On startup QuickLogin logs what it hooked and whether premium/Bedrock auto-login
are active. Set `debug: true` to log how each player is classified on join.

## How premium detection works (important)

QuickLogin decides a Java player is **premium** when they arrive with a real,
Mojang-verified UUID (a version-4 UUID). **Something has to verify them first —
that is not, and cannot be, done by a backend plugin.** Depending on your setup:

- **Single server (no proxy), `online-mode=true`** → the server verifies premium
  accounts itself. Premium auto-login works. (But then cracked/Bedrock can't join
  — that's a vanilla limitation.)
- **Behind Velocity/BungeeCord with the proxy in `online-mode=true`** → the proxy
  verifies premium accounts and kicks expired/invalid sessions automatically (the
  exact "failed to verify" prompt), then forwards the real UUID. Premium
  auto-login works, **and Bedrock via Floodgate works too.** ← recommended
- **Proxy in `online-mode=false`** (to allow *cracked* Java players) → nothing
  verifies premium accounts, so no backend plugin can distinguish a real premium
  account from someone typing that name. In this mode only **Bedrock** auto-login
  works; premium auto-login is impossible without a proxy-side plugin.

If premium players aren't auto-logging in, enable `debug` — you'll see
`uuid=... (v3)` for them, which means your proxy is in offline mode.

> Requires **modern player-info forwarding** between the proxy and the backend
> (the standard Velocity + Paper setup) so the real UUID reaches the backend.

## Expired / invalid premium session → kick

This is handled automatically by online-mode verification (the proxy or a
standalone online-mode server): an expired session never passes the login phase,
so the player is kicked upstream with the standard prompt. The backend never sees
them, so there is nothing for this plugin to do here.

## Configuration (`plugins/QuickLogin/config.yml`)

```yaml
premium:
  enabled: true               # auto-login players with a Mojang-verified (v4) UUID
floodgate:
  enabled: true               # auto-login Bedrock players
auth:
  auto-register: true         # register new trusted players automatically
  generated-password-length: 32
  login-delay-ticks: 5        # wait after join before logging in (raise if needed)
database:
  file: "quicklogin.db"
debug: false
```

Reload with `/quicklogin reload`.

## Commands & permissions

| Command | Description |
|---------|-------------|
| `/quicklogin reload` | Reload the configuration. |
| `/quicklogin status` | Show what's enabled and hooked. |
| `/quicklogin reset <player>` | Wipe stored credentials + AuthMe account so a fresh password is generated next join. |

Permission: `quicklogin.admin` (default: OP). Alias: `/ql`.

## Requirements

| Component | Required? |
|-----------|-----------|
| Paper/Spigot 1.20.1 – 1.21.x | ✅ |
| AuthMeReloaded | ✅ |
| Floodgate | ⛄ optional (needed on the backend for Bedrock detection) |

SQLite is bundled in the jar. For premium skins on an offline network, pair with
**SkinsRestorer** as usual.

## Building

```bash
mvn -B package
```

Produces `target/QuickLogin-<version>.jar` (JDK 17+). Every push/PR builds it via
GitHub Actions and uploads the jar as an artifact; a `v*` tag attaches it to a
Release.

## License

Provided as-is. Use, modify and distribute freely.
