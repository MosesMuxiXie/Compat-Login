# Compat Login

[简体中文](README.md) | **English**

Compat Login is a server-side Fabric mod that lets one `online-mode=true` server accept all of the following at the same time:

- Mojang / Microsoft accounts;
- third-party Yggdrasil accounts such as LittleSkin;
- additional trusted Yggdrasil identity providers configured by the server owner.

The mod never receives, stores, or forwards player passwords. Players still sign in through their own launchers. The server only calls each provider's `hasJoined` endpoint to verify the current session.

## Version compatibility

Starting with Compat Login `0.3.0`, one universal JAR covers Minecraft `1.16` through `26.2`:

```text
compat_login-universal-0.3.1.jar
```

| Component | Supported range |
| --- | --- |
| Minecraft | stable releases from `1.16` through `26.2` |
| Fabric Loader | `0.18.4` or newer; tested with stable `0.19.3` |
| Fabric API | not required; it may remain installed when other mods need it |
| authlib-injector | optional; tested with `1.2.7` |
| Compat Login | `0.3.1` |

Minecraft `26.3` snapshots are outside the current support range. Snapshot classes and methods may change; support should only be extended after the corresponding stable release passes the startup matrix.

### Continuous integration startup tests

GitHub Actions starts real servers with Fabric Loader `0.19.3` for the following boundary versions instead of checking metadata alone:

| Minecraft | Java |
| --- | --- |
| `1.16.5` | 8 |
| `1.17.1` | 17 |
| `1.18.2` | 17 |
| `1.19.2`, `1.19.4` | 17 |
| `1.20.1`, `1.20.4` | 17 |
| `1.20.6` | 21 |
| `1.21.1`, `1.21.4`, `1.21.8`, `1.21.11` | 21 |
| `26.1.2`, `26.2` | 25 |

These versions cover the main Java and authlib API boundaries. Other stable releases inside the metadata range use the same JAR.

### Version branches

The repository keeps a `minecraft/<version>` branch for every stable release in the supported range, for example `minecraft/1.16`, `minecraft/1.20.6`, and `minecraft/26.2`. These branches are version entry points to the same tested universal source, not separate incompatible JAR implementations. Normal downloads and releases still come from `main` and GitHub Releases.

## 1. Create a Fabric server from scratch

### Step 1: Choose Minecraft and Java versions

Choose the server's Minecraft version first, then install the corresponding Java version:

| Minecraft | Recommended Java |
| --- | --- |
| `1.16.x` | Java 8 |
| `1.17.x` | Java 17 |
| `1.18.x` through `1.20.4` | Java 17 |
| `1.20.5` through `1.21.11` | Java 21 |
| `26.1` through `26.2` | Java 25 |

You can install a suitable build from [Eclipse Temurin](https://adoptium.net/temurin/releases/). Reopen the terminal after installation and check it:

```powershell
java -version
```

Do not try to launch Minecraft with a Java version lower than the one required by that Minecraft release.

### Step 2: Create the server directory

Windows example:

```powershell
New-Item -ItemType Directory -Path "D:\Minecraft\CompatLoginServer"
Set-Location "D:\Minecraft\CompatLoginServer"
```

Use a standalone local directory for production servers. Real-time synchronization and file locking can interfere with the server.

### Step 3: Download the Fabric server launcher

On the [official Fabric server download page](https://fabricmc.net/use/server/), select:

- the required stable Minecraft version;
- Fabric Loader `0.19.3` or a newer stable release;
- Fabric Installer `1.1.2` or a newer stable release.

You can also set the versions and download the launcher in PowerShell. This example uses Minecraft `1.21.11`:

```powershell
$MinecraftVersion = "1.21.11"
$LoaderVersion = "0.19.3"
$InstallerVersion = "1.1.2"
$Url = "https://meta.fabricmc.net/v2/versions/loader/$MinecraftVersion/$LoaderVersion/$InstallerVersion/server/jar"
curl.exe -L $Url -o fabric-server.jar
```

Change only `$MinecraftVersion` for another Minecraft release. Keeping the launcher name as `fabric-server.jar` avoids rewriting the startup script after every upgrade.

### Step 4: Create the first startup script

Create `start.bat` in the server directory:

```bat
@echo off
java -Xms2G -Xmx8G -jar fabric-server.jar nogui
pause
```

- `-Xms2G`: initial memory of 2 GiB;
- `-Xmx8G`: maximum memory of 8 GiB; adjust it for the server hardware;
- `nogui`: disables the vanilla graphical console.

Run `start.bat`. The first launch creates the initial files and stops because the EULA has not been accepted yet.

### Step 5: Accept the EULA

Read the [Minecraft EULA](https://www.minecraft.net/eula). If you accept it, open `eula.txt` and set:

```properties
eula=true
```

Run `start.bat` again. Wait for the server to create `server.properties`, `mods`, `config`, and the other directories, then enter:

```text
stop
```

Always stop the server cleanly before installing mods or changing authentication settings.

## 2. Install Compat Login

### Step 6: Get the JAR

Download the latest universal asset from [GitHub Releases](https://github.com/MosesMuxiXie/Compat-Login/releases):

```text
compat_login-universal-0.3.1.jar
```

This JAR works with every Minecraft version in the support table above. Historical `0.3.0` releases used version-specific asset names; starting with `0.3.1`, everyone downloads the same universal JAR.

To build from source:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

The output is located at:

```text
build\libs\compat_login-universal-0.3.1.jar
```

### Step 7: Put it in `mods`

```text
mods\
└─ compat_login-universal-0.3.1.jar
```

Compat Login does not require Fabric API. Keep the Fabric API JAR for the matching Minecraft version if another installed mod needs it.

Delete the old Compat Login JAR when upgrading. Never keep multiple Compat Login versions in `mods`. Install Compat Login only on the server; players do not need it on their clients.

## 3. Configure server authentication

### Step 8: Edit `server.properties`

The following setting is mandatory:

```properties
online-mode=true
```

With `online-mode=false`, logins bypass every Mojang and third-party session check. Compat Login prints a `[WARNING]` and refuses to start.

Minecraft 1.19.1 and newer also provide `enforce-secure-profile`.

#### Server without authlib-injector

```properties
enforce-secure-profile=false
```

This prevents vanilla secure-profile checks from rejecting third-party accounts that do not have trusted chat profile keys.

#### Server retaining authlib-injector 1.2.x

Following the [official authlib-injector server instructions](https://yushijinhun.github.io/authlib-injector/en/using-authlib-injector-on-a-minecraft-server.html), use:

```properties
enforce-secure-profile=true
```

Minecraft 1.16 through 1.19.0 do not have this server option, so do not add it manually there.

### Step 9: Choose the startup mode

#### Mode A: No server-side authlib-injector

```bat
@echo off
java -Xms2G -Xmx8G -jar fabric-server.jar nogui
pause
```

LittleSkin players still sign in through a client launcher that supports external authentication, but the server does not need a Java Agent.

#### Mode B: Keep server-side authlib-injector

Put `authlib-injector-1.2.7.jar` in the server directory:

```bat
@echo off
java -Xms2G -Xmx8G -javaagent:authlib-injector-1.2.7.jar=littleskin.cn -jar fabric-server.jar nogui
pause
```

Important details:

- `-javaagent:...` must appear before `-jar`;
- do not add `-Dauthlibinjector.ignoredPackages`;
- Compat Login automatically detects the Agent and queries the configured Mojang, LittleSkin, and other services directly;
- the Agent may continue handling third-party texture signatures, public keys, and its other compatibility features.

If an old configuration contains a random local proxy address generated by authlib-injector:

```text
http://127.0.0.1:<random-port>/https/sessionserver.mojang.com/session/minecraft/hasJoined
```

Compat Login backs up the original configuration as `config/compat_login.json.authlib-injector.bak`, migrates the Mojang entry back to the official HTTPS endpoint, and continues startup. Other local HTTP services configured by the server owner are not modified automatically.

## 4. Generate and edit the authentication configuration

### Step 10: Generate the default configuration

After installing the mod and configuring `server.properties`, start the server once. The mod creates:

```text
config\compat_login.json
```

Default contents:

```json
{
  "schemaVersion": 1,
  "authentication": {
    "connectTimeoutSeconds": 5,
    "requestTimeoutSeconds": 8,
    "maxResponseBytes": 1048576,
    "allowInsecureHttp": false,
    "services": [
      {
        "name": "Mojang",
        "enabled": true,
        "hasJoinedUrl": "https://sessionserver.mojang.com/session/minecraft/hasJoined"
      },
      {
        "name": "LittleSkin",
        "enabled": true,
        "hasJoinedUrl": "https://littleskin.cn/api/yggdrasil/sessionserver/session/minecraft/hasJoined"
      }
    ]
  }
}
```

The default configuration accepts both Mojang / Microsoft players and LittleSkin players.

### Step 11: Understand each option

| Option | Purpose | Valid range / notes |
| --- | --- | --- |
| `schemaVersion` | configuration format version | must currently be `1` |
| `connectTimeoutSeconds` | connection timeout | `1` through `30` seconds |
| `requestTimeoutSeconds` | response read timeout | `1` through `60` seconds |
| `maxResponseBytes` | maximum size of one authentication response | `1024` through `4194304` bytes |
| `allowInsecureHttp` | permits unencrypted HTTP | keep `false` for public identity providers |
| `services` | ordered identity-provider list | at least one service must have `enabled=true` |
| `services[].name` | provider name shown in logs | must not be empty or duplicated |
| `services[].enabled` | enables the provider | must be `true` or `false` |
| `services[].hasJoinedUrl` | Yggdrasil API root or complete verification endpoint | must be a trusted HTTP(S) URL |

Providers are queried in the order listed in `services`. The first provider that returns a valid profile authenticates the player.

### Step 12: Set authentication endpoints

`hasJoinedUrl` may be a complete `hasJoined` endpoint or an API root:

```json
{
  "name": "Mojang",
  "enabled": true,
  "hasJoinedUrl": "https://sessionserver.mojang.com"
}
```

```json
{
  "name": "LittleSkin",
  "enabled": true,
  "hasJoinedUrl": "https://littleskin.cn/api/yggdrasil"
}
```

The mod automatically appends the correct `/session/minecraft/hasJoined` path.

Do not configure:

- the LittleSkin sign-in or registration website;
- the `/authserver/authenticate` password login endpoint;
- player emails, passwords, or access tokens;
- an untrusted third-party server;
- a public unencrypted `http://` endpoint.

Player passwords must only be submitted to the player's chosen launcher and identity provider. Never put them in the server configuration.

### Step 13: Add another Yggdrasil provider

Add an entry to the `services` array:

```json
{
  "name": "MyYggdrasil",
  "enabled": true,
  "hasJoinedUrl": "https://account.example.com/api/yggdrasil"
}
```

Configure only trusted providers. An identity provider can assert player names, UUIDs, and profile properties; a malicious provider may impersonate players from another source.

### Step 14: Use HTTP only on a trusted private network

If the identity provider is available only through HTTP on the same trusted private network, set:

```json
"allowInsecureHttp": true
```

You may then configure an entry such as:

```json
{
  "name": "TrustedLanYggdrasil",
  "enabled": true,
  "hasJoinedUrl": "http://192.168.1.10:8080/api/yggdrasil"
}
```

Do not enable this option to work around public HTTPS certificate or connection problems.

## 5. Player connection instructions

### Mojang / Microsoft players

1. Sign in with the official Minecraft launcher or another launcher that supports Microsoft authentication.
2. Select the same Minecraft version as the server.
3. Connect normally.

The server verifies the session through Mojang's `hasJoined` endpoint.

### LittleSkin players

1. Add LittleSkin to a client launcher that supports authlib-injector or external authentication.
2. Set the provider to `https://littleskin.cn/api/yggdrasil`, or use the ALI address `littleskin.cn` as directed by the launcher.
3. Sign in to the LittleSkin account in the client launcher.
4. Select the same Minecraft version as the server.
5. Connect with the selected character.

Client-side authlib-injector and the optional server-side Java Agent are independent components.

## 6. Verify the installation

The startup log should contain the actual Minecraft and Loader versions, for example:

```text
Loading Minecraft 1.21.11 with Fabric Loader 0.19.3
compat_login 0.3.1
Compat Login initialized with 2 enabled authentication service(s)
```

When the server uses authlib-injector, the log should also include:

```text
[authlib-injector] [INFO] Version: 1.2.7
Detected server-side authlib-injector; compatibility mode is enabled
```

Test with one Mojang / Microsoft account and one third-party account. Successful authentication produces messages similar to:

```text
Authenticated PlayerName (uuid) via Mojang
Authenticated PlayerName (uuid) via LittleSkin
```

## 7. MCDReforged deployment

Compat Login is not an MCDR plugin. Put it in the Fabric server's `mods` directory. MCDR only starts the process, reads logs, and manages the server.

Example directory structure:

```text
D:\Minecraft\MCDRServer\
├─ start-mcdr.bat
├─ config.yml
├─ permission.yml
├─ config\
├─ logs\
├─ plugins\
└─ server\
   ├─ start.bat
   ├─ fabric-server.jar
   ├─ authlib-injector-1.2.7.jar          # optional
   ├─ server.properties
   ├─ config\compat_login.json
   └─ mods\
      └─ compat_login-universal-0.3.1.jar
```

At minimum, confirm the following entries in the root `config.yml`:

```yaml
working_directory: server
start_command: start.bat
handler: vanilla_handler
encoding: utf8
decoding: utf8
```

Without server-side authlib-injector, use this `server\start.bat`:

```bat
@echo off
java -Xms2G -Xmx8G -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar fabric-server.jar nogui
```

With server-side authlib-injector:

```bat
@echo off
java -Xms2G -Xmx8G -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -javaagent:authlib-injector-1.2.7.jar=littleskin.cn -jar fabric-server.jar nogui
```

Do not put `pause` at the end of a server script managed by MCDR. An optional root-level `start-mcdr.bat` may contain:

```bat
@echo off
cd /d "%~dp0"
mcdreforged
pause
```

## 8. Upgrade from an older version

1. Run `stop` in the server console.
2. Back up the complete server, or at least the world and `config` directory.
3. Delete every old `compat_login-*.jar` from `mods`.
4. Download `compat_login-universal-0.3.1.jar` from Releases and put it in `mods`.
5. Keep the existing `config/compat_login.json`.
6. Keep the existing `-javaagent` argument if the server already uses authlib-injector.
7. Upgrade Fabric Loader to `0.19.3` or a newer stable release.
8. Start the server and inspect the configuration migration log.
9. Test both Mojang / Microsoft and third-party accounts.

Version `0.2.0` checked `online-mode` too early and could reject a server that already had `online-mode=true`. Version `0.3.0` reads the actual `server.properties` file and no longer depends on a version-specific server lifecycle callback.

## 9. Troubleshooting

### `Compat Login requires online-mode=true`

Confirm that the actual `server.properties` in the server working directory contains:

```properties
online-mode=true
```

With MCDR, this file is inside the configured `working_directory`. Stop and restart the entire server; do not use `/reload`. Upgrade to `0.3.0` if version `0.2.0` still reports this error when the file already contains `true`.

### `Compat Login configuration is invalid`

Read every line following the exception:

```text
[WARNING] config/compat_login.json -> field path: exact problem
```

Fix every listed field and restart. The mod reports as many problems as possible in one pass and refuses to start with an unsafe authentication fallback.

### Fabric reports duplicate `compat_login`

The `mods` directory contains more than one Compat Login JAR. Delete the older files and keep exactly one `compat_login-universal-0.3.1.jar`.

### `http is disabled`

Use `https://` for public services. Only a trusted private-network service should use:

```json
"allowInsecureHttp": true
```

### `One or more configured authentication services were unavailable`

Check the following:

1. Can the server reach the identity-provider domain?
2. Is the URL a valid Yggdrasil API root?
3. Are DNS, firewall, proxy, and HTTPS certificates working?
4. Is the identity provider itself online?
5. Are `connectTimeoutSeconds` and `requestTimeoutSeconds` too short?

For security, if a provider request fails and no other provider successfully matches the login, the mod reports the authentication service as unavailable instead of silently allowing access.

### Mixin cannot match `hasJoinedServer`

Confirm that:

- Compat Login is `0.3.0` or newer;
- Minecraft is a stable release from `1.16` through `26.2`, not a `26.3` snapshot;
- Fabric Loader is at least `0.18.4`;
- only one Compat Login JAR is installed.

Include the complete `latest.log` and crash report when opening an issue.

## 10. Security notes

- Keep the server on `online-mode=true`.
- Configure only trusted identity providers.
- Public identity providers must use HTTPS.
- Manage permissions, bans, and allowlists by UUID where possible instead of relying only on player names.
- Different providers may contain conflicting names or UUIDs. Define a collision policy before opening a public server.
- The mod makes login sessions coexist; it cannot guarantee that every client trusts another provider's texture signatures.
- Back up the configuration and test every account type during a maintenance window after changing authentication settings.

## 11. Development and releases

Local build:

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

The build additionally verifies that every main class has a class-file major version no higher than `52`, which is Java 8 bytecode.

To run a development server with a local authlib-injector JAR:

```powershell
.\gradlew.bat runServer "-PcompatLoginTestAuthlibInjector=D:\path\to\authlib-injector-1.2.7.jar"
```

GitHub Actions includes:

- unit tests;
- Java 8 bytecode verification;
- real server startup tests for 14 Minecraft / Java combinations;
- automatic GitHub Release creation and JAR upload for `v*` tags.

## Implementation

Older Minecraft authlib versions return `GameProfile` from `hasJoinedServer`, while newer versions return `ProfileResult`. Compat Login uses:

- an internal profile model independent of a particular authlib release;
- Mixin-grouped adapters for the supported method signatures;
- runtime reflection to create either a legacy `GameProfile` or a modern `ProfileResult`;
- Java 8-compatible `HttpURLConnection`;
- a security check that reads `server.properties` directly.

This makes one Java 8 JAR work from Minecraft 1.16 through 26.2 while remaining runnable on newer Java versions.

## References

- [Official Fabric server launcher](https://fabricmc.net/use/server/)
- [Fabric Meta API](https://meta.fabricmc.net/)
- [Fabric Loader documentation](https://docs.fabricmc.net/develop/loader/)
- [Fabric 26.2 development documentation](https://docs.fabricmc.net/develop/)
- [MCDReforged configuration](https://docs.mcdreforged.com/en/latest/configuration.html)
- [Using authlib-injector on a Minecraft server](https://yushijinhun.github.io/authlib-injector/en/using-authlib-injector-on-a-minecraft-server.html)
- [authlib-injector Yggdrasil server technical specification](https://yushijinhun.github.io/authlib-injector/en/yggdrasil-server-technical-specification.html)
- [LittleSkin Yggdrasil documentation](https://manual.littlesk.in/yggdrasil/)
- [mc-multilogin-compat-mod](https://github.com/wifi-left/mc-multilogin-compat-mod)
- [MultiLogin](https://github.com/CaaMoe/MultiLogin)

## License

This project is licensed under the [Apache License 2.0](LICENSE).
