# Bots4Velo

一个由 Velocity 主插件和可选 Paper 后端伴侣组成的机器人系统。每个机器人都是嵌入代理进程的
Minecraft 无界面客户端，并通过真实协议连接回 Velocity；需要修改无敌、游戏模式、挂机属性或重生点时，
再由后端伴侣执行服务器权威操作。

## 支持版本

| 配置值 | 协议号 | 适配器 | 当前验证状态 |
| --- | ---: | --- | --- |
| `1.16.5` | 754 | Legacy Login/Play | 单元测试及 Velocity 4.0.0 → Paper 1.16.5 实际连接 |
| `1.21.11` | 774 | Modern Login/Configuration/Play | 单元测试及 Velocity 4.0.0 → Paper 1.21.11 实际连接 |
| `26.1.2` | 775 | Modern Login/Configuration/Play | 单元测试及 Velocity 4.0.0 → Paper 26.1.2 实际连接 |
| `26.2` | 776 | Modern Login/Configuration/Play | 单元测试及 Velocity 4.0.0 → Paper 26.2 实际连接 |

配置默认使用 `protocol-version: "AUTO"`。每个机器人优先 ping 自己的
`protocol-detection-server`；该字段为空时使用 `target-server`。只有两者都为空时才会向
`proxy.address:proxy.port` 发起标准 Status 握手。这样即使 Velocity 对外宣告最新协议，机器人仍会
按实际目标后端选择适配器。`/vbot status` 与 `/vbot monitor` 会显示检测来源，例如
`backend:survival` 或 `proxy-status`。也可以把该字段固定为上表中的任一配置值；`26.1` 也会被识别为
`26.1.2`。

## 当前实现

- 多机器人配置、运行时创建/删除、错峰启动、启停、即时重连、状态和命令控制。
- Offline UUID 固定生成，支持 Velocity forced-host 握手地址。
- Login、Configuration、Play 状态跟踪；协议库处理压缩、加密、Known Packs 和 KeepAlive。
- Client Information、Cookie 空响应、Teleport Confirm 与位置回执。
- 强制资源包门禁所需的 ACCEPTED、DOWNLOADED、SUCCESSFULLY_LOADED 状态序列。
- 每次资源包请求与回执均写入机器人日志，便于定位 CraftEngine 门禁或切服后的重复下发。
- AuthMe/AuthMeReloaded、VeloAuth 类聊天认证的登录/注册提示匹配与登录优先回退流程；日志只记录
  命令类型，不泄露密码。
- `protocol-version: AUTO` 通过目标后端 ping 或代理 Status 识别 1.16.5、1.21.11、26.1.2、26.2。
- 目标服切换确认与持续重试；确认二次 PLAY 后才会执行传送等后续命令。
- 能识别 VeloAuth 已在成功消息前后自动完成的切服，避免在目标服持续重复 `/server`。
- `/vbot server` 通过 Velocity 连接 API 可靠切服，并取消旧的静态切服重试，避免机器人又被拉回。
- `/vbot movehere` 接受批量选择器，自动识别执行者所在后端，先把匹配的机器人跨服，再逐个传送到执行者身边。
- TAB 玩家列表/计分板包可由协议客户端正常接收；命令自身也提供机器人、服务器和帮助页补全。
- 死亡重生和指数退避断线重连；有限重试预算只会在认证完成并稳定保持 PLAY 后重置，所有首次连接及
  自动重连共享全局连接间隔。
- `runtime.maximum-bots` 是静态机器人和运行时机器人合计的硬上限。
- `/vbot status` 提供当前协议/状态以及本进程内累计 PLAY、非人工断线、资源包成功次数和时间戳，
  用于稳定性观察与故障审计。
- `/vbot list` 支持选择器、状态、后端、失败状态筛选和固定分页；`/vbot queue` 显示待执行启动/
  重连的队列顺序与预计执行时间（ETA）。
- `/vbot hold` 可用原因和 TTL 维护锁批量停止机器人并阻止手动或自动重新上线；`/vbot resume`
  只解除维护锁，之后由管理员明确启动。维护锁在安全重载时保留。
- `/vbot reload --check` 会严格验证候选配置并输出脱敏差异，不替换配置或影响正在运行的机器人。
- 默认英文、内置简体中文及自定义语言目录；可用 `/vbot language` 安全热切换，不会重启机器人。
- 五页权限感知帮助界面，默认英文、可选中文；命令可点击填入、翻页可点击执行，并提供悬停说明和权限节点。
- 协议级绝对位置和视角控制，并可输出单个或全部机器人的 JSON 监控快照。
- 通过带 HMAC 签名和防重放校验的 Paper 伴侣设置无敌、游戏模式、挂机属性与重生点，并支持
  手动重生和一键恢复生命/饥饿/着火状态。

`ACCEPT_WITHOUT_DOWNLOAD` 只模拟客户端状态，不会下载或校验资源包。若 CraftEngine
还使用自定义插件消息或服务端回调校验，必须在真实测试网络中捕获断线原因并补充对应处理。

自动检测只负责选择协议适配器；它不会把不同协议的数据包互相转换。四个版本分别使用隔离且
重定位后的客户端 codec，避免依赖和包号相互污染。Velocity 或后端仍须真正接受机器人所用协议，
否则需要在网络中部署 ViaVersion 等协议转换方案。

## 构建

在仓库根目录执行：

```powershell
.\gradlew.bat clean check shadowJar
```

默认会在 `build/libs` 生成两个用途严格区分的产物：

- `bots4velo-3.0.0.jar`：安装到 Velocity 的 `plugins` 目录；
- `bots4velo-paper-3.0.0.jar`：安装到网络中每一个 Paper 后端的 `plugins` 目录。

也可以通过 `-PpluginVersion=<version>` 同时覆盖两个产物的版本号。
`check` 还会从最终阴影 JAR 中加载一次已重定位的协议客户端，避免只验证未打包的开发类路径。
Velocity 主插件首次启动会生成 `plugins/bots4velo/config.yml`；Paper 伴侣首次启动会生成
`plugins/Bots4VeloPaper/config.yml`。不需要玩家状态控制时可以只安装 Velocity JAR，并保持
`runtime.backend-control.enabled: false`。

Velocity JAR 同时包含四套隔离协议栈，因此文件会明显大于 Paper 伴侣和普通代理插件；这是避免
不同版本 MCProtocolLib 及其传递依赖冲突的设计结果。两个 JAR 不能互换安装。

## GitHub 大版本发布

每次大版本会自动执行测试、构建两个阴影 JAR、上传 GitHub Actions artifact，并创建同名
GitHub Release（附带两个 JAR、各自的 SHA-256 和自动生成的 Release Notes）。仅 `vX.0.0` 形式的标签会触发，
例如 `v3.0.0`；次版本与修订版本标签（例如 `v3.1.0`、`v3.0.1`）不会触发发布。普通 commit 和 Pull Request
由独立的 `Build and test` 工作流执行根项目及全部子项目的 `check` 与阴影 JAR 构建；大版本发布也会先复用这套完整质量门，
包括四个协议目标的临时网络集成，全部成功后才创建 Release。

```powershell
git tag v3.0.0
git push origin v3.0.0
```

发布构建会将标签去掉前缀 `v` 后作为插件版本，例如 `v3.0.0` 生成
`bots4velo-3.0.0.jar`、`bots4velo-paper-3.0.0.jar` 及各自的 `.sha256` 校验文件。每一个大版本都应先完成
测试、更新文档与配置示例，再 commit、push、创建并推送对应标签。

`v3.0.0` 符合大版本标签规则，会直接触发 `build-major-release.yml`；工作流成功后会自动创建同名
GitHub Release，并上传两个 JAR 与两个校验文件，无需再手动创建 Release。

## 集成验证

普通 CI 会在 `1.16.5`、`1.21.11`、`26.1.2` 与 `26.2` 四个目标上执行协议映射回归，并为每个
目标下载并启动一个临时 Velocity + AuthMe 登录服 + AFK Paper 网络。现代登录服还会固定安装来自
Modrinth 的 `TejasLamba2006/AuthMeUI 1.3.4`，校验其 SHA-256，并用自定义规则复选框验证
`RULES → REGISTER → PLAY`；1.16.5 保留聊天命令注册回退。两个 Paper 后端都会安装伴侣 JAR 并使用与
Velocity 相同的测试密钥；CI 除验证登录、PLAY、跨服与后端/代理重启恢复外，还会等待签名策略 ACK，
并确认后端恢复和代理重启后重新应用玩家状态。首次连接使用 AuthMeUI pre-join 完成规则与注册；随后会
安全重启登录服并验证 post-join `LOGIN → PLAY`，最后再切回 pre-join，确认有效 AuthMe session 会跳过
登录 Dialog、在进入 PLAY 后由 AuthMe 的 session-success 消息放行，并且不会重复提交密码。完整日志会作为
CI artifact 上传。测试会先确认 AuthMe 与 AuthMeUI 都真正启用；依赖下载或 bridge 连接失败不会被误判为
Dialog 兼容问题。
`scripts/ci/run-network-integration.sh` 也可在
Linux 上单独执行。现有本地隔离网络启动后还可执行：

```powershell
.\scripts\verify-local-integration.ps1 -RequireBotsInPlay
```

该检查确认三个监听端口、已部署的插件版本、可选 SHA-256 文件、Velocity 加载记录，以及至少一个机器人进入
`PLAY`。CI 网络会覆盖后端重启、代理断线/重启及重连后的重复资源包下发；24 小时稳定性巡检与特意不完成
认证流程的超时场景仍作为独立运行任务执行，避免把一次普通 commit 的快速检查伪装成长时间稳定性证明。

认证配置可用 `auth.timeout-ms`（默认 `30000`）限定认证流程；到期后机器人停止重试，并记录
`AUTH_TIMEOUT` 事件及明确的失败原因。设置为 `0` 可禁用该上限。

`runtime.reconnect.maximum-attempts` 为 `0` 时持续重试，设为正整数时表示一次连续故障最多允许的自动
重连次数。连接短暂进入 PLAY 不再清空这项有限预算：机器人必须完成认证，并连续保持 PLAY 达到
`runtime.reconnect.stable-reset-seconds` 后才重置计数。该稳定窗口默认 `30` 秒；设为 `0` 时会在 PLAY
与认证两个条件均满足后立即重置。这样反复“刚上线就掉线”的连接最终会进入 `FAILED`，而不是无限续期。

如需 Prometheus，设置 `runtime.prometheus-port` 为非零端口（默认绑定 `127.0.0.1`），即可读取
`/metrics`：每个机器人会输出在线状态、重连次数、断线数、资源包确认数、在线时长及失败分类。

## 首次联调

1. 保持示例机器人的 `enabled: false`，配置本地监听端口、virtual-host、账号密码和目标服。
2. 启动 Velocity，使用控制台 `/vbot start IronFarm01`。
3. 用 `/vbot status IronFarm01` 检查自动选择的协议，以及 LOGIN、CONFIGURATION、PLAY 或断线原因。
4. 在登录服确认注册/登录，再确认机器人进入 Leaf、执行挂机点传送并加载目标区块。
5. 单机器人验证通过后再设为 `enabled: true`，随后逐个增加机器人。

管理权限为 `bots4velo.admin`。命令包括：

```text
/vbot help [1|2|3|4|5]
/vbot list [id|selector] [--state <state>] [--server <server>] [--failed] [--page <n>]
/vbot queue [id|selector] [--state <state>] [--server <server>] [--failed] [--page <n>]
/vbot status <id>
/vbot monitor [id]
/vbot doctor [id|selector]
/vbot servers
/vbot server <id|selector> <server>
/vbot movehere <id|selector>
/vbot position <id>
/vbot move <id> <x> <y> <z>
/vbot look <id> <yaw> <pitch>
/vbot create <id> <username> <secret:name|env:NAME|-> [target-server|-]
/vbot remove <id>
/vbot start <id|selector>
/vbot stop <id|selector>
/vbot reconnect <id|selector>
/vbot hold <id|selector> [--ttl <number><s|m|h|d>] [reason...]
/vbot resume <id|selector>
/vbot command <id|selector> <command...>
/vbot behavior <id|selector> <start|pause|status>
/vbot afk <id|selector> status
/vbot afk <id|selector> preset <safe|farm|normal>
/vbot afk <id|selector> set <sleep-ignored|affects-spawning|pickup|collision> <on|off|keep>
/vbot afk <id|selector> unmanage
/vbot recover <id|selector>
/vbot invulnerable <id|selector> <on|off|keep>
/vbot gamemode <id|selector> <survival|creative|adventure|spectator|unchanged>
/vbot spawnpoint <id|selector> <current|worldspawn|clear>
/vbot spawnpoint <id|selector> set <world> <x> <y> <z> [yaw]
/vbot respawn <id|selector>
/vbot language [locale]
/vbot reload [--check]
```

### 分组、选择器与权限

机器人可在配置中使用 `groups` 和 `tags`。命令的 `<id|selector>` 参数接受单个 ID、`all`、
`@group:farm`、`@tag:backup`、简写 `@farm`（匹配组或标签）以及 `@server:lobby`。例如：

```text
/vbot start all
/vbot stop @farm
/vbot server @lobby survival
/vbot reconnect @server:lobby
```

`list` 与 `queue` 可以组合这些选择器和筛选项；结果按机器人 ID 稳定排序，每页最多 8 项。`--state`
接受 `STOPPED`、`CONNECTING`、`LOGIN`、`CONFIGURATION`、`PLAY`、`RECONNECT_WAIT`、`STOPPING` 或
`FAILED`，`--server` 按当前后端筛选，`--failed` 只保留失败状态或已有失败分类的机器人。翻页按钮会保留
原选择器和全部筛选条件。常用示例：

```text
/vbot list @farm --state PLAY --server survival --page 2
/vbot list all --failed
/vbot queue @server:lobby --page 1
```

当 TAB 已安装时，机器人可设置 `display-name` 和 `tab-group`。Bots4Velo 会在机器人上线后以 TAB 的
临时 API 应用它们，并在重连后自动恢复；可在 TAB 的格式规则中使用 `%bots4velo_tab_group%` 占位符。
这些覆盖不会写入或修改 TAB 的 `users.yml` / `groups.yml`。

权限默认采用最小职责划分：`bots4velo.view` 用于查看、`bots4velo.control` 用于连接与移动控制、
`bots4velo.create` 用于创建/删除运行时机器人、`bots4velo.reload` 用于重载；
`bots4velo.admin` 拥有全部权限。

### 运维控制

维护锁适合后端升级、账号排查和临时停机。`hold` 会立即停止匹配的机器人、取消尚未执行的启动，随后阻止
手动 `start`/`reconnect` 以及计划任务、presence 保活和断线自动重连再次将它们上线。可写自由文本原因；
`--ttl` 接受正整数加 `s`、`m`、`h` 或 `d`，最长 30 天，不写 TTL 时保持到手动解除。安全
`/vbot reload` 会把仍有效的维护锁和剩余到期时间带入新管理器。

```text
/vbot hold @farm --ttl 30m farm backend maintenance
/vbot hold @server:lobby investigating authentication
/vbot resume @farm
/vbot start @farm
```

`resume` 只解除维护锁，不会立即启动机器人；这样管理员可以先分批解锁，再通过 `start` 控制上线节奏。
`/vbot list` 和 `/vbot status` 会显示维护锁及原因。`/vbot queue` 仅列出确实等待执行的启动和重连，包含
顺序、原因与 ETA；永久维护锁不会伪装成队列任务。

修改配置前可先执行只读预检：

```text
/vbot reload --check
/vbot reload
```

`--check` 会使用与正式重载相同的严格解析和验证流程，但不会替换线上配置或停止机器人。输出只含
代理/运行时变更类别、机器人 ID 和发生变化的字段名；密码、后端共享密钥及其他机密值不会出现在差异中。
确认预览后再执行不带参数的 `/vbot reload` 应用配置。正式重载会先暂停替代管理器的全部激活，关闭旧连接，
并轮询 Velocity 在线玩家表；只有所有旧机器人用户名都已注销后才启动新机器人，从而避免零延迟配置下的
同名重复登录。交接期间 `start`/`reconnect` 会给出明确提示，`/vbot doctor` 与监控 JSON 也会显示暂停状态。

### Paper 后端玩家状态与挂机控制

无敌、游戏模式和个人重生点均由 Paper 服务端决定，客户端数据包无法可靠修改这些属性。要使用相关
命令，必须把 `bots4velo-paper-<version>.jar` 安装到每一个 Paper 后端，并让 Velocity 与所有伴侣使用
完全相同的共享密钥。密钥按 UTF-8 解码后至少需要 32 字节；也可使用 `base64:<内容>`，但 Base64 解码后
仍必须达到 32 字节。

推荐把密钥放进 Velocity 进程环境变量：

```yaml
runtime:
  backend-control:
    enabled: true
    secret: ""
    secret-env: "BOTS4VELO_BACKEND_SECRET"
    timeout-ms: 3000
```

Paper 伴侣默认读取同名环境变量，也可在每一个 Paper 的
`plugins/Bots4VeloPaper/config.yml` 写入同一值作为回退：

```yaml
shared-secret-env: "BOTS4VELO_BACKEND_SECRET"
shared-secret: "replace-with-the-same-secret-of-at-least-32-bytes"
```

环境变量存在且非空时严格优先于 `shared-secret`；不要把真实密钥提交到 Git。修改密钥后必须同步重启
Velocity 和全部 Paper 后端，并保持主机时间同步，否则签名 ACK 会因时间窗校验失败。

可通过模板或单个机器人声明登录后自动恢复的期望状态：

```yaml
bots:
  Farm01:
    player-state:
      afk-preset: FARM # NONE / SAFE / FARM / NORMAL
      # 可选覆盖；只取消需要单独调整的字段注释
      # invulnerable: ENABLED # KEEP / ENABLED / DISABLED
      # sleep-ignored: ENABLED # KEEP / ENABLED / DISABLED
      # affects-spawning: ENABLED
      # pickup-items: DISABLED
      # collidable: DISABLED
      game-mode: SURVIVAL # KEEP / SURVIVAL / CREATIVE / ADVENTURE / SPECTATOR
      apply-delay-ms: 1000
      respawn-point:
        mode: FIXED # UNCHANGED / CURRENT / FIXED / WORLD_SPAWN / CLEAR
        world: world
        x: 100.5
        y: 64
        z: -20.5
        yaw: 0
```

`CURRENT` 使用机器人应用策略时的位置，`WORLD_SPAWN` 使用世界出生点，`CLEAR` 清除个人重生点，
`FIXED` 使用给定世界与坐标。期望状态会在认证完成、切换后端及重连后重新发送；Paper 伴侣还会在
同一在线会话的换世界和死亡重生后重放已缓存策略，并在退出时清除缓存。运行时命令接受所有现有批量选择器并使用
`bots4velo.control` 权限：

- `afk ... preset safe` 忽略睡眠、开启无敌并关闭拾取和碰撞；`farm` 另外让机器人参与生物生成计算；
  `normal` 恢复普通玩家语义。单项 `set` 可覆盖睡眠、生成、拾取或碰撞；`unmanage` 停止管理这五项，
  但保留它们当时的服务端值，需要恢复普通值时应先使用 `preset normal`；
- `afk ... status` 只读取真实后端状态，使用 `bots4velo.view` 权限，不会重新应用配置；
- `recover` 在需要时先重生，再恢复生命、饥饿、饱和度并清除着火和摔落距离，最后重放该机器人
  已缓存的玩家策略；重复执行是安全的；
- `invulnerable on|off|keep` 设置真实服务端 invulnerable 标记；启用时伴侣还会取消该机器人的伤害事件；
- `gamemode` 设置四种原版游戏模式，`unchanged` 保留当前值；
- `spawnpoint current|worldspawn|clear|set ...` 修改个人重生点；
- `respawn` 让已经死亡的机器人立即重生；机器人尚存活时返回成功但不会改变状态。

运行时修改会在当前 Velocity 进程内保留，并在切服或自动重连后重新应用；执行 `/vbot reload` 后以
配置文件为新的基线，不会把临时命令修改写回 YAML。

`SAFE` 不会改变机器人是否参与生物生成；需要农场刷怪时使用 `FARM`。碰撞是 Paper 的服务端属性，
客户端预测、计分板队伍或 TAB 的队伍设置仍可能覆盖最终表现，因此应在实际网络中验证。

所有新配置默认是安全 no-op：后端控制默认关闭，`afk-preset` 为 `NONE`，挂机属性与
`invulnerable`/`game-mode` 为 `KEEP`，重生点为 `UNCHANGED`。开启控制但漏装伴侣、密钥不一致或 ACK
超时时，命令会明确返回失败，不会把“消息已发送”误报为成功。2.6 的扩展操作会先在当前后端连接上进行
能力协商；旧版伴侣仍能执行原有无敌、游戏模式、重生点和重生命令，但新挂机属性与 `recover` 会明确返回
不支持，不会部分应用策略。

安全边界是“仅控制承载该消息的机器人自身”，不是远程控制台：协议校验 HMAC、时间戳、一次性 nonce、
请求与响应关联 ID、当前后端和机器人 UUID；完全相同的重试只回放原 ACK，不会重复执行操作，其余重放与
跨玩家目标均被拒绝。共享密钥或代理/Paper 文件系统失陷
仍会破坏这一边界，因此应限制配置文件权限、隔离后端端口，且不要让普通客户端直接连接 Paper。

插件默认消息为英文。首次启动会生成 `plugins/bots4velo/messages.yml`。具有 `bots4velo.view` 的用户可用
`/vbot language` 查看当前语言和可用语言；具有 `bots4velo.reload` 的管理员可点击语言名称准备命令，或直接执行
`/vbot language zh_CN`、`/vbot language en_US`。切换只原子更新消息文件和内存消息目录，不会重启或重连机器人。
也可以手动修改顶层 `language` 后执行 `/vbot reload`。旧消息文件会自动从新版内置目录补齐缺少的键，同时保留
自定义覆写；YAML 损坏、未知语言或翻译占位符不兼容时不会破坏当前运行状态。命令名称、选择器、状态枚举与 JSON
字段保持英文，便于脚本、文档和跨语言管理员使用一致语法。

`/vbot help` 分为概览与队列、服务器与生命周期、行为与移动、管理与语言、Paper 玩家状态五页。帮助只显示
执行者有权使用的命令；拥有 `bots4velo.view`、`bots4velo.control`、`bots4velo.create`、
`bots4velo.reload` 或 `bots4velo.admin` 中任一角色即可打开根命令和帮助，而不必额外拥有 view 权限。
点击命令会把命令安全填入聊天栏，只有只读翻页和查看操作会直接执行。悬停可查看说明和所需权限。
补全同样按角色过滤：只读用户的 `behavior` 只提示 `status`，控制用户才会看到 `start`、`pause`、
`follow`、`unfollow`；单机器人命令只提示精确 ID，批量命令才提示选择器，`remove` 只提示受管理的 ID，
服务器、语言、状态、行为动作和在线玩家名也会在对应参数位置精确提示。界面默认使用英文；需要中文时执行
`/vbot language zh_CN`，切回英文使用 `/vbot language en_US`。

`/vbot doctor [id|selector]` 会先进行不替换线上机器人的配置验证，再报告后端数量、TAB/
VelocityScoreboardAPI 是否存在、目标服、协议状态、认证凭据、资源包和 Auth UI 计数。`/vbot reload --check`
可在不替换线上对象的前提下预览脱敏差异；正式 `/vbot reload` 会先完整解析并构造替代管理器，解析失败时
保留当前运行中的机器人，成功时也会保留尚未到期的维护锁。

### 模板、机密与基础挂机行为

`templates` 可复用认证、`protocol-version`、组/标签、切服和 `behavior` 设置；机器人按顺序继承
模板后，再由自身字段覆盖。密码必须在 `password`、`password-env`、`password-secret` 中三选一。
`password-env` 从 Velocity 进程环境读取；`password-secret` 从
`plugins/bots4velo/secrets.yml` 的 `passwords.<name>` 读取。首次生成配置会同时生成
`secrets.yml.example`，复制为 `secrets.yml` 后填写密码，并确保该文件不进入版本库。手写主
`config.yml` 仍兼容已有的内联 `password`，但新配置推荐只使用机密或环境变量引用。

```yaml
templates:
  farm-auth:
    groups: [farm]
    tags: [afk]
    protocol-version: "26.2"
    auth:
      mode: AUTO

bots:
  Farm01:
    template: farm-auth
    username: AFK_Farm01
    password-secret: farm01
    behavior:
      mode: FARM       # STATIC, FARM, PATROL, COMMAND, FOLLOW
      enabled: true
      interval-ms: 5000
      movement-radius: 0.0
      yaw-step: 20.0
      random-yaw: false
      jump: false
      swing: false
      sneak: false
      path: []
      server-cycle: [lobby, survival]
      server-cycle-every: 12
```

基础行为会在认证完成后启动，并在切服、断线、重连及重新进入 PLAY 后安全恢复。`STATIC` 保持在线；
`FARM` 支持定时/随机转头、半径往返、越界回到首次记录的安全位置、跳跃、挥手和潜行；`PATROL`
按 `path` 中的绝对坐标循环，未配置路径时按半径往返；`COMMAND` 循环发送 `behavior.commands`；
`server-cycle` 可在指定周期轮换服务器。`FOLLOW` 可由配置的 `follow-player` 自动启动，或在运行时
执行 `/vbot behavior <id|selector> follow <player>`；机器人会自动跨服，并通过目标玩家执行的
`/minecraft:tp` 跟随，因此目标玩家仍需目标后端的传送权限。`unfollow` 会停止该任务。
行为和登录后命令均可使用 `{id}`、`{username}`、`{password}` 与 `{server}` 占位符。

`/vbot behavior <id|selector> status` 是只读查看，使用 `bots4velo.view`；`start`、`pause`、`follow` 与
`unfollow` 会改变运行状态，使用 `bots4velo.control`。这让监控角色可以查看当前模式、是否运行、周期数、
最近动作和跟随目标，而无需获得行为控制能力。

`runtime.schedules` 支持重复执行 `start`、`stop`、`reconnect` 或 `server` 操作；每项指定唯一
`id` 与 `selector`，`server` 操作另需指定 `server`。可使用 `initial-delay-ms` 与 `interval-ms` 做相对
间隔，也可用 `at: "HH:mm"` 和 IANA `timezone`（例如 `Asia/Singapore`）每天在指定时刻上线、离线、重连
或跨服；未指定 `timezone` 时使用 `UTC`。所有操作仍通过已有的连接限速与认证检查。

`/vbot history <id>` 显示最近 50 条连接、认证、切服和断线事件。`/vbot monitor` 还包含
`onlineSeconds`、`failureCategory`、行为状态与事件列表，便于外部采集器判断不稳定重连和认证失败。
认证配置的 `failure-messages` 可匹配密码错误、验证码、2FA 或封禁提示；匹配后机器人进入 `FAILED`
并停止自动重连。定时 `start`/`reconnect` 与 `presence-rules` 也不会越过这个人工介入状态；管理员修复账号后可执行
`/vbot start <id>` 或 `/vbot reconnect <id>` 明确恢复。

其他 Velocity 插件可通过 `Bots4VeloApiProvider.get()` 获得 `Bots4VeloApi`，读取机器人快照、请求
start/stop/reconnect，并注册 `BotEvent` 监听器；API 只在 Bots4Velo 完成初始化时可用。

若设置 `runtime.webhook-url`，Bots4Velo 会异步 POST 包含 `botId`、`type`、`detail` 和 `at` 的 JSON
生命周期事件；留空则完全禁用。Webhook 失败只写 debug 日志，不会影响机器人运行。

`runtime.presence-rules` 用于空服保活和动态缩容：当某后端的人类玩家数不高于
`maximum-humans` 时，插件会从 `selector` 中启动最多 `minimum-bots` 个机器人并切至该后端；
人数上升后则停止这些明确由规则管理的机器人。规则会先 ping 后端；维护期间 ping 失败时等待，后端
恢复后再按 `spawn-interval-ms` 的连接限速分批重新进入。

在 Velocity 已处于 PLAY 的玩家跨后端回退时，代理不一定会转发后端的 configuration-phase Dialog。
若登录服使用 AuthMeUI pre-join，建议同时启用 AuthMe sessions 与
`configuration-phase-respect-authme-sessions: true`；否则后端故障回退可能要等 AuthMeUI 配置阶段超时，
再由完整重连恢复。post-join 模式不受这个配置阶段限制。

`create` 创建的机器人会原子写入 `plugins/bots4velo/managed-bots.yml`，不会重写带注释的
主 `config.yml`，重启后自动恢复；`remove` 只允许删除这类受管理机器人。新建命令不接受内联密码，
第三个参数必须是 `secret:<name>`、`env:<NAME>` 或 `-`。目标服仍可用 `-` 留空。例如：

```text
/vbot create Farm02 AFK_Farm02 secret:farm02 survival
/vbot create Backup01 AFK_Backup01 env:BOTS4VELO_BACKUP_PASSWORD lobby
/vbot create Observer AFK_Observer - -
```

`secret:farm02` 从 `plugins/bots4velo/secrets.yml` 的 `passwords.farm02` 读取；`env:...` 从 Velocity
进程环境读取；`-` 创建无需认证的机器人。保存时 `managed-bots.yml` 只写 `password-secret` 或
`password-env` 引用，不写解析后的密码；无需认证时不写密码字段。引用在创建时必须能够解析为非空值，
否则创建失败且不会留下半写入记录。

旧版 `managed-bots.yml` 中已经存在的内联 `password` 仍可加载并原样保留，避免升级后机器人突然丢失；
但 `/vbot doctor` 会逐个给出迁移警告，新 `/vbot create` 也不会再生成这种记录。迁移步骤：

1. 先把每个旧密码写入 `plugins/bots4velo/secrets.yml` 的 `passwords.<别名>`，或设置 Velocity 进程环境变量；
2. 备份 `managed-bots.yml`，将该机器人的 `password: ...` 替换为 `password-secret: <别名>` 或
   `password-env: <变量名>`，不要同时保留多个密码来源；
3. 执行 `/vbot reload --check`，确认验证成功且脱敏差异符合预期，再执行 `/vbot reload`；
4. 再次执行 `/vbot doctor <id>`，确认旧版明文警告已经消失。

`server` 只在机器人进入 PLAY 且认证完成后执行，并使用 Velocity 注册的后端名称。`movehere` 接受单个
ID 或任一批量选择器，但必须由游戏内玩家执行；插件会先把每个匹配机器人切到执行者当前后端，再让执行者
逐个提交 `/minecraft:tp <机器人> <执行者>`，因此执行者还需要该后端的传送权限。切服期间执行者或对应
机器人离开目标服时，该机器人的操作会安全取消。

`move` 发送正常客户端移动包，不具备服务端传送权限；距离过大、碰撞或越界时，后端可能用位置包
校正机器人。`position` 和 `monitor` 会反映最近一次客户端接受的服务器位置，`pitch` 范围为
`-90..90`。`monitor` 输出单行 JSON，适合控制台采集器或外部健康检查读取。

### AuthMe、AuthMeUI 与 Smart AuthMe Login UI

这里的 **AuthMeUI** 特指 [`TejasLamba2006/AuthMeUI 1.3.4`](https://modrinth.com/plugin/authmeui/version/1.3.4)，
不是 AuthMe 6 自带的 Dialog，也不是名称相近的其他 UI 插件。Bots4Velo 3.0.0 支持它的两种工作方式：

- pre-join：在 CONFIGURATION 阶段依次处理服务器规则、注册或登录 Dialog，完成后才进入 PLAY；
- post-join：进入 PLAY 后处理 AuthMeUI 的登录/注册 Dialog，再继续切服和登录后命令；
- 规则与字段：读取 Dialog 中实际的 custom-click action 和受支持字段 key，给规则布尔字段写入 `true`，
  并按表单实际 key 填写密码、确认密码及已配置的注册邮箱；自定义规则 checkbox key 可以改名，
  AuthMeUI 1.3.4 固定的 `password`/`confirm` 字段会被精确识别；
- 状态诊断：`/vbot status <id>` 的 `Auth UI`、`Presented`、`Submitted` 可区分未识别、已展示但未提交、
  已提交但认证失败三类问题。事件历史会以 `AUTH_UI` 记录 provider 与 `RULES/REGISTER/LOGIN` 阶段，
  不会记录密码。

AuthMeUI 管理命令仍由它自己提供：`/authmeui reload` 使用 `authmeui.admin` 权限；玩家可用
`/authmeui show <login|register>` 手动打开对应测试 Dialog。Bots4Velo 侧不需要新增认证命令，机器人保持
`auth.mode: AUTO` 并配置 `password`、`password-env` 或 `password-secret` 即可。Bots4Velo 与此 CI 配置
默认使用英文消息；自定义 AuthMeUI 标题、正文和字段标签不会要求切换 Bots4Velo 语言。

机器人可显式控制规则接受和邮箱字段；默认允许规则，邮箱默认为空。AuthMeUI/底层 AuthMe 要求邮箱时必须配置：

```yaml
bots:
  LobbyBot:
    auth:
      mode: AUTO
      authmeui:
        accept-rules: true
        registration-email: "bot@example.com"
        # AUTO, CONFIRMATION, EMAIL_OPTIONAL or EMAIL_MANDATORY
        registration-second-argument: AUTO
        # 给 post-join Dialog 留出检测时间；旧版 1.16.5 不受影响
        ui-detection-grace-ms: 3000
```

`registration-second-argument` 仅影响 AuthMeUI 的注册 Dialog，不改变 AuthMe 自带 Dialog：

- `AUTO`（默认）：按收到的字段 key/label 判断第二字段是密码确认还是邮箱；
- `CONFIRMATION`：给第二字段重复填写密码；
- `EMAIL_OPTIONAL`：有 `registration-email` 时填写邮箱，没有时提交空的第二字段；
- `EMAIL_MANDATORY`：必须配置非空 `registration-email`，否则机器人会明确停止并报告原因。

`ui-detection-grace-ms` 默认是 `3000`。现代机器人第一次进入 PLAY 后会先等待这段时间，让 AuthMeUI 的
post-join Dialog 有机会出现；一旦识别到 UI，就不会再抢先发送聊天 `/login` 或 `/register`。设为 `0`
可恢复立即使用聊天认证的旧行为。Dialog 的标题、正文和按钮文字只用于显示/字段语义判断，绝不会被当作
登录成功、失败或聊天提示的证据。

`after-auth-delay-ms` 会在 AuthMeUI Dialog、聊天成功消息及 AuthMe session 恢复被确认后统一生效；
默认的短暂等待可避免机器人刚进入 PLAY、初始数据包尚未处理完就立即跨服。只有确实需要时才建议设为 `0`。

AuthMeUI 默认可以尊重 AuthMe session。启用 `configuration-phase-respect-authme-sessions` 后，有效 session
不会再展示登录 Dialog；AuthMeUI 1.3.4 会把检查延后到玩家加入，随后 AuthMe 发送
`Logged-in due to Session Reconnection.`。Bots4Velo 即使已进入 PLAY，也会继续锁住切服与挂机行为，直到匹配
这条成功证据才放行。

AuthMeUI 1.3.4 自身不能在 configuration/pre-join 阶段保存邮箱注册：`EMAIL_OPTIONAL` 只有邮箱为空时
可以继续按无邮箱注册；一旦需要提交非空邮箱，或使用 `EMAIL_MANDATORY`，必须把
`dialogs.use-configuration-phase` 设为 `false`，改用 post-join。`AUTO` 推断到邮箱字段时也遵守同一限制，
不会为了绕过限制丢弃已经配置的邮箱。

不要同时启用 AuthMe 6 自带 Dialog 和 AuthMeUI。选择 AuthMeUI 时，建议无论上游默认值如何都显式关闭
AuthMe 自带的 pre/post Dialog，再单独选择 AuthMeUI 的 pre-join 或 post-join 模式：

```yaml
# plugins/AuthMe/config.yml
settings:
  registration:
    dialog:
      preJoin:
        enable: false
      postJoin:
        enable: false

# plugins/AuthMeUI/config.yml
dialogs:
  use-configuration-phase: true # false = post-join
rules-dialog:
  enabled: true
  agreement:
    enabled: true
    checkbox-key: "rules_accepted" # 可以改名，Bots4Velo 会读取实际 key
```

兼容边界如下：

- AuthMeUI 1.3.4 需要 Paper 1.21.7+、Java 21+ 和 AuthMe 5.6+；
- `1.21.11` 与 `26.1.2` 是 AuthMeUI 1.3.4 的主要 CI 目标；
- `26.2` 已在真实 Velocity + AuthMe 6.0.0 + AuthMeUI 1.3.4 + 双 Paper 网络完成
  `RULES → REGISTER → PLAY`、post-join 登录与 AuthMe session 复用验证；
- `1.16.5` 不安装 AuthMeUI，继续使用 AuthMe/AuthMeReloaded 的聊天 `/login`、`/register` 回退；
- 验证码、2FA 或插件额外增加的未知必填字段不会被猜测填写；注册邮箱必须由管理员显式配置。应给专用
  机器人账号配置安全的单独放行规则；密码错误或无法自动处理的步骤会按认证失败/超时停止重试。

Bots4Velo 仍支持 AuthMe 6 自带的 preJoin/postJoin Dialog，以及 Smart AuthMe Login UI 的
Title/Subtitle 和默认 `Authenticated` 成功标题。部署时只选择一套主要 UI，避免两个插件同时发送认证表单。
默认正则覆盖 AuthMe 的 `/register`、`/login`、`Successfully registered!`、`Successful login!` 和常见
session reconnect/中文成功消息；自定义聊天文案时应同步更新机器人的 `login-prompts`、`register-prompts`
与 `success-messages`。未声明 `success-messages` 时会使用这组安全默认值；显式配置为空列表不会把
Dialog 提交本身当作成功。pre-join AuthMeUI 仍可在服务端接受凭据并放行进入 PLAY 后完成认证；post-join
或聊天认证没有可匹配的成功消息时则保持 fail-closed，并最终报告认证超时。Smart AuthMe Login UI 的
`premiumNameProtection` 可能拒绝与 Mojang 正版账号同名的
离线机器人，生产服应使用不会撞名的专用机器人名称或按插件安全策略单独放行。

### TAB

已在 Velocity 上与 TAB 6.1.0 联调。若要启用 TAB 的 scoreboard、belowname、playerlist objective
等计分板功能，应同时在 Velocity 安装 VelocityScoreboardAPI；Bots4Velo 不需要直接依赖 TAB API，
机器人仍作为普通 Velocity 玩家参与 TAB 的玩家列表和计分板更新。

## 已完成的本地集成验证

- 同一个最终 Velocity JAR 分别以协议 754、774、775、776 通过 Velocity 进入对应 Paper 后端。
- 机器人被传送至出生区块以外的 `(256,256)`：在线时区块已加载且含玩家实体，退出 15 秒后卸载。
- 后端重启期间按 1/2/4/5 秒退避重连，并在后端恢复后重新进入 PLAY。
- 三机器人在登录后端不可用期间持续重试：实际 TCP 连接按全局间隔交错，后端恢复后无需人工操作
  全部重入 PLAY、重新通过 CraftEngine 资源包并切换至 survival。
- 运行时执行 `create` 后新机器人进入 PLAY；关闭并重启 Velocity 后从 `managed-bots.yml` 恢复，
  再执行 `remove` 后在线列表由 4 个恢复为 3 个且持久记录清除。
- 1.21.11 协议移动后，服务端 NBT 确认位置 `(4097.25,65,4097.75)`、视角 `(123,25)`；
  1.16.5 协议确认位置 `(4097.25,78,4097.75)`、视角 `(210,-30)`，均与 `monitor` JSON 一致。
- 1.21.11 与 1.16.5 均由后端控制台执行 `kill`：机器人未断线或重新登录，5 秒后服务端
  `Health=20.0`、`DeathTime=0` 且仍在线；1.16.5 的 `monitor` 同步收到重生点位置校正。
- 两个机器人错峰连接；已注册账号执行 `/login`，未注册账号由登录提示回退执行 `/register`。
- 通用资源包探针请求收到 `ACCEPTED`、`DOWNLOADED`、`SUCCESSFULLY_LOADED` 完整状态序列。
- Paper 1.21.11 上实际加载 CraftEngine 26.7.4，生成并上传 195,809 字节资源包；机器人在
  登录自动下发和 `/ce feature send-pack` 重复下发时均完成
  `OFFER_RECEIVED → ACCEPTED → DOWNLOADED → SUCCESSFULLY_LOADED`，随后保持 PLAY。
- 1.16.5 与 1.21.11 均完成登录服到生存服切换；目标服不可用期间持续重试，
  `after-login-commands` 只在确认切服并等待稳定窗口后执行。
- 官方 Leaf 1.21.11 stable build 173 上，AUTO 从 `backend:leaf` 识别协议 774；机器人进入 PLAY、
  完成资源包状态序列并出现在 Leaf 玩家实体中。机器人位于区块 `(256,256)` 时该区块保持加载，
  退出 15 秒后卸载；控制台击杀后自动复活且未重新建立网络连接。
- 官方 VeloAuth 1.4.0 + Velocity 3.5.1 + Leaf 1.21.11 隔离网络中，首次账号匹配注册提示并自动
  切入 Leaf，H2 `AUTH` 表保存 BCrypt 与离线 UUID，审计表写入 `REGISTER`；保留数据库重启后，
  机器人匹配登录提示、收到成功消息并再次切入 Leaf，审计表新增 `LOGIN_OK`。
- Paper 26.2 build 84 + AuthMe 6.0.0 Paper 实测：聊天模式下首次连接自动 `/register`，保留 SQLite
  重启后自动 `/login`；两次都匹配官方成功消息并切入 `afk`。开启 AuthMe 6 pre-join Dialog 后，
  机器人在 CONFIGURATION 阶段提交登录表单，通过后进入 PLAY 并切入 `afk`。
- Smart AuthMe Login UI 1.0.2 + AuthMe 6.0.0 实测：机器人识别 Title 注册提示、执行 `/register`、
  匹配成功消息并切服；保留账号后也能通过登录流程。
- Velocity 3.5 build 609 + TAB 6.1.0 + VelocityScoreboardAPI 2.1.0 实测无协议断线；运行时命令让
  机器人完成 `afk → lobby → afk`，随后 `movehere` 再自动切到玩家所在的 lobby。Paper 日志确认执行
  `/minecraft:tp AFK_262 TypeThe0ry` 并成功传送。
- 在 Velocity 对外 Status 宣告协议 776、目标 Leaf 实际为协议 774 的组合中，AUTO 仍选择
  `1.21.11 (774)`，检测来源记录为 `backend:leaf`。

认证验证同时覆盖兼容测试探针、官方 VeloAuth 1.4.0 和 AuthMe 6.0.0；资源包验证除通用探针外，已经覆盖
CraftEngine 26.7.4 的真实生成、上传、登录自动下发和 PLAY 阶段重复下发。这些结果证明了协议与
标准流程兼容，但用户生产服的自定义消息、权限、数据库和门禁规则仍须在部署前复验。

## 尚需真实网络验证

本地矩阵已经证明四版本协议连接、真实 VeloAuth/AuthMe 流程、TAB 共存、真实 Leaf 切换和远端区块加载。生产网络
验收仍需要：

- 用户实际 VeloAuth/CraftEngine 版本、语言文案、数据库和门禁配置下复验；
- UUID、玩家数据和插件识别结果核对；
- 目标 Leaf 上的刷铁机、生物生成和随机刻实测；
- 至少 24 小时在线与代理/后端重启恢复测试。
