# 项目记忆（MEMORY）

> 本文件是项目跨会话的持久记忆：用途、架构、历次改动、遗留问题与隐患。
> 每次对项目做出实质改动后都应更新第 3、4 节。

---

## 1. 这个项目是干什么的

**Compat Login**（mod id `compat_login`，包名 `cn.compatlogin`）是一个 Fabric **服务端**模组，让一台 Minecraft 服务器同时接受多个 Yggdrasil 兼容身份源的玩家登录（典型场景：Mojang 正版 + LittleSkin 等第三方外置登录），并支持把同一玩家在不同身份源下的存档（不同 UUID）安全地合并迁移。

核心能力：

- **多身份源鉴权**：替换原版 `YggdrasilMinecraftSessionService.hasJoinedServer`，**并行**查询所有启用 provider，任一命中即放行（首个成功即返回，整体等待由 `overallTimeoutSeconds` 兜底）；配置位于 `config/compat_login.json`（首次启动自动生成，含 Mojang 与 LittleSkin 两个默认服务）。
- **安全护栏**：启动时强制校验 `server.properties` 的 `online-mode=true`（否则拒绝启动）；检测到服务端 authlib-injector 时进入兼容模式；配置文件非法时拒绝启动且不覆盖原文件。
- **`/account migrate` 玩家数据迁移**：管理员发起一次性确认码，目标账号上线确认后自动踢出、锁定目标登录、把 `playerdata/advancements/stats/usercache` 中源 UUID 改写为目标 UUID，全程带备份与回滚（事务式）。
- **跨版本兼容**：同一套源码产出两个 JAR，覆盖 Minecraft 1.16–1.21.11（重映射 intermediary、Java 8 字节码）与 26.1–26.2（官方名、Java 25 字节码），authlib 2.x/6+/7+ 的 `GameProfile`/`ProfileResult` 差异，以及 1.21.9+ `PlayerList.canPlayerLogin` 的 `NameAndId` 参数，全部用反射桥接。

## 2. 项目架构

### 仓库布局

```
compat_login-template-1.21.11/
├─ src/main/java/cn/compatlogin/      # 两条版本线共用的全部源码
│  ├─ CompatLogin.java                # 入口：初始化鉴权服务、server.properties 守卫
│  ├─ AuthlibInjectorCompatibility.java  # 检测 -javaagent:authlib-injector 参数
│  ├─ ServerPropertiesGuard.java      # online-mode=true / enforce-secure-profile 检查
│  ├─ auth/                           # 鉴权：MultiAuthService(HTTP查询) + AuthlibProfileAdapter(反射桥)
│  ├─ config/                         # 配置：加载/校验/迁移/端点解析
│  ├─ migration/                      # 迁移：状态机 + 文件事务 + 命令注册 + 文本/命令/版本桥
│  └─ mixin/                          # 5 个 Mixin（见下）
├─ src/test/java/                     # 11 个测试类、42 个 @Test（纯逻辑，不依赖 Minecraft）
├─ versions/1.21.11/build.gradle      # 旧线：fabric-loom-remap，编译目标 1.16.5，release 8
├─ versions/26.2/build.gradle         # 新线：fabric-loom（不重映射），release 25
├─ gradle/version-module.gradle       # 两线共享配置（sourceSets 指向根 src/、fabric.mod.json 模板化等）
├─ build.gradle                       # 根：collectReleaseJars(Sync) 把两线 JAR 收敛到 build/libs
├─ scripts/smoke-test-server.{ps1,sh} # 真实服务端启动冒烟测试
├─ MEMORY.md                          # 本文件
└─ .github/workflows/                 # build.yml（JDK 25 构建 + 14 版本启动矩阵）、release.yml
```

### 构建产物约定

| JAR | 覆盖 | 字节码 | 重映射 |
| --- | --- | --- | --- |
| `compat_login-1.21.11-<ver>.jar` | MC `>=1.16 <=1.21.11`，Java `>=8` | Java 8（有 `verifyJava8Bytecode` 任务把关） | Loom 重映射为 intermediary（如 `PlayerList→class_3324`、`canPlayerLogin→method_14586`） |
| `compat_login-26.2-<ver>.jar` | MC `>=26.1 <=26.2`，Java `>=25` | Java 25 | 不做重映射（26.x 官方名） |

- 模块名 = 该线最高支持版本；JAR 名 = `compat_login-<模块名>-<模组版本>`（`archivesName` 自动生成，不手写）。
- `collectReleaseJars` 是 `Sync` 任务，每次构建自动清除 `build/libs` 里的陈旧产物，只收集本次 `project.version` 的 JAR，并按 `(rootProject.name-模块名-版本.jar)` 精确校验文件名集合（模块 `build/libs` 会长期堆积历史版本，1.1.1 轮曾因此“收集到 4 个”而失败）。
- `-Dfile.encoding=COMPAT` 解决中文路径下 Gradle @argfile 的 GBK/UTF-8 乱码（**需 JDK 18+ 运行 Gradle**，见第 4 节）。
- 新线编译时校验运行 Gradle 的 JDK ≥ 25，否则直接失败并给出明确提示。
- 本地沙箱内跑 Gradle：普通 workspace 模式只有在 wrapper 发行版已解压、守护进程已预热后才能跑通（首次会因写 `%USERPROFILE%\.gradle` 被拒）。

### Mixin 清单

| Mixin | 目标 | 作用 |
| --- | --- | --- |
| `YggdrasilMinecraftSessionServiceMixin` | authlib `hasJoinedServer`（3 个签名，`@Group min=1 max=1`，`remap=false`） | 鉴权替换 |
| `CommandsMixin` | `Commands.<init>` RETURN | 注册 `/account migrate` |
| `MinecraftServerMixin` | `tickServer` TAIL | 每 250ms 推进迁移状态机 |
| `PlayerListLoginMixin` | `canPlayerLogin` HEAD（`@Coerce` 抗签名漂移；1.21.9+ 第二参数为 `NameAndId`，读取 UUID 失败即放行） | 迁移期间锁定目标 UUID 登录 |
| `PlayerListAccessor` | `PlayerList.stats/advancements` | 迁移后清内存缓存 |

### 迁移状态机（`MigrationManager`）

```
awaiting_confirmation --确认码正确--> waiting_for_disconnect --目标离线--> migrating --完成--> completed(移除)
      | 过期(15/5min)                      | 30s 超时未离线 / 源 UUID 上线 --> 回滚移除
```

- 会话持久化在 `config/compat_login-migrations.json`（原子写）；加载时复检名字格式与 UUID 合法性。
- 文件事务在 `MigrationFileService`：备份 → staged 改写 → 原子替换目标 → 删源；失败按备份回滚；备份存 `config/compat_login/migration-backups/`，带 manifest。
- 关键原则：**目标 UUID 只有在"确认码正确 + 源离线 + 目标已离线"三者同时成立后才动数据**；登录锁只锁目标（`waiting_for_disconnect`/`migrating` 两态），名字歧义（同名多 UUID）直接拒绝并要求用 UUID。

### 版本桥（`migration/VersionBridge.java`，本轮新增）

支撑范围横跨三套运行时世界，全部惰性解析 + 缓存句柄 + 失败闭合（fail-closed，一次性报错）：

| 能力 | 1.16–1.21.4（旧 API） | 1.21.5–1.21.11（新 API，intermediary） | 26.x（新 API，官方名） |
| --- | --- | --- | --- |
| 命令权限 ≥3 | 接口 `SharedSuggestionProvider.hasPermission(int)`（`class_2172`/`method_9259`，运行时经 MappingResolver 解析后反射调用） | `CommandSourceStack.permissions` **字段**（`class_2168`/`field_63437`）→ `LevelBasedPermissionSet.level()`（`class_12086`/`method_75009`）→ `PermissionLevel.id()`（`class_12094`/`method_75026`）≥ 3；`PermissionSet.ALL_PERMISSIONS`（`class_12096`/`field_63208`）按对象同一性直接放行 | 同名官方 API 反射：`permissions()` **方法** → `level()` → `id()` |
| 系统消息 | `displayClientMessage(Component, boolean)`（`class_1657`/`method_7353`，1.16–1.18） | `ServerPlayer.sendSystemMessage(Component)`（`class_3222`/`method_64398`，1.19+） | 官方名 `sendSystemMessage` |
| 踢人 | `player.connection.disconnect(Component)`——两线同名字段/方法，**直接编译调用，无需桥**（旧线映射为 `field_13987`/`method_14367`） | 同左 | 同左（继承自 `ServerCommonPacketListenerImpl`） |

- 非玩家命令源（console/RCON）不走权限桥，沿用名字白名单（`getTextName()` 为 "Server"/"Rcon"）——与 0.3.x 以来的行为一致且零 I/O。
- 关键事实（已用 1.16.5 / 1.21.11 / 26.2 三份映射逐一核实）：`CommandSourceStack.hasPermission(int)` 在 1.21.5+ 被移除；1.21.11 的 `PermissionSet` 两个 `(Permission)→Z` 方法在官方映射中**未命名**（`method_75033`/`method_75036`），所以桥刻意走 `level()→id()` 路线避开歧义；`PlayerList.disconnect(ServerPlayer, Component)` 在 1.16.5 与 26.2 上都不存在；op 列表 key 从 `GameProfile` 漂移为 26.2 的 `NameAndId`（`getOps()` 本身两线都在）。

## 3. 历次改动

### 3.1 上一轮（1.1.0 构建与发布轮，已通过 5 个真实版本实测）

- 双线构建体系落地：旧线/新线两个模块 + `gradle/version-module.gradle` 共享配置 + `collectReleaseJars`(Sync)；Loom 固定到稳定版 `1.17.20`；JAR 名取模块目录名。
- 修掉最危险缺陷：登录锁提示文本不再放 Mixin 的 `static final` 字段（曾导致文本桥失配时 `ExceptionInInitializerError` 崩掉整台服务器），改为首次拒绝登录时惰性构造、失败记日志并放行。
- 修掉同名解析歧义：`PlayerIdentityResolver` 收集在线玩家 + `usercache.json` 全部同名匹配，多于一个即拒绝并列出候选 UUID（配 6 个单元测试）。
- 迁移会话重载时复检玩家名格式（名字会拼进服务器命令）；`User-Agent` 改为读取实际模组版本。
- 工具链：`-Dfile.encoding=COMPAT` 修中文路径 @argfile 乱码；smoke 脚本改共享读日志、超时可配（默认 300s）、`-JavaExecutable` 指定 JDK、新增"模组是否真正初始化"断言；CI 改 JDK 25 构建 + 按版本线分发 JAR 到各启动测试；中英文 README 全面同步。
- 清理：删除 0.3.1 旧 JAR、`run/`、模块 `build/`、测试世界、11 个 %TEMP% 测试目录、924MB 过期 Gradle 缓存。

### 3.2 本轮（权限口径 + 命令桥语义修正）

目标 = 评估清单第 2 项：`canBegin` 零 I/O 化 + `ServerCommandBridge.execute` 假成功语义修正。落地过程中用 1.16.5/1.21.11/26.2 三份映射 + javap 发现了共享源码无法直接调用的 API 漂移，最终引入 `VersionBridge`。

- **`MigrationManager.canBegin` 不再读 `ops.json`**：非玩家源按名字白名单（Server/Rcon），玩家源走 `VersionBridge.hasCommandPermission`（服务端自身权限模型，玩家 op level 在登录时已缓存，零 I/O、无"ops.json 读失败全员被拒"的失败模式）。Brigadier 在每次进服发送命令树时求值 `requires`，因此该谓词必须廉价无副作用。
- **踢人改直连 API**：`confirm` 中 `kick` 命令替换为 `player.connection.disconnect(Component)`（`ServerCommandBridge.disconnect`）；删除"踢人失败就取消迁移"的死分支（`execute` 在 1.19+ 恒真），成功与否交给 tick 状态机的 30s deadline（异步断开，本来就无法同步判定）。
- **消息改直发**：新增 `ServerCommandBridge.tell(ServerPlayer, Component)`，经 `VersionBridge` 调用运行时可用版 `sendSystemMessage`/`displayClientMessage`，不再拼 `/tellraw <名字>`——顺带消除了"玩家名含特殊字符时命令解析失败"的隐患，且消息投递失败告警重新变得真实。
- **`ServerCommandBridge.execute` 仅剩 pardon 用途**（清理旧版遗留临时封禁），javadoc 注明 1.19+ 返回值不反映成败、属尽力而为；`releaseLegacyBan` 的成功/失败分支措辞相应改为 "Issued pardon … / reported failure …"。
- **新增 `migration/VersionBridge.java`**：权限桥（三路：旧接口法 → 新 API 官方名 → 新 API intermediary）与消息桥（三路：官方名 → intermediary `sendSystemMessage` → intermediary `displayClientMessage`），全部惰性解析、缓存、fail-closed、一次性报错，风格与 `MinecraftTextBridge` 一致。
- 移除 `MigrationManager` 中 ops.json 解析相关的 Gson import 与 `string/integer` 辅助方法。
- **验证**：`gradlew build` 全绿（两线编译、32 个单元测试、`verifyJava8Bytecode`、`collectReleaseJars` 均通过）；已抽查两个 JAR 字节码：旧线 `ServerCommandBridge` 正确引用 `field_13987`/`method_14367`，`VersionBridge` 常量池含全部预期 intermediary 名；新线保留官方名。**未做**真实服务器运行验证（沙箱限制 + smoke 不覆盖这些路径，见第 4 节第 9 条）。

### 3.3 本轮（鉴权并行化）

- **`MultiAuthService.hasJoinedServer` 改为并行查询**：所有启用 provider 同时查询，首个返回有效档案的成功即返回，其余取消（残余查询在池线程里自然超时丢弃）。超时预算从"N 个 provider 叠加"变为"重叠"：最坏总等待 = 单个 provider 的最长预算，不再 13s×N。安全依据：Yggdrasil `hasJoined` 的 `serverId` 是一次性的，至多一个身份源能返回档案，并行无认领竞态。
- **共享 4 线程 daemon 池**（`AUTH_EXECUTOR`，线程名 "Compat Login Auth"）；单 provider 也走同一路径（行为统一、总超时同样生效）；意外 RuntimeException 经 `ExecutionException` 重新抛出，保持原有"未预期失败向上传播"语义；失败聚合、30s 节流告警、`AuthenticationServiceUnavailableException` 文案与原先一致。
- **新增配置 `authentication.overallTimeoutSeconds`**（默认 13 = 5+8）：一次登录验证的总等待上限；校验范围 1–120 且不得小于 `connectTimeoutSeconds`；超时且仍有查询在途时抛"did not finish within overallTimeoutSeconds"，不误报"用户不存在"。
- **测试**：`MultiAuthServiceTest` 扩到 6 个用例（HttpServer 配并发 executor），覆盖：并行不等待最慢源（计时断言）、单 provider、全拒绝、部分失败聚合、overall 超时截断。
- **文档**：中英文 README 的默认配置 JSON、配置项表、查询语义段落、排错清单全部同步。
- **方案 C（熔断跳过失效源）评估后不做**：并行之后"降优先级"无意义，而"临时跳过失效源"会让该源恢复后的一段时间内其玩家无法登录（fail-open 语义倒退），风险大于收益，记于此备查。
- **方案 B（登录鉴权整体异步化）**：见第 4 节"可选功能（未来工作）"。

### 3.4 本轮（1.21.9+ 登录崩溃：`canPlayerLogin` 身份对象漂移为 `NameAndId`）

线上现象：1.21.11 服务器上玩家（LittleSkin 经 authlib-injector）鉴权成功后**立刻掉线**，日志依次是
`IllegalStateException: Cannot read the player UUID from an authlib GameProfile` →
`Caused by: NoSuchMethodException: net.minecraft.class_11560.id()` →
`Sending unknown packet 'clientbound/minecraft:disconnect'` → `lost connection: Internal server error`。

- **根因**：`PlayerListLoginMixin` 注入的 `PlayerList.canPlayerLogin(SocketAddress, …)` 第二个参数在 **1.21.9 起由 authlib `GameProfile` 换成 `NameAndId` 记录**（只有 `id()`/`name()`）。mixin 用 `@Coerce Object` 编译期无感，`AuthlibProfileAdapter` 又只探测 `getId()`/`getName()`（`getId` 失败后回落到 `id` 也失败），于是抛异常打断登录线程；`disconnect` 包发送失败只是连带症状。
- **证据（全部本地实测，未依赖网络猜测）**：下载 fabric intermediary 1.21.8/1.21.9/1.21.10/1.21.11 四份映射比对——`method_14586` 的参数在 1.21.8 是 `Lcom/mojang/authlib/GameProfile;`，1.21.9+ 变成 `Lnet/minecraft/class_11560;`，而 `class_11560` 的成员是 `Codec field_62419`、`UUID comp_4422`、`String comp_4423`，访问器为 `comp_4422(): UUID` 与 `comp_4423(): String`（即 `NameAndId` 记录）；`javap` 扫遍 gradle 缓存里全部 18 个 authlib：`2.1.28–6.0.58` 的 `GameProfile` 只有 `getId()`，`7.0.61/7.0.63/9.0.75` 只有 `id()`，`UserIdentity` 在所有版本都不存在。因此该缺陷影响 **1.21.9/1.21.10/1.21.11 全线**，与是否加载 authlib-injector 无关。
- **修复 A（`AuthlibProfileAdapter`）**：`invokeAccessor` 改为候选名数组，**记录式访问器优先**——`readProfileId`：`id()` → `getId()`；`readProfileName`：`name()` → `getName()`；全部失败时抛最后一个 `NoSuchMethodException`（保留原有报错可读性，正是它暴露出 `class_11560.id()`）。公共 API 名不变（`readProfileId`/`readProfileName` 语义是"读身份对象"），三处调用点零改动；`getProperties`/`properties` 的既有用法不变。
- **修复 B（`PlayerListLoginMixin`）**：新增 `compatLogin$readIdentity`，`identity == null` 或读取抛 `RuntimeException`/`LinkageError` 时**一次性记日志并放行**（`compatLogin$identityReadFailureReported` 节流），迁移锁只能失效、不能再打断连接；javadoc 记录签名漂移事实。
- **修复 C（构建）**：`collectReleaseJars` 原先收 `compat_login-*.jar`，模块 `build/libs` 里的历史版本（`...-1.1.0.jar`）会被一起同步进根 `build/libs`，导致"Expected 2 but collected 4"；现改为按 `project.version` 过滤 + 精确校验 `(rootProject.name-模块名-版本.jar)` 集合。
- **测试**：`AuthlibProfileAdapterTest` 从 2 个用例扩到 6 个——新增 `readsTheRecordShapeUsedByNameAndIdSinceMinecraft1219`（夹具只暴露 `id()`/`name()`，即本次崩溃形状的直接回归）、`readsTheRealNameAndIdOfThisMinecraftLine`（反射取真实 `NameAndId`：新线 dev classpath 上有 authlib 的 `...response.NameAndId` 并实际执行；旧线因编译目标是 1.16.5、游戏类无法链接而 `assumeTrue` 跳过，由夹具用例 + 字节码抽查兜底）、`readsTheJavaBeanGameProfileShapeUsedByAuthlib2To6`、`reportsTheUnsupportedShapeAsAnAuthlibProfileFailure`（锁定 `IllegalStateException` + `NoSuchMethodException` cause 契约）。夹具用普通嵌套静态类而非 `record`，避免两条线 `testJavaRelease` 8/25 的编译差异。
  - 试过给 test 源集补回 minecraft 依赖让旧线也能跑真实类，**失败且已回退**：Loom 会按该模块的编译目标（1.16.5）解析 `com.mojang:minecraft:<module>`，把 1.16.5 的库灌进测试运行时（`datafixerupper 4.0.26` 等），反而破坏模组自身 classpath。结论：测试保持纯逻辑，游戏类型只在 dev classpath 恰好可链接时才用。
- **验证**：`gradlew build` 全绿（两线编译、两线各 40 个测试 0 失败、`verifyJava8Bytecode`、`collectReleaseJars` 只收 1.1.1 两个 JAR）；`javap` 抽查两个 JAR：`readProfileId` 常量池顺序 `id`→`getId`、`readProfileName` 为 `name`→`getName`、mixin handler 异常表含 `RuntimeException`/`LinkageError`；新线测试直接消费了真实 authlib `NameAndId` 类。**未做**真机登录验证（沙箱无客户端与线上身份源，见第 4 节第 8 条）。

## 4. 遗留问题与潜在隐患（全部）

### 高优先级

1. **迁移文件事务跑在服务器主线程 tick 内**（`MinecraftServerMixin.tickServer` TAIL → `MigrationManager.tick` → `MigrationFileService.migrate`）：单文件上限 64MB，解压+改写+回压+多文件复制可能秒级卡顿；且 `tick`/`isLoginLocked` 等全是类级 `synchronized`，大迁移期间登录锁检查也会被阻塞。**建议**：tick 只切状态（主线程），文件事务扔专用 worker（`CompletableFuture`），tick 每轮 `isDone()` 轮询结果；会话以不可变快照传给 worker。
2. **崩溃中断迁移的恢复边界**（`MigrationManager.tick` 里 `state=MIGRATING; persist(); migrate()` 的次序）：
   - 崩溃后重启会重跑 `migrate()`，已提交文件因源已删被跳过，多数情况收敛正确；
   - 但若崩溃发生在"全部文件已提交、`state=COMPLETED` 未持久化"窗口，重跑时 `changedAnyPlayerFile=false` 抛异常 → 误报"failed and rolled back"，**数据其实已迁移且未回滚**，误导管理员；
   - 同理，源账号本无任何数据时也会走同一条异常路径，提示语错误。
   - **建议**：区分"无源文件"与"真失败"；迁移前写 journal（列出待提交 artifacts），重启时据此决定续跑或回滚；至少在 README 写明手工从 `migration-backups/` 恢复的办法。

### 中优先级

3. **`compat_login.mixins.json` 的 `compatibilityLevel: JAVA_8` 与 26.2 线 Java 25 字节码不一致**：目前 Mixin 能跑通但属隐性依赖。**建议**：像 `fabric.mod.json` 一样按线模板化（新线 `JAVA_25`；若捆绑 Mixin 不支持该枚举就取最高可用值，smoke 测试兜底）。
4. **反射未缓存**：`ServerCommandBridge.execute` 每次调用都 `getMethods()` 全量扫描（现仅 pardon 用，影响小）；`AuthlibProfileAdapter` 每次登录都反射构造 GameProfile/PropertyMap，读取身份对象的候选名数组也每次都走 `getMethod`（名字很少、失败即抛，量级小）。**建议**：首次解析后缓存 static final，失败降级；缓存必须按运行时类区分（两条线的 `GameProfile` 类不同）。
5. **`StoreFile.schemaVersion` 写入但加载时从不校验**（`MigrationManager.ensureLoaded`）：未来 schema 升级时旧文件会被静默按新结构解析。**建议**：加载时校验版本，不符则拒绝或显式迁移。
6. **`smoke-test-server.sh` 不支持超时参数**（硬编码 `timeout_seconds:-300`），与 PS1 版不对等；本机 1.16.5 实测需 264s，CI 冷启动 + JAR 下载可能吃满 300s（job 级 10 分钟兜底）。**建议**：`.sh` 加第 5 参数并让 CI 显式传 420。
7. **CI 矩阵是抽样而非全版本**：旧线跳过了 1.19.3、1.20.2、1.20.5 等签名断点版本；当前 14 个抽样点均实测通过，但 README 应注明"抽样验证，同线内其他版本用同一 JAR、按需自查"。
8. **运行时交互路径没有自动化验证**：`canBegin`/`tell`/`disconnect`（VersionBridge 三路）与鉴权并行路径都只在真实玩家交互时执行，smoke 测试没有玩家进服，覆盖不到；`VersionBridge` 依赖的 intermediary 名是静态写死的（已对照三份映射人工核实，但未来 MC 若再改权限/消息 API，桥会 fail-closed 并只留一条日志）。**1.21.9 的 `NameAndId` 参数漂移就是同一类隐患的实例**：启动 smoke 全绿，只有真机登录才炸。**建议**：发布前在旧线和新线各手动做一次完整迁移演练（管理员建单 → 目标确认 → 观察 kick/消息/迁移结果），并把"权限 API / 身份对象变更时更新 VersionBridge 与 `AuthlibProfileAdapter`"写进发布检查单。

### 低优先级 / 环境

9. **仓库在 OneDrive 中文路径**（`D:\OneDrive\文档\javaprojects\...`）：Loom 每次构建警告，曾出现 `test-results/binary` 被占用导致 Gradle 删除失败，还频繁遇到编辑文件被外部改动（疑似 OneDrive 同步）触发"file changed since read"。**建议**：整体迁移到本地 ASCII 路径。
10. **`%USERPROFILE%\.gradle` 约 9.3GB**：可在停掉守护进程后清理（尚未动）。
11. 次要代码点：
    - `notifyRequester` 按玩家名在线查找，请求者改名后收不到完成通知（日志仍完整，可接受）。
    - `confirm` 的 catch 会把 `disconnect()` 中文本桥构造失败（理论上仅发生在不受支持版本）也归入"Cannot confirm"提示，属可接受降级。
    - `tick` 中每 250ms 对每个 `waiting_for_disconnect` 会话调用 `findOnline` 线性扫描在线列表，会话数小、无实际影响。
    - `-Dfile.encoding=COMPAT` 需 JDK 18+ 运行 Gradle 守护进程（JEP 400 后才识别该值）；目前 Loom 1.17 本身要求较新 JDK 所以无实际风险，但 `gradle.properties` 注释未写明前提，建议补一句。

### 可选功能（未实现 / 未来工作）

- **登录鉴权整体异步化（方案 B，MultiLogin 路线）**：目前并行化已把登录等待压到单 provider 预算（默认 13s 上限），但登录流程线程仍然同步阻塞这段时间。彻底解法是把 `hasJoinedServer` 的执行挪出登录线程（在 `LoginListener` 等更上层挂钩、登录挂起等待结果），需要新增跨 1.16–26.2 的 Mixin 目标与签名桥，改动面大；列为未来课题。
- `/account migrate list|status`：让管理员查看未决会话。
- 迁移完成后把 `backupPath` 通过命令回复给请求者（目前只进日志）。
- 迁移 `playerdata` 改写之外的关联数据（如实体 NBT 中对玩家 UUID 的引用）不在范围内，README 已建议按 UUID 管理权限/封禁/白名单。

### 已解决（本文件存在之前的记录）

- ~~1.21.9+ 玩家鉴权成功后掉线（`Cannot read the player UUID` / `NoSuchMethodException: class_11560.id()`）~~ → `canPlayerLogin` 参数为 `NameAndId`，身份桥改为记录式访问器优先 + 读取失败放行（3.4）。
- ~~`collectReleaseJars` 把模块 `build/libs` 里的历史版本 JAR 一起收集导致"Expected 2 but collected 4"~~ → 按 `project.version` 过滤 + 精确文件名集合校验（3.4）。
- ~~鉴权 provider 串行查询、超时叠加（13s×N）~~ → 并行查询 + `overallTimeoutSeconds` 总兜底（3.3）。
- ~~登录锁文本 `static final` 引发 `ExceptionInInitializerError`~~ → 惰性构造 + 失败放行（3.1）。
- ~~迁移命令按名字解析取第一个匹配~~ → 全量收集 + 歧义拒绝（3.1）。
- ~~`requires` 每次进服读 `ops.json`~~ → 名字白名单 + `VersionBridge` 权限桥（3.2）。
- ~~`execute` 恒真导致 kick 失败分支/pardon 告警/tell 告警失效~~ → 踢人直连 API、消息直发、pardon 注明尽力而为（3.2）。
- ~~tellraw 拼接玩家名存在特殊字符解析失败隐患~~ → 直发系统消息（3.2）。
- ~~共享源码直接调 `source.hasPermission(3)`/`sendSystemMessage`/`PlayerList.disconnect` 的跨线编译与运行时悬空问题~~ → 全部经 `VersionBridge`/`connection.disconnect` 吸收（3.2）。
- ~~CI 引用已不存在的 `compat_login-universal-*.jar`、JDK 21 编不了 release 25~~ → JDK 25 + 按线分发（3.1）。
- ~~smoke 脚本独占读 `latest.log` 在 Windows 抛 IOException~~ → 共享读（3.1）。
