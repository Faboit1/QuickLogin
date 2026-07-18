# QuickLogin

**Floodgate (Bedrock) + premium Java auto-login for [AuthMeReloaded](https://github.com/AuthMe/AuthMeReloaded).**
Drag-and-drop, SQLite-backed, and built to be exploit-free.

QuickLogin lets an **offline-mode** Paper/Spigot server accept everyone —
cracked Java players, real (paid) Java players, and Bedrock players via
Geyser/Floodgate — while still auto-logging the trusted ones into AuthMe so
they never have to type `/login`.

- **Premium Java players** are verified directly against Mojang (the full
  online-mode encryption handshake is performed by the plugin) and logged in
  automatically.
- **Bedrock players** (Floodgate) are logged in automatically.
- **First-time trusted players** are registered in AuthMe with a strong,
  randomly generated password that is stored in a local SQLite database. They
  never see or type it — later joins re-use it transparently.
- **A premium account with an invalid/expired session** is kicked with the
  *exact same message a cracked client sees on an online-mode server*
  (`Failed to verify username!` by default), so there is no confusion.
- **Cracked players** are untouched and go through AuthMe's normal
  `/register` + `/login` flow.

---

## How it works

On an offline-mode server nothing normally verifies who a Java player really
is, so anyone can log in under any name. QuickLogin closes that gap:

1. It intercepts the login-start packet (via **ProtocolLib**).
2. If the name is a standard Java name, it asks the **Mojang API directly**
   whether that name is a paid account.
3. If it is, QuickLogin performs the Mojang encryption handshake itself,
   verifies the session with `sessionserver.mojang.com/hasJoined`, enables
   channel encryption, and only then lets the (now trusted) login proceed.
4. On join, the player is force-logged-into AuthMe — registering them first
   with a generated password if they are new.

Because the server sits **behind a proxy**, QuickLogin talks to the Mojang API
**directly** and never sends the client IP to the `hasJoined` endpoint (the
address the backend sees is the proxy's, and sending it would make Mojang
reject valid sessions).

### Supported topology

QuickLogin runs the login handshake on the Paper server itself, so it is
designed for:

- a **standalone offline-mode** Paper/Spigot server, optionally behind a
  **transparent TCP / anti-DDoS proxy** (e.g. TCPShield) or a Geyser instance
  on a separate host.

If you run a full **BungeeCord/Velocity** network where the *Minecraft proxy*
performs the login phase, premium verification has to happen on the proxy
instead (that is a different kind of plugin). Bedrock + AuthMe auto-login still
works on the backend regardless.

---

## Requirements

| Dependency | Required? | Notes |
|------------|-----------|-------|
| Paper/Spigot 1.20.1 – 1.21.x | ✅ | Server should be in **offline mode** (`online-mode: false`). |
| [AuthMeReloaded](https://www.spigotmc.org/resources/authme-reloaded.6269/) | ✅ | The login backend. |
| [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/) | ✅ | Needed for the premium handshake. |
| [Floodgate](https://geysermc.org/download) | ⛄ optional | Only needed for Bedrock auto-login. |

SQLite is bundled inside the jar — nothing else to install.

> **Skins:** premium skins are not applied by QuickLogin. If you want premium
> skins on an offline server, pair it with **SkinsRestorer** (the usual combo).

---

## Installation (drag & drop)

1. Install **AuthMeReloaded** and **ProtocolLib** (and **Floodgate** if you use
   Bedrock).
2. Drop `QuickLogin-x.y.z.jar` into `plugins/`.
3. Make sure the server is in **offline mode** (`server.properties` →
   `online-mode=false`).
4. Start the server. That's it — defaults are sane.

---

## Configuration

`plugins/QuickLogin/config.yml`:

```yaml
premium:
  enabled: true                # premium Java auto-login
  allow-cracked: true          # let non-premium names log in as offline players
  kick-on-invalid-session: true
  invalid-session-message: "Failed to verify username!"

floodgate:
  enabled: true                # Bedrock auto-login
  auto-register: true

auth:
  auto-register: true          # register new trusted players automatically
  generated-password-length: 32

mojang:
  request-timeout-ms: 5000
  cache-seconds: 300
  worker-threads: 3

database:
  file: "quicklogin.db"

debug: false
```

Reload after edits with `/quicklogin reload`.

---

## Commands & permissions

| Command | Description |
|---------|-------------|
| `/quicklogin reload` | Reload the configuration. |
| `/quicklogin status` | Show what's enabled and hooked. |
| `/quicklogin reset <player>` | Wipe a player's stored credentials + AuthMe account so a fresh password is generated next join. |

Permission: `quicklogin.admin` (default: OP). Alias: `/ql`.

---

## Security notes

- Premium verification uses the real Mojang session check — a premium name can
  only get in if the connecting client actually owns that account.
- Expired/invalid premium sessions are **kicked** by default (configurable),
  never silently downgraded to cracked.
- Login usernames are validated (`[A-Za-z0-9_]{3,16}`) before any processing.
- Generated passwords use `SecureRandom`; the SQLite file is created
  owner-only on POSIX systems.
- The plugin never logs passwords, shared secrets, or private keys.

---

## Building

```bash
mvn -B package
```

The jar lands in `target/QuickLogin-<version>.jar`. Requires JDK 17+.

### Automatic builds (GitHub Actions)

Every push and pull request builds the plugin (`.github/workflows/build.yml`)
and uploads the jar as a workflow artifact. Pushing a tag like `v1.0.0`
additionally attaches the jar to a GitHub Release.

---

## License

Provided as-is. Use, modify and distribute freely.
