# Compat Login

**简体中文** | [English](README_EN.md)

Compat Login 是一个只安装在服务端的 Fabric 模组。它让同一个 `online-mode=true` 服务器同时接受：

- Mojang / Microsoft 正版账号；
- LittleSkin 等第三方 Yggdrasil 账号；
- 服主额外配置的其他可信 Yggdrasil 身份源。

模组不会接收、保存或转发玩家密码。玩家仍在自己的启动器中登录；服务端只向各身份源的 `hasJoined` 接口验证本次会话。

## 版本兼容性

Compat Login `1.1.0` 按 Minecraft 的映射方式分成两个发布 JAR，每个 JAR 以自己支持的最高正式版命名：

```text
compat_login-1.21.11-1.1.0.jar   # Minecraft 1.16 至 1.21.11
compat_login-26.2-1.1.0.jar      # Minecraft 26.1 至 26.2
```

Minecraft `26.1` 起官方发布不再混淆，Fabric Loader 也不再加载运行时映射，所以这条线必须单独构建。两个 JAR 使用完全相同的一套核心源码，只有映射方式和字节码级别不同。

| 组件 | 支持范围 |
| --- | --- |
| Minecraft | `1.16` 至 `1.21.11`（旧映射线）、`26.1` 至 `26.2`（新版线） |
| Fabric Loader | 最低 `0.18.4`，使用最新稳定版 `0.19.3` 验证 |
| Fabric API | 不需要；可与整合包中已有的 Fabric API 共存 |
| authlib-injector | 可选；已验证 `1.2.7` |
| Compat Login | `1.1.0` |

服务器只安装与自身 Minecraft 版本对应的那一个 JAR，不要同时放入两个。

Minecraft `26.3` 快照不在当前支持范围内。快照的类和方法会继续变化，应在对应正式版发布并通过启动测试后再扩大范围。

### 持续集成启动测试

GitHub Actions 会使用 Fabric Loader `0.19.3` 实际启动以下关键版本，而不是只检查元数据：

| Minecraft | Java | 使用的 JAR |
| --- | --- | --- |
| `1.16.5` | 8 | `compat_login-1.21.11` |
| `1.17.1` | 17 | `compat_login-1.21.11` |
| `1.18.2` | 17 | `compat_login-1.21.11` |
| `1.19.2`、`1.19.4` | 17 | `compat_login-1.21.11` |
| `1.20.1`、`1.20.4` | 17 | `compat_login-1.21.11` |
| `1.20.6` | 21 | `compat_login-1.21.11` |
| `1.21.1`、`1.21.4`、`1.21.8`、`1.21.11` | 21 | `compat_login-1.21.11` |
| `26.1`、`26.1.2`、`26.2` | 25 | `compat_login-26.2` |

这些版本覆盖 Java 与 authlib 接口变更的主要边界。同一条线内其他位于元数据范围中的正式版使用该线的同一个 JAR。

### 版本发布

仓库以单一 `main` 分支维护，每个版本的发布物（两个 JAR）通过 GitHub Releases 发布；按版本打 `v*` 标签（历史版本标签为 `mc-*` 或 `v*` 格式）。

## 一、从零创建 Fabric 服务器

### 第 1 步：选择 Minecraft 版本和 Java

先确定服务器的 Minecraft 版本，再安装对应 Java：

| Minecraft | 建议 Java |
| --- | --- |
| `1.16.x` | Java 8 |
| `1.17.x` | Java 17 |
| `1.18.x` 至 `1.20.4` | Java 17 |
| `1.20.5` 至 `1.21.11` | Java 21 |
| `26.1` 至 `26.2` | Java 25 |

可从 [Eclipse Temurin](https://adoptium.net/temurin/releases/) 安装对应版本。安装后重新打开终端并检查：

```powershell
java -version
```

不要用低于 Minecraft 本身要求的 Java 版本强行启动。

### 第 2 步：创建服务器目录

Windows 示例：

```powershell
New-Item -ItemType Directory -Path "D:\Minecraft\CompatLoginServer"
Set-Location "D:\Minecraft\CompatLoginServer"
```

生产服务器建议使用本地独立目录，避免实时同步或文件锁定影响运行。

### 第 3 步：下载 Fabric 服务端

在 [Fabric 官方服务端下载页](https://fabricmc.net/use/server/) 选择：

- 需要的 Minecraft 正式版；
- Fabric Loader `0.19.3` 或当前更新的稳定版；
- Fabric Installer `1.1.2` 或当前更新的稳定版。

也可以在 PowerShell 中设置版本变量并下载。以 Minecraft `1.21.11` 为例：

```powershell
$MinecraftVersion = "1.21.11"
$LoaderVersion = "0.19.3"
$InstallerVersion = "1.1.2"
$Url = "https://meta.fabricmc.net/v2/versions/loader/$MinecraftVersion/$LoaderVersion/$InstallerVersion/server/jar"
curl.exe -L $Url -o fabric-server.jar
```

需要其他 Minecraft 版本时，只修改 `$MinecraftVersion`。统一保存为 `fabric-server.jar` 可避免每次升级后重写启动脚本。

### 第 4 步：创建首次启动脚本

在服务器目录创建 `start.bat`：

```bat
@echo off
java -Xms2G -Xmx8G -jar fabric-server.jar nogui
pause
```

- `-Xms2G`：初始内存 2 GiB；
- `-Xmx8G`：最大内存 8 GiB，可按服务器硬件修改；
- `nogui`：不打开原版图形控制台。

双击 `start.bat`。首次运行会生成文件并因 EULA 尚未同意而停止。

### 第 5 步：接受 EULA

阅读 [Minecraft EULA](https://www.minecraft.net/eula)，确认接受后打开 `eula.txt`：

```properties
eula=true
```

再次运行 `start.bat`，等待生成 `server.properties`、`mods`、`config` 等目录，然后在控制台输入：

```text
stop
```

应正常停止服务器后再安装模组或修改认证配置。

## 二、安装 Compat Login

### 第 6 步：获取对应的 JAR

从 [GitHub Releases](https://github.com/MosesMuxiXie/Compat-Login/releases) 下载与服务器 Minecraft 版本匹配的那一个附件：

```text
compat_login-1.21.11-1.1.0.jar   # Minecraft 1.16 至 1.21.11
compat_login-26.2-1.1.0.jar      # Minecraft 26.1 至 26.2
```

附件名中的版本号是该 JAR 支持的最高 Minecraft 正式版，后面的 `1.1.0` 是模组版本。

也可以从源码构建（需要 JDK 25 或更新版本，因为新版线编译为 Java 25 字节码）：

```powershell
.\gradlew.bat build
```

两个成品位于同一目录：

```text
build\libs\compat_login-1.21.11-1.1.0.jar
build\libs\compat_login-26.2-1.1.0.jar
```

### 第 7 步：放入 `mods` 目录

```text
mods\
└─ compat_login-1.21.11-1.1.0.jar
```

Compat Login 不强制依赖 Fabric API。如果其他模组需要 Fabric API，可继续保留对应 Minecraft 版本的 Fabric API JAR。

升级时必须删除旧版 Compat Login JAR，不得同时保留多个版本。Compat Login 只安装在服务端，玩家客户端不需要安装。

## 三、设置服务器认证

### 第 8 步：修改 `server.properties`

必须设置：

```properties
online-mode=true
```

`online-mode=false` 会让登录绕过所有 Mojang 和第三方会话验证。Compat Login 会输出 `[WARNING]` 并拒绝启动。

Minecraft 1.19.1 及更新版本还有 `enforce-secure-profile`。

#### 服务端没有 authlib-injector

```properties
enforce-secure-profile=false
```

这可避免没有可信聊天档案密钥的第三方账号被原版安全档案检查拒绝。

#### 服务端保留 authlib-injector 1.2.x

按照 [authlib-injector 官方服务端说明](https://yushijinhun.github.io/authlib-injector/en/using-authlib-injector-on-a-minecraft-server.html)：

```properties
enforce-secure-profile=true
```

Minecraft 1.16 至 1.19.0 没有这个服务器选项，不需要手动添加。

### 第 9 步：选择启动模式

#### 模式 A：不加载服务端 authlib-injector

```bat
@echo off
java -Xms2G -Xmx8G -jar fabric-server.jar nogui
pause
```

LittleSkin 玩家的客户端仍需通过支持外置登录的启动器登录，但服务端不需要 Java Agent。

#### 模式 B：保留服务端 authlib-injector

将 `authlib-injector-1.2.7.jar` 放在服务器目录：

```bat
@echo off
java -Xms2G -Xmx8G -javaagent:authlib-injector-1.2.7.jar=littleskin.cn -jar fabric-server.jar nogui
pause
```

注意：

- `-javaagent:...` 必须位于 `-jar` 之前；
- 不需要添加 `-Dauthlibinjector.ignoredPackages`；
- Compat Login 会自动检测 Agent，并直接查询配置中的 Mojang、LittleSkin 等服务；
- Agent 仍可处理第三方材质签名、公钥及其他兼容功能。

如果旧配置含有 authlib-injector 生成的随机本机代理地址：

```text
http://127.0.0.1:<随机端口>/https/sessionserver.mojang.com/session/minecraft/hasJoined
```

Compat Login 会备份原配置为 `config/compat_login.json.authlib-injector.bak`，将 Mojang 项迁移回官方 HTTPS 地址，然后继续启动。服主自行配置的其他本地 HTTP 服务不会被自动修改。

## 四、生成和修改认证配置

### 第 10 步：生成默认配置

安装模组并设置好 `server.properties` 后启动一次服务器。模组会生成：

```text
config\compat_login.json
```

默认内容：

```json
{
  "schemaVersion": 1,
  "authentication": {
    "connectTimeoutSeconds": 5,
    "requestTimeoutSeconds": 8,
    "overallTimeoutSeconds": 13,
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

默认配置已允许正版玩家和 LittleSkin 玩家同时加入。

### 第 11 步：理解配置项

| 配置项 | 作用 | 有效范围 / 说明 |
| --- | --- | --- |
| `schemaVersion` | 配置格式版本 | 当前必须为 `1` |
| `connectTimeoutSeconds` | 建立连接超时 | `1` 到 `30` 秒 |
| `requestTimeoutSeconds` | 单个身份源的响应读取超时 | `1` 到 `60` 秒 |
| `overallTimeoutSeconds` | 一次登录验证（所有身份源并行查询）的总等待上限 | `1` 到 `120` 秒，且不得小于 `connectTimeoutSeconds` |
| `maxResponseBytes` | 单个验证响应最大长度 | `1024` 到 `4194304` 字节 |
| `allowInsecureHttp` | 是否允许明文 HTTP | 公网身份源必须保持 `false` |
| `services` | 按顺序查询的身份源列表 | 至少有一个 `enabled=true` |
| `services[].name` | 日志中显示的身份源名称 | 不得为空或重复 |
| `services[].enabled` | 是否启用该身份源 | 必须为 `true` 或 `false` |
| `services[].hasJoinedUrl` | Yggdrasil API 根地址或完整验证接口 | 必须是可信 HTTP(S) URL |

所有启用的身份源被**并行**查询，第一个返回有效档案的身份源通过认证；`services` 数组顺序只影响日志与告警中的排列。某个身份源不可达不会拖慢其他身份源玩家的登录，整次验证的等待上限由 `overallTimeoutSeconds` 兜底。

### 第 12 步：填写认证地址

`hasJoinedUrl` 可填完整 `hasJoined` 地址，也可填 API 根地址：

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

模组会自动补全为对应的 `/session/minecraft/hasJoined` 接口。

不要填写：

- LittleSkin 登录或注册网页；
- `/authserver/authenticate` 密码登录接口；
- 玩家邮箱、密码或 access token；
- 未经服主信任的第三方服务器；
- 公网上的明文 `http://` 地址。

玩家密码只应提交给玩家选择的启动器和身份源，不能写进服务端配置。

### 第 13 步：添加其他 Yggdrasil 身份源

在 `services` 数组中增加：

```json
{
  "name": "MyYggdrasil",
  "enabled": true,
  "hasJoinedUrl": "https://account.example.com/api/yggdrasil"
}
```

仅配置可信身份源。身份源有能力声明玩家名、UUID 和档案属性；恶意身份源可能冒充其他来源的玩家。

### 第 14 步：仅在可信内网使用 HTTP

如果身份源只在同一可信内网中提供 HTTP 服务：

```json
"allowInsecureHttp": true
```

然后才能配置：

```json
{
  "name": "TrustedLanYggdrasil",
  "enabled": true,
  "hasJoinedUrl": "http://192.168.1.10:8080/api/yggdrasil"
}
```

不要为了解决公网 HTTPS 证书或连接错误而开启此选项。

## 五、玩家如何加入

### 正版玩家

1. 使用官方 Minecraft 启动器或其他支持 Microsoft 正版登录的启动器；
2. 选择与服务器一致的 Minecraft 版本；
3. 正常连接服务器。

服务端会通过 Mojang `hasJoined` 接口验证会话。

### LittleSkin 玩家

1. 在支持 authlib-injector / 外置登录的客户端启动器中添加 LittleSkin；
2. 身份源填写 `https://littleskin.cn/api/yggdrasil`，或按启动器说明使用 ALI 地址 `littleskin.cn`；
3. 在客户端启动器中登录 LittleSkin 账号；
4. 选择与服务器一致的 Minecraft 版本；
5. 使用对应角色连接同一服务器。

客户端的 authlib-injector 与服务端是否加载 Java Agent 是两件独立的事。

## 六、确认安装成功

启动日志应包含实际 Minecraft 和 Loader 版本，例如：

```text
Loading Minecraft 1.21.11 with Fabric Loader 0.19.3
compat_login 1.1.0
Compat Login initialized with 2 enabled authentication service(s)
```

使用服务端 authlib-injector 时还应包含：

```text
[authlib-injector] [INFO] Version: 1.2.7
Detected server-side authlib-injector; compatibility mode is enabled
```

然后分别使用一个正版账号和一个第三方测试账号进入服务器。成功时日志类似：

```text
Authenticated PlayerName (uuid) via Mojang
Authenticated PlayerName (uuid) via LittleSkin
```

### 合并两个 UUID 的玩家数据

此操作会用来源账号的数据覆盖目标账号数据，只能由 `ops.json` 中权限等级至少为 `3` 的管理员发起。开始前应让两个账号各登录服务器一次，以便服务器记录准确的名字和 UUID，并确保来源账号已经离线。

管理员输入：

```text
/account migrate <来源玩家名> <目标玩家名> begin
```

服务器会向管理员返回一个一次性迁移码，有效期为 15 分钟。目标玩家随后使用接收数据的账号登录；聊天框会显示确认问题和确认指令。目标账号需要在提示出现后的 5 分钟内输入：

```text
/account migrate confirm <迁移码>
```

`confirm` 不要求管理员权限，但执行者的登录 UUID 必须与迁移目标 UUID 完全一致。确认后，服务器会踢出目标玩家，并在迁移事务结束前按 UUID 拒绝该账号重新登录；该锁不会写入原版封禁列表。目标玩家完全离线后，服务器会完成以下操作：

- 将来源 UUID 的 `playerdata`、`advancements` 和 `stats` 文件覆盖到目标 UUID；
- 改写玩家 NBT/JSON 中出现的来源 UUID，并删除来源 UUID 文件；
- 从 `usercache.json` 删除来源身份缓存并保留正确的目标身份；
- 清除服务端内存中的统计与进度缓存，避免旧目标数据在下次登录时覆盖迁移结果；
- 迁移完成或失败回滚后立即释放目标 UUID 的登录锁，玩家随后即可重新连接。

每次迁移都会先把来源文件、被覆盖的目标文件和 `usercache.json` 备份到 `config/compat_login/migration-backups/`。中途写入失败时会自动回滚。已有目标数据会被覆盖；来源账号以后再次登录会创建一份新的空白数据。模组只能可靠迁移上述原版 UUID 存档，其他模组或插件的私有数据库需要按其各自文档另行迁移。

忽略提示时，服务端的确认状态会在 5 分钟后自动删除。纯服务端模组无法从原版客户端的历史聊天记录中撤回一条已经显示的系统消息。

## 七、MCDReforged 部署

Compat Login 不是 MCDR 插件，应放入 Fabric 服务端的 `mods` 目录。MCDR 只负责启动进程、读取日志和管理服务器。

通用目录结构：

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
   ├─ authlib-injector-1.2.7.jar          # 可选
   ├─ server.properties
   ├─ config\compat_login.json
   └─ mods\
      └─ compat_login-1.21.11-1.1.0.jar
```

根目录 `config.yml` 至少确认：

```yaml
working_directory: server
start_command: start.bat
handler: vanilla_handler
encoding: utf8
decoding: utf8
```

不使用服务端 authlib-injector 时，`server\start.bat` 写为：

```bat
@echo off
java -Xms2G -Xmx8G -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar fabric-server.jar nogui
```

保留服务端 authlib-injector 时：

```bat
@echo off
java -Xms2G -Xmx8G -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -javaagent:authlib-injector-1.2.7.jar=littleskin.cn -jar fabric-server.jar nogui
```

MCDR 管理的服务端脚本末尾不要写 `pause`。可选的根目录 `start-mcdr.bat` 可写为：

```bat
@echo off
cd /d "%~dp0"
mcdreforged
pause
```

## 八、从旧版升级

1. 在控制台执行 `stop`；
2. 备份整个服务器，至少备份世界和 `config`；
3. 从 `mods` 删除所有旧的 `compat_login-*.jar`；
4. 从 Releases 下载与本服 Minecraft 版本对应的 `compat_login-<最高支持版本>-1.1.0.jar` 并放入 `mods`；
5. 保留原来的 `config/compat_login.json`；
6. 已有 authlib-injector 时可保留原 `-javaagent` 参数；
7. 将 Fabric Loader 更新到 `0.19.3` 或更新稳定版；
8. 启动服务器并检查配置迁移日志；
9. 分别测试正版和第三方账号。

`0.2.0` 存在过早检查 `online-mode` 的问题，可能将已经设为 `true` 的服务器误判为离线模式。`0.3.0` 直接读取实际 `server.properties` 文件，不再依赖版本特定的服务器生命周期方法。

## 九、常见错误

### `Compat Login requires online-mode=true`

确认修改的是实际服务器工作目录中的 `server.properties`：

```properties
online-mode=true
```

使用 MCDR 时，该文件位于 `working_directory` 指向的目录。完整停止后重新启动，不要只执行 `/reload`。如果旧版 `0.2.0` 在文件已为 `true` 时仍误报，请升级到 `0.3.0`。

### `Compat Login configuration is invalid`

继续查看异常下面的每一条：

```text
[WARNING] config/compat_login.json -> 字段路径: 具体原因
```

修复所有列出的字段后重启。模组会一次列出尽可能多的问题，并拒绝使用不安全的备用认证方式继续启动。

### Fabric 报告重复的 `compat_login`

`mods` 中存在多个 Compat Login JAR。删除其余的，只保留与本服 Minecraft 版本对应的那一个；两条版本线的 JAR 不能同时安装。

### `http is disabled`

公网服务应改为 `https://`。只有可信内网服务才能设置：

```json
"allowInsecureHttp": true
```

### `One or more configured authentication services were unavailable`

检查：

1. 服务器能否访问身份源域名；
2. URL 是否为正确的 Yggdrasil API 根地址；
3. DNS、防火墙、代理和 HTTPS 证书是否正常；
4. 身份源本身是否宕机；
5. `connectTimeoutSeconds`、`requestTimeoutSeconds` 和 `overallTimeoutSeconds` 是否过短。

为了安全，只要某个身份源请求异常，并且本次登录也没有在其他来源成功匹配，模组会将它作为认证服务不可用处理，而不是静默放行。

### Mixin 无法匹配 `hasJoinedServer`

确认：

- 使用的是 Compat Login `0.3.0` 或更新版本；
- Minecraft 为 `1.16` 至 `1.21.11` 或 `26.1` 至 `26.2` 正式版，而非 `26.3` 快照；
- 安装的 JAR 与服务器所在版本线一致：`1.21.11` 结尾的 JAR 用于 `1.21.11` 及更早版本，`26.2` 结尾的 JAR 用于 `26.x`；
- Fabric Loader 至少为 `0.18.4`；
- 没有安装多个 Compat Login JAR。

提交问题时应附上完整 `latest.log` 和崩溃报告。

## 十、安全说明

- 服务器必须保持 `online-mode=true`；
- 只添加可信身份源；
- 公网身份源必须使用 HTTPS；
- 权限、封禁和白名单尽量按 UUID 管理，而不是只看玩家名；
- 不同身份源可能存在同名甚至 UUID 冲突，公开服务器前必须制定处理策略；
- 模组解决的是登录会话共存，不保证所有客户端都信任其他身份源的材质签名；
- 修改认证配置前应备份文件，并在维护窗口内分别测试各类账号。

## 十一、开发与发布

本地构建需要 JDK 25 或更新版本，两条版本线共用 `src/main/java`，各自的构建脚本在 `versions/<最高支持版本>/`：

```powershell
.\gradlew.bat build
```

该命令会编译并测试两条线，把两个发布 JAR 收集到 `build\libs`，只构建其中一条时使用：

```powershell
.\gradlew.bat :versions:1.21.11:build
.\gradlew.bat :versions:26.2:build
```

旧线构建会额外检查所有主类的字节码主版本不高于 `52`，即 Java 8 字节码。

使用本地 authlib-injector JAR 做联合启动测试：

```powershell
.\gradlew.bat :versions:1.21.11:runServer "-PcompatLoginTestAuthlibInjector=D:\path\to\authlib-injector-1.2.7.jar"
```

对已构建的 JAR 做真实服务端启动测试（`-JavaExecutable` 用于指定该 Minecraft 版本需要的 JDK）：

```powershell
powershell -File scripts\smoke-test-server.ps1 -MinecraftVersion 26.2 -ModJar build\libs\compat_login-26.2-1.1.0.jar
```

GitHub Actions 包含：

- 单元测试；
- Java 8 字节码检查；
- 14 个 Minecraft / Java 组合的服务端启动测试，每个组合使用所属版本线的 JAR；
- `v*` 标签的 GitHub Release 自动创建和两个 JAR 的上传。

## 实现原理

Minecraft 旧版 authlib 的 `hasJoinedServer` 返回 `GameProfile`，新版则返回 `ProfileResult`。Compat Login 使用：

- 不依赖特定 authlib 版本的内部档案模型；
- 多个受 Mixin 分组约束的方法签名适配器；
- 运行时反射创建旧版 `GameProfile` 或新版 `ProfileResult`；
- Java 8 可用的 `HttpURLConnection`；
- 直接读取 `server.properties` 的安全检查。

因此同一套源码只需要两个构建产物：一个 Java 8 字节码的 JAR 覆盖 Minecraft 1.16 至 1.21.11，一个 Java 25 字节码、不做重映射的 JAR 覆盖 26.1 至 26.2。

## 参考资料

- [Fabric 官方服务端启动器](https://fabricmc.net/use/server/)
- [Fabric Meta API](https://meta.fabricmc.net/)
- [Fabric Loader 文档](https://docs.fabricmc.net/develop/loader/)
- [Fabric 26.2 开发文档](https://docs.fabricmc.net/develop/)
- [MCDReforged：配置](https://docs.mcdreforged.com/zh-cn/latest/configuration.html)
- [authlib-injector：在 Minecraft 服务端使用](https://yushijinhun.github.io/authlib-injector/en/using-authlib-injector-on-a-minecraft-server.html)
- [authlib-injector：Yggdrasil 服务端技术规范](https://yushijinhun.github.io/authlib-injector/en/yggdrasil-server-technical-specification.html)
- [LittleSkin Yggdrasil 文档](https://manual.littlesk.in/yggdrasil/)
- [mc-multilogin-compat-mod](https://github.com/wifi-left/mc-multilogin-compat-mod)
- [MultiLogin](https://github.com/CaaMoe/MultiLogin)

## License

本项目采用 [Apache License 2.0](LICENSE)。
