# legacyfix-attic 工作计划书（Attic 启动器侧拍板，2026-10-29）

> 本 fork = betacraftuk/legacyfix 的唯一 fork 路线（启动器仓库 plan.md「legacyfix fork 定稿」条目）。
> 分支纪律：`v3` 分支 = 纯上游镜像（只做 ff-sync，不落任何自研 commit）；自研工作全部在 `attic` 分支（当前基 = tag `26.10`），每次上游 release rebase 一次。版本命名 `<upstream>-attic.<n>`（如 `26.10-attic.1`）。
> 构建已验证（2026-10-29 本机）：`JAVA_HOME=C:\Program Files\Java\jdk1.8.0_202` + `gradlew build --no-daemon`（Gradle 6.9.4 / shadow 6.1.0）→ BUILD SUCCESSFUL，产物 `build/libs/legacyfix-<version>.jar`（约 1.09MB，含全部子模块与 javassist）。缺省 JDK 25 不可用（Gradle 6.9 上限），必须锁 8。

## 任务 A：DeAwt 窗口位置一行修复（PR 先行）

**缺陷机理**（启动器侧字节码级实锤，LWJGL 2.9.0/2.9.3 同构）：
`DeAwtPatch.transformDisplay` 替换的 `Display.setParent` 注入体（`common/src/main/java/uk/betacraft/legacyfix/patch/impl/lwjgl/DeAwtPatch.java` L288-302）只设尺寸不设位置 → `Display.getWindowX/Y` 走居中公式 `Math.max(0,(initial_mode − current_mode)/2)`，其中 initial=物理桌面、current=逻辑分辨率——高 DPI 混缩放下两值不同源，窗口偏右下。`Display.setLocation(x,y)` 无条件写 x/y 静态字段、`create` 时原样采用，是唯一落点（DisplayMode 公开构造器仅 (w,h)，无坐标通道）。

**修复 = 一行**：在注入体 `setDisplayMode(...)` 与 `setResizable(true)` 之间（L299/L300 之间）插入

```java
"    org.lwjgl.opengl.Display.setLocation(frame.getX(), frame.getY());" +
```

`frame` 局部变量在注入体已就位（AppletLauncher 已 `setLocationRelativeTo(null)` 定妥；未显示 Frame 的 `getX/getY` 返回显式设置值，`getLocationOnScreen` 会抛 `InvalidComponentException` 故不用）。

**流程**：
1. `attic` 分支落码 + 本地构建 + 启动器实机验收（高 DPI 150% 下 in-20100223 / c0.24 窗口位置）。
2. **同内容给上游提 PR**（betacraftuk/legacyfix，目标 v3 分支）——一次 PR 换启动器侧整套二进制补丁退役。
3. 无论 PR 合否，fork 出 `26.10-attic.1` release。启动器侧随即退役 `legacyfixPatch.ts` 常量池二进制补丁全套（SHA256 钉版表/锚点唯一性/幂等 machinery——fork 源码级修复后全部失去存在意义），`agentMount` 钉版表切 fork 直链。

## 任务 B：addons 机制（用户拍板三条：新建类同构 v1 / 模块化 jar / `-Dlf.addons=`）

### B1. API = 新建类，与 Betacraft v1 `org.betacraft.Addon` 同构

v1 语义取证（reference/betacraft-launcher）：抽象类 `Addon`，钩子 `preAppletInit/postAppletInit(Wrapper, ArrayList<Addon> addonsLeft)` 返回 `WhatToDo{STOP_LOOP, NORMAL, STOP_CODE}`；`applyAfter(): List<String>` = 依赖图（v1 `Wrapper.loadAddon` 递归先载依赖）；`getName()` = 类简名去重。

fork 新 API（包 `uk.betacraft.legacyfix.addon`），同构但宿主语境换成 agent：

```java
public abstract class Addon {
    public String getName() { /* 类简名，同 v1 */ }
    public List<String> applyAfter() { return new ArrayList<String>(); } // 依赖图，同 v1

    /** 补丁相位（premain，Patcher.apply() 之后跑）：注册 CtTransformer/Transformer 到 PatchPool。 */
    public void patch(PatchPool pool) throws Exception {}

    /** Applet 生命周期钩子（同 v1 同名同返回型；上下文对象 = 游戏实例，addonsLeft 语义保留）。 */
    public WhatToDo preAppletInit(Object gameInstance, List<Addon> addonsLeft) { return WhatToDo.NORMAL; }
    public WhatToDo postAppletInit(Object gameInstance, List<Addon> addonsLeft) { return WhatToDo.NORMAL; }

    public enum WhatToDo { STOP_LOOP, NORMAL, STOP_CODE }
}
```

### B2. 装载 = `-Dlf.addons=` 系统属性（零管道成本）

`Agent` 静态块已把所有 `lf.*` 系统属性自动收进 SETTINGS（Agent.java L237-242），故 `AddonLoader` 直接 `Agent.getSetting("lf.addons", null)` 即可拿到值，无需任何新配置面。

- 值格式 = **逗号分隔的 jar 路径**（跨平台，Windows 路径含 `;`/`:` 不歧义）：`-Dlf.addons=D:\.attic\launcher\addons\gamemodeswitch.jar,...`
- 便捷形态：单一路径以 `.jar` 结尾 = 文件，否则按**目录**处理 = 扫 `<dir>/*.jar`（对齐启动器数据布局 `launcher/addons/<name>.jar`）。
- 发现方式 = 每枚 jar 的 **manifest 属性 `LegacyFix-Addon: <类全名>`**（一枚 jar 一个入口类 = 模块化，同 v1「一 addon 一 jar」；多入口预留逗号）。
- 装载实现 = `URLClassLoader`（父 = agent 类加载器，让 addon 能 `import javassist.*` 与 `uk.betacraft.legacyfix.*`）——与 v1 `Wrapper` 的 URLClassLoader 加载同构。
- 排序 = 复刻 v1 `loadAddon` 递归：按 `applyAfter()` 先载依赖、`getName()` 去重、循环依赖显式报错不静默。

### B3. 生命周期接线（两个调用点，都在 agent 自有代码，不靠字节码 patch）

1. **patch 相位**：`Agent.premain` 里 `patcher.apply()`（L59）之后、redefinition 收集（L61）之前——addon 注册的 transformer 与内置 patch 同批生效。装载失败/入口类缺失 = Logger.error + 跳过该 addon（agent 绝不因 addon 崩游戏，非 required 语义）。
2. **pre/postAppletInit**：`applet/AppletLauncher.java` 调 `((Applet)instance).init()` 的包夹点（对齐 v1 `Wrapper.init()` L664-694 的 `addonsPreAppletInit → init → addonsPostAppletInit` 次序）。`STOP_LOOP` 撤后续、`STOP_CODE` 语义照抄 v1。

### B4. 首批三枚 addon（退役启动器侧 v1 移植愿望池）

上游空缺三项，javassist 重写为模块化 jar（源码住 fork 新子模块 `addons/`，逐枚 shadowJar 独立构件）：

| addon | 目标行为 | 挂载点（javassist） |
|---|---|---|
| `gamemodeswitch` | classic/indev F3 切创造/生存 | Minecraft 输入处理（KeyBinding 类名逐代混淆，用 `GameClasses.find*` 家族探测，不写版本 id——戒律同 upstream） |
| `quitgame` | 游戏内退回启动器（DeAWT 下 `System.exit` 语义核对） | `Minecraft.run()` 退出路径 / `Display.isCloseRequested` |
| `fullscreen` | 老版本 F11 全屏 | `org.lwjgl.opengl.Display.setFullscreen` 可用性 + 按键注入 |

验收 = 启动器实机矩阵（c0.24 / in-20100223 / b1.7.3 至少各一）。

## 任务 C：发版通道（fork CI）

1. **抄上游 `release.yml`/`gradle.yml` 改两处**：⚠️ tag 触发正则 `[0-9][0-9].[0-9]*` 匹配不了 `26.10-attic.1`，须改 `*-attic.*`；nightly 流 = push 到 `attic` 分支即构建（`CI_NIGHTLY=true` → `nightly-<run_id>`，供启动器侧提前接线自测）。
2. **weekly 轮询 workflow**：`git fetch upstream --tags`，上游最新 tag ≠ 当前基座 → 自动开 rebase PR（冲突则只开 issue）。合并人工化：启动器发版前或上游修复打中我们时才真 rebase。
3. Release 页注明 fork 身份 + rebase 纪律（本文件可作 README 底稿）。

## 执行顺序

A（一行修复 + 上游 PR）→ C.1（fork release 通道，出 `26.10-attic.1`）→ B（addons 机制 + 三枚首批）→ C.2/C.3 并行。
启动器侧对应动作（fork 首个 release 之后才做，勿提前）：退役 `legacyfixPatch.ts`、`agentMount` 钉版表切 fork 直链、JVM 参数拼 `-Dlf.addons=<addons 目录>`、UI addons 通道启用。
