# QuickLogin

**One backend plugin. Auto-registers and auto-logs premium Java players and
Bedrock (Floodgate) players into [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded)** so they never type `/register` or `/login`. Drag-and-drop.

- **Premium Java** &rarr; cryptographically verified against Mojang via
  [PacketEvents](https://github.com/retrooper/packetevents), auto-registered
  (random password stored in SQLite) on first join, auto-logged-in every time.
- **Bedrock (Floodgate)** &rarr; same, auto-registered/auto-logged-in.
- **Cracked / unverified** &rarr; left to AuthMe's normal `/register` + `/login`.
  Players with expired premium sessions are **not kicked** &mdash; they fall
  through to AuthMe normally.
- New accounts get a strong random password, stored in SQLite; players never see it.

## Install

1. Have **AuthMeReloaded** on the server (required).
2. Install **[PacketEvents](https://github.com/retrooper/packetevents)** for
   premium auto-login (recommended). Without it, only Bedrock auto-login works.
3. Add **Floodgate** + **Geyser** if you have Bedrock players.
4. Drop `QuickLogin-x.y.z.jar` into `plugins/`.
5. **Important**: set `settings.enablePremium: false` in AuthMe's `config.yml`
   so AuthMe and QuickLogin don't both try to run the Mojang handshake.
6. Start the server. Done &mdash; defaults are sane.

On startup QuickLogin logs what it hooked and whether premium/Bedrock auto-login
are active. Set `debug: true` to log how each player is classified on join.

## How premium verification works

QuickLogin runs the **Mojang encryption handshake** itself using PacketEvents,
exactly how an online-mode server verifies premium accounts:

1. A player connects &rarr; QuickLogin intercepts `LOGIN_START`
2. Mojang API lookup: is this username a paid account?
3. If yes: send `EncryptionRequest` (RSA challenge), handle
   `EncryptionResponse` (decrypt shared secret, install AES/CFB8 ciphers),
   verify via Mojang's `hasJoined` session endpoint
4. Verified &rarr; approved through AuthMe's pre-join dialog, auto-registered
   and auto-logged-in
5. If the session is expired/invalid, or the name isn't premium &rarr; falls
   through to AuthMe's normal `/register` + `/login` (no kick)

This works on **offline-mode servers** (standalone or behind a proxy) &mdash;
no proxy plugin needed, no `/premium` command, fully automatic.

## AuthMe 6 pre-join dialog support

AuthMe 6 shows a login dialog during the connection's configuration phase
(before the player is fully online). QuickLogin approves trusted players
(Bedrock and verified premium) through this dialog automatically so it never
appears for them, while normal players still get it.

Set `auth.skip-prejoin-dialog: true` (the default) to enable this.

## Configuration (`plugins/QuickLogin/config.yml`)

```yaml
premium:
  enabled: true               # Mojang handshake via PacketEvents

floodgate:
  enabled: true               # auto-login Bedrock players

auth:
  auto-register: true         # register new trusted players automatically
  skip-prejoin-dialog: true   # approve trusted players through AuthMe 6's dialog
  generated-password-length: 16
  login-delay-ticks: 5        # wait after join before logging in (raise if needed)

mojang:
  request-timeout-ms: 5000
  cache-seconds: 600

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
| Paper/Spigot 1.20.1 &ndash; 1.21.x | Yes |
| AuthMeReloaded | Yes |
| PacketEvents 2.x | Recommended (needed for premium auto-login) |
| Floodgate | Optional (needed for Bedrock auto-login) |

SQLite is bundled in the jar.

## Building

```bash
mvn -B package
```

Produces `target/QuickLogin-<version>.jar` (JDK 17+). Every push/PR builds it via
GitHub Actions and uploads the jar as an artifact; a `v*` tag attaches it to a
Release.

## License

Provided as-is. Use, modify and distribute freely.
