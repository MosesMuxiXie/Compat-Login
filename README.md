# Compat Login

Compat Login 是一个仅服务端安装的 Fabric 模组，让同一台在线模式服务器同时接受 Mojang 正版会话与一个或多个第三方 Yggdrasil 会话（例如 LittleSkin）。当前目标环境：

- Minecraft 1.21.11
- Fabric Loader 0.18.4+
- Fabric API 0.141.6+1.21.11
- Java 21+

模组不会改成离线验证，也不会接收玩家密码。客户端仍向自己的身份源提交标准 Yggdrasil `join` 请求；服务端收到登录后，按配置顺序查询各身份源的 `hasJoined` 接口，第一个有效档案即通过。MCDR 不需要额外适配，仍可读取正常的服务端日志、玩家加入/离开事件和进程退出状态。

## 安装

1. 执行 `./gradlew build`（Windows 使用 `gradlew.bat build`），在 `build/libs/` 取得模组 JAR。
2. 将 JAR 放进 Fabric 服务端的 `mods/` 文件夹。服务端需要安装匹配版本的 Fabric API。
3. 确认 `server.properties` 中 `online-mode=true`。这是强制要求；设为 `false` 会绕过所有会话认证，模组会拒绝启动。
4. 首次启动会生成 `config/compat_login.json`。默认已包含 Mojang 与 LittleSkin，可以直接使用或继续添加受信任的 Yggdrasil 服务。
5. 正版玩家正常启动游戏；LittleSkin 玩家在启动器中选择 LittleSkin 外置登录（其 Yggdrasil API 根地址为 `https://littleskin.cn/api/yggdrasil`）。服务端 JVM 不需要加载 authlib-injector Java Agent。

为了兼容没有受信任聊天档案证书的外置登录客户端，建议设置 `enforce-secure-profile=false`。保持为 `true` 时模组会警告，但不会强制停止，因为某些自建身份源可能完整支持档案密钥。

## 配置

默认 `config/compat_login.json`：

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

`services` 的顺序就是查询优先级。`hasJoinedUrl` 必须填写完整接口，并以 `/session/minecraft/hasJoined` 结尾；模组会自行添加 `username`、`serverId` 和按原版设置传入的 `ip` 参数。公网服务应使用 HTTPS。只有可信内网的自建服务才应在明确理解风险后启用 `allowInsecureHttp`。

配置错误会一次性列出尽可能多的问题，并阻止服务器继续启动。例如：

```text
[WARNING] config/compat_login.json -> authentication.services[1].hasJoinedUrl: path must end with /session/minecraft/hasJoined
[WARNING] config/compat_login.json -> authentication.requestTimeoutSeconds: must be between 1 and 60, but was 0
```

JSON 本身损坏时，Gson 返回的行号、列号和 JSON 路径也会保留在警告中，方便直接定位。

## 安全与兼容性说明

- 只配置你信任的身份源。每个身份源都能声明玩家名、UUID 和档案属性；恶意身份源可以伪造其他来源的身份。
- 不同来源可能存在相同玩家名甚至 UUID。权限、封禁和白名单系统应优先使用 UUID，并在开放服务器前处理冲突策略。
- 模组只解决会话登录共存。第三方材质签名不一定被未安装 authlib-injector 的正版客户端信任，因此跨来源皮肤显示不属于当前保证范围。
- 任一身份源请求失败而本次登录又没有在其他来源匹配时，模组按“认证服务不可用”处理，不会把故障误判成安全的无效会话。
- 查询响应限制为 `maxResponseBytes`，并校验 UUID、玩家名和属性结构，避免错误或恶意响应直接进入游戏档案。

## 参考

- [mc-multilogin-compat-mod](https://github.com/wifi-left/mc-multilogin-compat-mod)
- [Fabric 1.21.11 开发文档](https://docs.fabricmc.net/zh_cn/1.21.11/develop/)
- [MultiLogin（项目已归档）](https://github.com/CaaMoe/MultiLogin)
- [authlib-injector Yggdrasil 服务端技术规范](https://yushijinhun.github.io/authlib-injector/zh/yggdrasil-server-technical-specification.html)
- [LittleSkin Yggdrasil 文档](https://manual.littlesk.in/yggdrasil/)

## 测试

```bash
./gradlew test
./gradlew build
```

测试覆盖默认配置、多个字段错误的聚合报告、HTTP 安全开关，以及本地模拟的多身份源回退与档案解析。

Windows 下如果工程位于带中文字符的 OneDrive 路径，且 Gradle 报 `GradleWorkerMain` 或测试类 `ClassNotFoundException`，请把工程复制到纯 ASCII 路径后再运行测试。这是 Gradle 测试子进程的类路径问题，不影响生成的模组 JAR 或服务端运行。

## License

CC0-1.0，详见 `LICENSE`。
