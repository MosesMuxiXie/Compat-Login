# Compat Login

Compat Login 是一个仅安装在服务端的 Fabric 模组。它让同一个 `online-mode=true` 的服务器同时接受：

- Mojang / Microsoft 正版账号；
- LittleSkin 等第三方 Yggdrasil 账号；
- 服主额外配置的其他受信任 Yggdrasil 身份源。

模组不会接收、保存或转发玩家密码。玩家仍在自己的启动器中登录；服务端只向各身份源的 `hasJoined` 接口验证本次会话。

## 版本要求

| 组件 | 本项目使用的版本 |
| --- | --- |
| Minecraft | `1.21.11` |
| Fabric Loader | `0.18.4` 或更高版本 |
| Fabric API | `0.141.6+1.21.11` |
| Java | `21` 或更高版本 |
| Compat Login | `0.2.0` |
| authlib-injector | 可选；已验证 `1.2.7` |

下面以 Windows 独立服务器为主。Linux 用户只需要把 `.bat` 命令改成对应的 Shell 命令。

## 一、从零创建 Fabric 服务器

### 第 1 步：安装并检查 Java

安装 Java 21 或更高版本。可使用 [Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21)，Windows 也可以执行：

```powershell
winget install EclipseAdoptium.Temurin.21.JDK
```

重新打开终端后检查：

```powershell
java -version
```

输出中的主版本必须为 `21` 或更高。不要使用 Java 17 启动 Minecraft 1.21.11 服务器。

### 第 2 步：创建服务器目录

示例：

```powershell
New-Item -ItemType Directory -Path "D:\Minecraft\CompatLoginServer"
Set-Location "D:\Minecraft\CompatLoginServer"
```

路径可以自行修改。建议避免把生产服务器放进会实时同步、锁定文件的 OneDrive 目录。

### 第 3 步：下载 Fabric 服务端启动器

可在 [Fabric 官方服务端下载页](https://fabricmc.net/use/server/)选择：

- Minecraft：`1.21.11`
- Loader：`0.18.4`
- Launcher / Installer：`1.1.0`

也可以在服务器目录中执行：

```powershell
curl.exe -OJ "https://meta.fabricmc.net/v2/versions/loader/1.21.11/0.18.4/1.1.0/server/jar"
```

下载后应得到类似文件：

```text
fabric-server-mc.1.21.11-loader.0.18.4-launcher.1.1.0.jar
```

### 第 4 步：创建首次启动脚本

在服务器目录创建 `start-server.bat`：

```bat
@echo off
java -Xms2G -Xmx8G -jar fabric-server-mc.1.21.11-loader.0.18.4-launcher.1.1.0.jar nogui
pause
```

- `-Xms2G`：初始内存 2 GiB；
- `-Xmx8G`：最大内存 8 GiB，可按机器内存修改；
- `nogui`：不打开原版图形控制台。

双击 `start-server.bat`。首次运行会生成文件并因 EULA 尚未同意而停止。

### 第 5 步：接受 EULA

阅读 [Minecraft EULA](https://www.minecraft.net/eula)，确认接受后打开 `eula.txt`，修改为：

```properties
eula=true
```

再次运行 `start-server.bat`，等待服务器生成 `server.properties`、`mods`、`config` 等目录，然后在控制台输入：

```text
stop
```

务必正常停止服务器后再改配置或安装模组。

## 二、安装 Fabric API 和 Compat Login

### 第 6 步：准备模组文件

下载与 Minecraft 1.21.11 匹配的 [Fabric API](https://modrinth.com/mod/fabric-api/versions)，本项目验证版本为：

```text
fabric-api-0.141.6+1.21.11.jar
```

构建本项目时，在项目目录执行：

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

成品位于：

```text
build\libs\compat_login-0.2.0.jar
```

### 第 7 步：放入 `mods` 目录

服务器的 `mods` 目录最终至少包含：

```text
mods\
├─ fabric-api-0.141.6+1.21.11.jar
└─ compat_login-0.2.0.jar
```

升级 Compat Login 时必须删除旧版 JAR。不要让 `0.1.x` 与 `0.2.0` 同时存在，否则 Fabric 会报告重复模组。

Compat Login 只装服务端。正版客户端和 LittleSkin 客户端都不需要安装这个模组。

## 三、设置服务器认证选项

### 第 8 步：修改 `server.properties`

必须设置：

```properties
online-mode=true
```

`online-mode=false` 会绕过全部会话认证，Compat Login 会拒绝启动。这不是可选设置。

`enforce-secure-profile` 根据服务器是否加载服务端 authlib-injector 设置：

#### 模式 A：服务器没有 authlib-injector

建议：

```properties
enforce-secure-profile=false
```

这样可避免没有可信聊天档案密钥的第三方账号被原版安全档案检查拒绝。

#### 模式 B：服务器已经有 authlib-injector 1.2.x

按照 [authlib-injector 官方服务端说明](https://yushijinhun.github.io/authlib-injector/en/using-authlib-injector-on-a-minecraft-server.html)，设置：

```properties
enforce-secure-profile=true
```

Compat Login `0.2.0` 会自动检测服务端 Agent，并进入兼容模式。

### 第 9 步：选择启动模式

#### 模式 A：不加载服务端 authlib-injector

保持最初的启动脚本：

```bat
@echo off
java -Xms2G -Xmx8G -jar fabric-server-mc.1.21.11-loader.0.18.4-launcher.1.1.0.jar nogui
pause
```

这是新建服务器的简单方案。LittleSkin 玩家客户端仍需通过支持外置登录的启动器登录，但服务端不需要 Java Agent。

#### 模式 B：保留已有的服务端 authlib-injector

把 `authlib-injector-1.2.7.jar` 放在服务器目录，启动脚本写为：

```bat
@echo off
java -Xms2G -Xmx8G -javaagent:authlib-injector-1.2.7.jar=littleskin.cn -jar fabric-server-mc.1.21.11-loader.0.18.4-launcher.1.1.0.jar nogui
pause
```

注意：

- `-javaagent:...` 必须位于 `-jar` 之前；
- 不需要添加 `-Dauthlibinjector.ignoredPackages`；
- Compat Login 会绕过 Agent 对登录检查地址的重定向，直接查询配置中的 Mojang、LittleSkin 等服务；
- Agent 仍可处理第三方材质签名、公钥和它自己的其他兼容功能。

如果旧版 Compat Login 曾在 Agent 环境下生成过随机端口地址，例如：

```text
http://127.0.0.1:50378/https/sessionserver.mojang.com/session/minecraft/hasJoined
```

`0.2.0` 会自动：

1. 识别这个 authlib-injector 临时代理地址；
2. 备份原配置为 `config/compat_login.json.authlib-injector.bak`；
3. 把 Mojang 项恢复为官方 HTTPS 地址；
4. 继续启动服务器。

用户自行配置的其他本地 HTTP 服务不会被自动修改。

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

默认配置已经允许正版玩家和 LittleSkin 玩家同时加入，无需再改 URL。

### 第 11 步：理解每个配置项

| 配置项 | 作用 | 有效范围 / 说明 |
| --- | --- | --- |
| `schemaVersion` | 配置格式版本 | 当前必须为 `1` |
| `connectTimeoutSeconds` | 建立连接超时 | `1` 到 `30` 秒 |
| `requestTimeoutSeconds` | 整个验证请求超时 | `1` 到 `60` 秒 |
| `maxResponseBytes` | 单个验证响应最大长度 | `1024` 到 `4194304` 字节 |
| `allowInsecureHttp` | 是否允许明文 HTTP | 公网身份源必须保持 `false` |
| `services` | 按顺序查询的身份源列表 | 至少有一个 `enabled=true` |
| `services[].name` | 日志中显示的身份源名称 | 不得为空或重复 |
| `services[].enabled` | 是否启用该身份源 | 必须为 `true` 或 `false` |
| `services[].hasJoinedUrl` | Yggdrasil API 根地址或完整验证接口 | 必须是可信 HTTP(S) URL |

查询顺序就是 `services` 数组顺序。服务端收到登录后依次查询；第一个返回有效档案的身份源通过认证。

### 第 12 步：填写认证地址

`hasJoinedUrl` 可以填写完整 `hasJoined` 地址，也可以填写 API 根地址。

以下写法都受支持：

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

模组会自动补全为相应的 `/session/minecraft/hasJoined` 接口。

不要填写：

- LittleSkin 登录网页或注册网页；
- `/authserver/authenticate` 密码登录接口；
- 玩家邮箱、密码、access token；
- 不受你信任的第三方服务器；
- 公网上的明文 `http://` 地址。

玩家密码只应提交给玩家选择的启动器和身份源，不能写进服务端配置。

### 第 13 步：添加其他 Yggdrasil 身份源

在 `services` 数组中增加一项：

```json
{
  "name": "MyYggdrasil",
  "enabled": true,
  "hasJoinedUrl": "https://account.example.com/api/yggdrasil"
}
```

完整示例：

```json
"services": [
  {
    "name": "Mojang",
    "enabled": true,
    "hasJoinedUrl": "https://sessionserver.mojang.com"
  },
  {
    "name": "LittleSkin",
    "enabled": true,
    "hasJoinedUrl": "https://littleskin.cn/api/yggdrasil"
  },
  {
    "name": "MyYggdrasil",
    "enabled": true,
    "hasJoinedUrl": "https://account.example.com/api/yggdrasil"
  }
]
```

只配置你信任的身份源。身份源有能力声明玩家名、UUID 和档案属性；恶意身份源可能冒充其他来源的玩家。

### 第 14 步：仅在可信内网使用 HTTP

如果身份源只在同一可信内网中提供 HTTP 服务：

```json
"allowInsecureHttp": true
```

然后才可以配置：

```json
{
  "name": "TrustedLanYggdrasil",
  "enabled": true,
  "hasJoinedUrl": "http://192.168.1.10:8080/api/yggdrasil"
}
```

不要为了解决公网 HTTPS 证书或连接错误而开启此选项。HTTP 会暴露请求内容并允许中间人篡改响应。

## 五、玩家如何加入

### 正版玩家

1. 使用官方 Minecraft 启动器或其他支持 Microsoft 正版登录的启动器；
2. 选择 Minecraft 1.21.11；
3. 正常连接服务器。

服务端会通过 Mojang `hasJoined` 接口验证会话。

### LittleSkin 玩家

1. 在支持 authlib-injector / 外置登录的客户端启动器中添加 LittleSkin；
2. 客户端身份源填写 `https://littleskin.cn/api/yggdrasil`，或按启动器说明使用 ALI 地址 `littleskin.cn`；
3. 在客户端启动器中登录 LittleSkin 账号；
4. 使用对应角色启动 Minecraft 1.21.11；
5. 连接同一个服务器地址。

客户端的 authlib-injector 与服务端是否保留 Java Agent 是两件独立的事。即使服务器使用“模式 A”，LittleSkin 客户端仍可通过自己的外置登录启动器加入。

## 六、确认安装成功

### 无服务端 authlib-injector

启动日志应包含：

```text
Loading Minecraft 1.21.11 with Fabric Loader 0.18.4
compat_login 0.2.0
Compat Login initialized with 2 enabled authentication service(s)
```

### 有服务端 authlib-injector

启动日志还应包含：

```text
[authlib-injector] [INFO] Version: 1.2.7
Detected server-side authlib-injector; compatibility mode is enabled
Compat Login initialized with 2 enabled authentication service(s)
```

authlib-injector 日志不应再出现：

```text
Transformed [cn.compatlogin.config.CompatLoginConfig] with [Constant URL Transformer]
```

然后分别使用一个正版账号和一个 LittleSkin 测试账号进入服务器。成功时服务端会记录类似：

```text
Authenticated PlayerName (uuid) via Mojang
Authenticated PlayerName (uuid) via LittleSkin
```

## 七、MCDReforged 部署

Compat Login 不需要 MCDR 插件。MCDR 只负责启动进程、读取日志和管理服务器。

下面以你正在使用的 MCDReforged `2.15.7` 为例。推荐目录为：

```text
D:\IHC Server\
├─ start-mcdr.bat                           # 可选
├─ config.yml
├─ permission.yml
├─ config\
├─ logs\
├─ plugins\
└─ server\
   ├─ start.bat
   ├─ fabric-server-mc.1.21.11-loader.0.18.4-launcher.1.1.0.jar
   ├─ authlib-injector-1.2.7.jar              # 可选
   ├─ server.properties
   ├─ config\compat_login.json
   └─ mods\
      ├─ fabric-api-0.141.6+1.21.11.jar
      └─ compat_login-0.2.0.jar
```

先打开根目录的 `config.yml`，确认至少有以下项：

```yaml
working_directory: server
start_command: start.bat
handler: vanilla_handler
encoding: utf8
decoding: utf8
```

- `working_directory: server` 表示 MCDR 会在 `D:\IHC Server\server` 中启动 Fabric；
- `start_command: start.bat` 指向的是 `server\start.bat`，不是根目录脚本；
- Fabric 服务器使用 `vanilla_handler`；
- UTF-8 编解码可避免 MCDR 和 Minecraft 日志乱码。

不使用服务端 authlib-injector 时，`D:\IHC Server\server\start.bat` 写为：

```bat
@echo off
java -Xms2G -Xmx8G -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar fabric-server-mc.1.21.11-loader.0.18.4-launcher.1.1.0.jar nogui
```

保留服务端 authlib-injector 时，改为：

```bat
@echo off
java -Xms2G -Xmx8G -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -javaagent:authlib-injector-1.2.7.jar=littleskin.cn -jar fabric-server-mc.1.21.11-loader.0.18.4-launcher.1.1.0.jar nogui
```

MCDR 管理的服务端脚本末尾不要写 `pause`，否则服务器正常停止后批处理进程仍可能不退出。

如果希望双击脚本启动 MCDR，可选的根目录 `D:\IHC Server\start-mcdr.bat` 写为：

```bat
@echo off
cd /d "%~dp0"
mcdreforged
pause
```

也可以打开命令行，进入 `D:\IHC Server` 后直接执行：

```powershell
mcdreforged
```

如果系统找不到 `mcdreforged` 命令，可尝试 `python -m mcdreforged`。不要进入 `server` 目录再启动 MCDR，因为 MCDR 需要从根目录读取 `config.yml`。

使用 MCDR 时仍应以服务器日志中的最底层 `Caused by:` 和 Compat Login 的 `[WARNING]` 为准；MCDR 本身不会改变 Fabric 登录认证流程。

## 八、从旧版本升级

1. 在控制台执行 `stop`；
2. 备份整个服务器，至少备份世界和 `config`；
3. 从 `mods` 删除旧的 `compat_login-*.jar`；
4. 放入 `compat_login-0.2.0.jar`；
5. 保留原来的 `config/compat_login.json`；
6. 如果服务器已有 authlib-injector，可以保留原 `-javaagent` 参数；
7. 启动服务器并检查 compatibility mode 和配置迁移日志；
8. 分别测试正版和第三方账号。

若旧配置含有 authlib-injector 随机本机代理 URL，模组会自动备份并迁移，不需要手动删除配置。

## 九、常见错误

### `Compat Login requires online-mode=true`

修改 `server.properties`：

```properties
online-mode=true
```

停止后重新启动，不要只执行 `/reload`。

### `Compat Login configuration is invalid`

继续查看异常下面的每一条：

```text
[WARNING] config/compat_login.json -> 字段路径: 具体原因
```

修复列出的字段后重启。模组会一次列出尽可能多的问题，并拒绝使用不安全的备用认证方式继续启动。

### `http is disabled`

公网服务应把 URL 改为 `https://`。只有受信任内网服务才能设置：

```json
"allowInsecureHttp": true
```

### Fabric 报告重复的 `compat_login`

`mods` 中存在两个 Compat Login JAR。删除旧版，只保留 `compat_login-0.2.0.jar`。

### `One or more configured authentication services were unavailable`

检查：

1. 服务器能否访问该身份源域名；
2. URL 是否为正确的 Yggdrasil API 根地址；
3. DNS、防火墙、代理和 HTTPS 证书是否正常；
4. 身份源本身是否宕机；
5. `connectTimeoutSeconds` 和 `requestTimeoutSeconds` 是否过短。

为了安全，只要某个身份源请求异常、并且本次登录也没有在其他来源成功匹配，模组会把它作为认证服务不可用处理，而不是当成普通无效账号静默放行。

### 第三方账号无法通过安全档案检查

- 无服务端 Agent：尝试 `enforce-secure-profile=false`；
- authlib-injector 1.2.x：按其官方文档使用 `enforce-secure-profile=true`，并检查身份源是否支持相应功能；
- 检查客户端是否确实使用正确的外置登录启动配置。

### 其他模组的 Mixin / refmap 警告

如果日志已经出现 `Compat Login initialized...`，随后才由其他模组报错，应查看最底层 `Caused by` 中指向的模组。并非所有 Mixin 或 refmap 警告都来自 Compat Login。

## 十、安全说明

- 只添加你信任的身份源；
- 公网身份源必须使用 HTTPS；
- 权限、封禁和白名单尽量按 UUID 管理，而不是只看玩家名；
- 不同身份源可能存在同名甚至 UUID 冲突，开放服务器前必须制定处理策略；
- 模组解决的是登录会话共存，不保证所有客户端都信任其他身份源的材质签名；
- 修改认证配置前备份文件，并在维护窗口内分别测试各类账号；
- 服务器必须保持 `online-mode=true`。

## 十一、开发与验证

```powershell
.\gradlew.bat test
.\gradlew.bat build
```

使用本地 authlib-injector JAR 进行联合启动测试：

```powershell
.\gradlew.bat runServer "-PcompatLoginTestAuthlibInjector=D:\path\to\authlib-injector-1.2.7.jar"
```

Windows 下如果项目位于带中文字符的 OneDrive 路径，并遇到 `GradleWorkerMain` 或测试类 `ClassNotFoundException`，请把项目复制到纯 ASCII 路径后再运行测试。这是 Gradle 测试子进程的类路径问题，不影响生成的模组 JAR 或实际服务器运行。

## 参考资料

- [Fabric 官方服务端启动器](https://fabricmc.net/use/server/)
- [Fabric 1.21.11 开发文档](https://docs.fabricmc.net/1.21.11/develop/)
- [Fabric API 官方 Maven](https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/)
- [MCDReforged 2.15.7：配置](https://docs.mcdreforged.com/zh-cn/latest/configuration.html)
- [authlib-injector：在 Minecraft 服务端使用](https://yushijinhun.github.io/authlib-injector/en/using-authlib-injector-on-a-minecraft-server.html)
- [authlib-injector：Yggdrasil 服务端技术规范](https://yushijinhun.github.io/authlib-injector/en/yggdrasil-server-technical-specification.html)
- [LittleSkin Yggdrasil 文档](https://manual.littlesk.in/yggdrasil/)
- [mc-multilogin-compat-mod](https://github.com/wifi-left/mc-multilogin-compat-mod)
- [MultiLogin](https://github.com/CaaMoe/MultiLogin)

## License

CC0-1.0，详见 `LICENSE`。
