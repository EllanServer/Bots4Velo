# Bots4Velo

一个独立、可构建为单 JAR 的 Velocity 插件原型。每个机器人都是嵌入插件进程的
Minecraft 无界面客户端，并通过真实协议连接回 Velocity。

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
- `/vbot movehere` 自动识别执行者所在后端，先把机器人跨服，再传送到执行者身边。
- TAB 玩家列表/计分板包可由协议客户端正常接收；命令自身也提供机器人、服务器和帮助页补全。
- 死亡重生和指数退避断线重连；所有首次连接及自动重连共享全局连接间隔。
- `runtime.maximum-bots` 是静态机器人和运行时机器人合计的硬上限。
- `/vbot status` 提供当前协议/状态以及本进程内累计 PLAY、非人工断线、资源包成功次数和时间戳，
  用于稳定性观察与故障审计。
- 协议级绝对位置和视角控制，并可输出单个或全部机器人的 JSON 监控快照。

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

产物位于 `build/libs/bots4velo-0.1.0-SNAPSHOT.jar`。
`check` 还会从最终阴影 JAR 中加载一次已重定位的协议客户端，避免只验证未打包的开发类路径。
只需把这个 JAR 放入 Velocity 的 `plugins` 目录。首次启动会生成
`plugins/bots4velo/config.yml`。

单 JAR 同时包含四套隔离协议栈，因此文件会明显大于普通 Velocity 插件；这是避免不同版本
MCProtocolLib 及其传递依赖冲突的设计结果。

## GitHub 大版本发布

推送大版本标签会自动执行测试、构建阴影 JAR、上传 GitHub Actions artifact，并创建同名
GitHub Release（附带 JAR 和自动生成的 Release Notes）。仅 `vX.0.0` 形式的标签会触发，
例如 `v2.0.0`；`v1.1.0`、`v1.0.1`、普通提交和分支推送均不会触发此发布流程。

```powershell
git tag v2.0.0
git push origin v2.0.0
```

发布构建会将标签去掉前缀 `v` 后作为插件版本，例如 `v2.0.0` 生成
`bots4velo-2.0.0.jar`。

## 首次联调

1. 保持示例机器人的 `enabled: false`，配置本地监听端口、virtual-host、账号密码和目标服。
2. 启动 Velocity，使用控制台 `/vbot start IronFarm01`。
3. 用 `/vbot status IronFarm01` 检查自动选择的协议，以及 LOGIN、CONFIGURATION、PLAY 或断线原因。
4. 在登录服确认注册/登录，再确认机器人进入 Leaf、执行挂机点传送并加载目标区块。
5. 单机器人验证通过后再设为 `enabled: true`，随后逐个增加机器人。

管理权限为 `bots4velo.admin`。命令包括：

```text
/vbot help [1|2]
/vbot list
/vbot status <id>
/vbot monitor [id]
/vbot servers
/vbot server <id> <server>
/vbot movehere <id>
/vbot position <id>
/vbot move <id> <x> <y> <z>
/vbot look <id> <yaw> <pitch>
/vbot create <id> <username> <password|-> [target-server|-]
/vbot remove <id>
/vbot start <id>
/vbot stop <id>
/vbot reconnect <id>
/vbot command <id> <command...>
/vbot reload
```

`create` 创建的机器人会原子写入 `plugins/bots4velo/managed-bots.yml`，不会重写带注释的
主 `config.yml`，重启后自动恢复；`remove` 只允许删除这类受管理机器人。密码和目标服位置使用
`-` 表示禁用认证或留空。密码会像主配置一样以明文保存在服务器本地，应限制数据目录访问权限，
并优先从 Velocity 控制台执行含密码的创建命令。

`server` 只在机器人进入 PLAY 且认证完成后执行，并使用 Velocity 注册的后端名称。`movehere`
必须由游戏内玩家执行；它会让执行者向当前后端提交
`/minecraft:tp <机器人> <执行者>`，因此执行者还需要该后端的传送权限。切服期间执行者离开目标服时，
操作会安全取消。

`move` 发送正常客户端移动包，不具备服务端传送权限；距离过大、碰撞或越界时，后端可能用位置包
校正机器人。`position` 和 `monitor` 会反映最近一次客户端接受的服务器位置，`pitch` 范围为
`-90..90`。`monitor` 输出单行 JSON，适合控制台采集器或外部健康检查读取。

### AuthMe、AuthMe UI 与 Smart AuthMe Login UI

Bots4Velo 支持 AuthMe/AuthMeReloaded 的聊天命令认证，也支持现代 Paper 上的登录 UI：

- AuthMe 6 内置 `preJoin` Dialog：机器人读取登录/注册表单，在 CONFIGURATION 阶段提交密码；
  表单通过并进入 PLAY 后才会切服或执行登录后命令。
- AuthMe 6 内置 `postJoin` Dialog：机器人识别 Dialog 中的登录/注册命令模板并走正常命令认证。
- Smart AuthMe Login UI：机器人读取 Title/Subtitle 提示，兼容其命令同步与默认
  `Authenticated` 成功标题。

pre-join 处理会从收到的 Dialog NBT 中提取实际 custom-click 动作 ID 和密码字段名，不依赖某个
固定 AuthMe build。`/vbot status <id>` 会显示 `Auth UI`、`Presented`、`Submitted`：若两个计数
都是 `0`，说明服务器 UI 格式未被识别；若 `Presented` 大于 `Submitted`，说明表单无法提交；若
已经提交但后端仍超时，通常是密码不正确、账号注册状态不符，或后端还要求验证码/2FA。

因此 1.21.11、26.1 和 26.2 登录服可以保持 Dialog 开启，例如：

```yaml
settings:
  registration:
    dialog:
      preJoin:
        enable: true
      postJoin:
        enable: true
```

默认正则已覆盖 AuthMe 的 `/register`、`/login`、`Successfully registered!` 和
`Successful login!` 英文消息、Smart AuthMe Login UI 默认的 `Authenticated`，以及常见中文成功消息。
若服务器修改了语言文件或 UI 标题，应把实际文案加入
各机器人的 `login-prompts`、`register-prompts` 和 `success-messages`。

1.16.5 不支持现代 Dialog 数据包，仍使用聊天命令流程。其他改变 AuthMe pre-join 自定义动作 ID
或要求验证码、邮箱、2FA 的扩展不能仅靠密码自动通过，应为机器人账号配置绕过或使用相应命令流程。
Smart AuthMe Login UI 的 `premiumNameProtection` 可能拒绝与 Mojang 正版账号同名的离线机器人；生产服应
使用不会撞名的专用机器人名称，或按该插件的安全策略单独放行，而不是全局关闭名称保护。

### TAB

已在 Velocity 上与 TAB 6.1.0 联调。若要启用 TAB 的 scoreboard、belowname、playerlist objective
等计分板功能，应同时在 Velocity 安装 VelocityScoreboardAPI；Bots4Velo 不需要直接依赖 TAB API，
机器人仍作为普通 Velocity 玩家参与 TAB 的玩家列表和计分板更新。

## 已完成的本地集成验证

- 同一个最终 JAR 分别以协议 754、774、775、776 通过 Velocity 进入对应 Paper 后端。
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
