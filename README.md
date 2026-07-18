# QuickLogin

**Floodgate (Bedrock) + premium Java auto-login for [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded), for Velocity networks.**

QuickLogin lets an **offline-mode Velocity network** accept everyone — cracked
Java players, real (paid) Java players, and Bedrock players via Geyser/Floodgate
— while auto-logging the trusted ones into AuthMe so they never type `/login`.

It ships as **two plugins** because on a Velocity network the proxy owns the
login phase, so premium verification *must* happen there — it cannot be done on
the backend (a backend that tries gets `Backend server is online-mode!` from
Velocity):

| Plugin | Where it goes | Job |
|--------|---------------|-----|
| **QuickLogin-Velocity** | the Velocity proxy | Verifies paid accounts by forcing online-mode login for premium names. Cracked/Bedrock fall through as offline. Invalid/expired sessions are kicked by Velocity with the standard "not authenticated with Minecraft.net" prompt. |
| **QuickLogin-Bukkit** | each Paper/Spigot backend | Auto-logs verified premium + Bedrock players into AuthMe, registering new accounts with a random password stored in SQLite. |

## How it works

1. A player connects to Velocity (which is in **offline mode**, so cracked and
   Bedrock players are allowed).
2. **QuickLogin-Velocity** sees the login-start, asks the **Mojang API directly**
   whether the name is a paid account, and if so calls `forceOnlineMode()`.
   Velocity then runs the real Mojang session check. A cracked client using a
   premium name, or a premium account with an expired session, fails that check
   and is kicked with the vanilla online-mode prompt — no confusion.
3. The player is forwarded to the backend. With **modern forwarding**, premium
   players arrive with their real Mojang UUID (version 4); cracked players get an
   offline UUID (version 3).
4. **QuickLogin-Bukkit** classifies each join — Bedrock (Floodgate), premium
   (v4 UUID), or cracked (v3 UUID) — and for premium/Bedrock players force-logs
   them into AuthMe, generating and storing a random password in SQLite for
   brand-new accounts. Cracked players go through AuthMe's normal flow.

Because the network sits behind a proxy, the Velocity plugin talks to Mojang
**directly** and never routes through a JVM proxy.

## Requirements

| Component | Required? | Notes |
|-----------|-----------|-------|
| Velocity 3.3+ or 4.x | ✅ | Proxy in **offline mode** (`online-mode = false` in `velocity.toml`). |
| Modern forwarding | ✅ | `player-info-forwarding-mode = "modern"` + matching secret on the backends (the standard Velocity + Paper setup). |
| Paper/Spigot 1.20.1 – 1.21.x backend | ✅ | Backend in offline mode (as Velocity requires). |
| AuthMeReloaded (backend) | ✅ | The login backend. |
| Floodgate | ⛄ optional | On the proxy for Bedrock connectivity; on the backend for Bedrock detection. |

SQLite is bundled in the backend jar — nothing else to install. Skins on an
offline network: pair with **SkinsRestorer** as usual.

## Installation

1. **Proxy:** drop `QuickLogin-Velocity-x.y.z.jar` into Velocity's `plugins/`.
   Ensure `velocity.toml` has `online-mode = false` and
   `player-info-forwarding-mode = "modern"`.
2. **Backends:** install **AuthMeReloaded** + `QuickLogin-Bukkit-x.y.z.jar` in
   each backend's `plugins/`. (Add Floodgate if you use Bedrock.)
3. Start the proxy, then the backends. Defaults are sane.

## Configuration

**Proxy** — `plugins/quicklogin/config.properties`:

```properties
premium-enabled=true          # verify paid accounts (force online-mode login)
allow-cracked=true            # allow non-premium names as offline players
bedrock-prefix=.              # names starting with this are treated as Bedrock and skipped
mojang-request-timeout-ms=5000
mojang-cache-seconds=300
debug=false
```

**Backend** — `plugins/QuickLogin/config.yml`:

```yaml
premium:
  enabled: true               # auto-login players with a Mojang-verified (v4) UUID
floodgate:
  enabled: true               # auto-login Bedrock players
auth:
  auto-register: true         # register new trusted players automatically
  generated-password-length: 32
database:
  file: "quicklogin.db"
debug: false
```

Reload the backend with `/quicklogin reload`.

## Commands & permissions (backend)

| Command | Description |
|---------|-------------|
| `/quicklogin reload` | Reload the configuration. |
| `/quicklogin status` | Show what's enabled and hooked. |
| `/quicklogin reset <player>` | Wipe stored credentials + AuthMe account so a fresh password is generated next join. |

Permission: `quicklogin.admin` (default: OP). Alias: `/ql`.

## Security notes

- Premium verification uses Velocity's real Mojang session check — a premium
  name only gets in if the client actually owns that account.
- Expired/invalid premium sessions are kicked by the proxy, never silently
  downgraded to cracked.
- Login usernames are validated (`[A-Za-z0-9_]{3,16}`) before any Mojang call.
- Generated passwords use `SecureRandom`; the SQLite file is owner-only on POSIX.
- No passwords, tokens, or keys are ever logged.

## Building

```bash
mvn -B package
```

Produces `velocity/target/QuickLogin-Velocity-<version>.jar` and
`bukkit/target/QuickLogin-Bukkit-<version>.jar`.

**Build requires JDK 25** (Velocity 4's API and annotation processor are
compiled for Java 25). The emitted bytecode targets Java 17, so the jars still
run on Java 17+ backends; the Velocity jar runs on the Java 25 that Velocity 4
itself requires.

### Automatic builds (GitHub Actions)

Every push/PR builds both jars and uploads them as workflow artifacts. Pushing a
`v*` tag attaches both jars to a GitHub Release.

## License

Provided as-is. Use, modify and distribute freely.
