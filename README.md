# Deekseep

一个基于 **LSPosed** 的 DeepSeek 客户端增强模块（包名 `com.dsmod.probe`）。

模块在宿主进程内运行，通过 Xposed 钩子接管聊天界面、工具调用、心跳与本地设备能力，同时内置一套跨版本符号解析机制，让同一份模块 APK 能在多个 DeepSeek 版本上工作。

---

## 功能概览

- 聊天界面与输入区的钩子改造
- 本地工具协议：文件、Shell、界面操作、音乐、截图等
- 主动心跳与定时任务
- 账号、备份、搜索、外观等辅助界面
- 多版本宿主适配（2.2.x / 2.3.0 / 2.3.4 / 2.3.6 / 2.4.5）

---

## 构建

```bash
./gradlew :app:assembleDebug
```

产物位于：

```
app/build/outputs/apk/debug/app-debug.apk
```

安装后在 LSPosed 管理器中启用模块，作用域勾选 **DeepSeek**（`com.deepseek.chat`），然后强制停止宿主或重启设备。

### 环境

| 项目 | 值 |
|------|-----|
| 语言 | Java 8 |
| 构建脚本 | Gradle Kotlin DSL |
| AGP | 8.0.2 |
| compileSdk | 35 |
| minSdk | 24 |
| targetSdk | 35 |

---

## 宿主符号解析

### 问题

DeepSeek 每次发版都会被 R8 重新混淆。同一份业务契约，类名却在不断变化：

| 逻辑名 | 2.2.x | 2.3.6 | 2.4.5 |
|--------|-------|-------|-------|
| ChatViewModel | `td1` | `td1` | `hq1` |
| Chat State/Event | `lq` | `lq` | `qq1` |
| ChatCompletionRequest | `nx0` | `nx0` | `xx0` |
| ChatFullCompletionRequest | `qw0` | `qw0` | `g11` |
| LocalViewModelStoreOwner | `sc` | `sc` | `p5` |

2.4.5 尤其麻烦：R8 把混淆策略从「2-3 字符短名」整体换成了「3 字符字母数字混合名」，等于把整张对照表全部洗牌。

### 两条路并行

**静态映射表 —— `HostCompat`**

`HostCompat` 维护一条逐级翻译链：

```
2.2.x → name230() → name234() → name236() → name245()
```

每一级只负责自己那一代的差异，加载时通过反射探针（方法签名、构造器形状）判定当前宿主属于哪一代，再选择对应分支。优点是零额外依赖、启动快；代价是每出一个新版本都要人工补表。

**运行时解析 —— `DexKitHostResolver`**

新增的解析器换一条思路：不认名字，只认行为。

它把每个目标类的可观测契约写成匹配条件，交给 **DexKit** 扫描宿主 APK 的 DEX，直接解出当前版本的真实类名。名字再怎么变，只要契约没动就还能找到。

---

## DexKit 集成

### 依赖

```toml
# gradle/libs.versions.toml
dexkit = "2.2.0"
dexkit = { group = "org.luckypray", name = "dexkit", version.ref = "dexkit" }
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(libs.dexkit)
}
```

DexKit 自带 `arm64-v8a` / `armeabi-v7a` / `x86` / `x86_64` 四套原生库。为了让它能在运行时 dlopen 成功，构建脚本做了两件事：

- `packaging { jniLibs { useLegacyPackaging = true } }` —— 让 `.so` 以传统方式落在 APK 的 `lib/` 目录
- 清单中的 `android:extractNativeLibs="true"` —— 安装时把 `.so` 解压到磁盘

两者缺一不可，否则会出现 `UnsatisfiedLinkError`。

### 解析器设计

`DexKitHostResolver` 是包级私有类，对外只暴露四个方法：

```java
static Map<String, String> resolve(Context ctx, String apkPath, long versionCode)
static Map<String, String> snapshot()
static String lookup(String logicalKey)
static boolean isReady()
```

逻辑键沿用 `HostCompat` 的 2.2.x 符号名，两边可以直接对接，不需要额外的翻译层。

### 匹配策略

每个目标类用「结构指纹」描述，而不是名字：

| 目标 | 匹配条件 |
|------|----------|
| `ChatViewModel` | 同类同时具备 `A(Object) → Object` 与 `f(Object,Object,Object) → Object` |
| `Chat State/Event` | 从 ViewModel 的无参方法返回类型中挑选，优先取同时出现在两参 void 方法参数表里的那个 |
| `ChatCompletionRequest` | 无参 `serializer()` + 11 参构造器 `(String,?,String,?,boolean,boolean,?,boolean,String,String,int)` |
| `ChatFullCompletionRequest` | 同上形状，按类名排序取第二个不同类 |
| `LocalViewModelStoreOwner` | 返回 `androidx.lifecycle.ViewModelStoreOwner` 的无参方法之宿主类 |
| `kotlin.Unit` | 具备静态、类型等于自身的 `INSTANCE` 字段的类 |

`ChatViewModel` 用的是与 `HostCompat.hasV245ChatViewModel()` 完全一致的双契约交叉验证，所以两边的判定结果可以互相印证。

### 缓存

DexKit 扫描本身在毫秒级，但没必要每次冷启动都跑。解析器把结果按宿主 `versionCode` 缓存到宿主应用自己的 cache 目录：

```
/data/data/com.deepseek.chat/cache/dexkit_hostmap_<versionCode>.properties
```

- 命中缓存：只做一次文件读取，不做扫描
- 宿主升级导致 `versionCode` 变化：旧缓存自动失效，重新扫描并覆写
- 缓存写入失败：只影响下次启动速度，不影响功能

### 日志

解析过程统一使用 tag `Deekseep/DexKit`，每条映射解出时都会打一行：

```
ChatViewModel -> hq1
Chat State/Event -> qq1
ChatCompletionRequest -> xx0
LocalViewModelStoreOwner -> p5
Unit -> mu8
```

没有解出的目标会打 `未定位到 xxx`，方便定位需要补匹配条件的项。

---

## 已知限制

- **`ChatFullCompletionRequest` 无法纯结构区分**：它与 `ChatCompletionRequest` 的 11 参构造器形状完全一致，解析器只能按类名排序取第二个，并把全部候选打进日志供人工核对。如果发现归类反了，在 `findCompletionRequests()` 里调整排序键即可。
- **`Chat State/Event` 是启发式选取**：依赖「state 类型会出现在 reducer 参数表里」这一经验，个别版本可能选到相邻类型，同样以日志为准。
- **两条路尚未完全合并**：`DexKitHostResolver` 目前是独立层，`HostCompat` 仍在走自己的静态表。要彻底去掉人工维护，需要把 `HostCompat.name()` 改为优先查解析器、未命中再落回静态表。
- **不覆盖 Compose 运行时类**：`docs/V245_MAPPING.md` 里列的 `u12`、`i02`、`di5` 等 Compose 内部类没有纳入解析，它们不是版本适配的稳定契约。

---

## 目录结构

```
app/src/main/java/com/dsmod/probe/
├── HostCompat.java              # 静态映射表与版本探测
├── DexKitHostResolver.java      # DexKit 运行时符号解析
├── Main.java                    # 模块入口
├── DeekseepTools.java           # 工具协议实现
├── HeartbeatToolProtocol.java   # 心跳协议
└── ...                          # 界面、账号、音乐、备份等

docs/
└── V245_MAPPING.md              # 2.4.5 手工对照表
```

---

## 参考

- [DexKit](https://github.com/LuckyPray/DexKit) —— 高性能 DEX 运行时解析库
- [LSPosed](https://github.com/LSPosed/LSPosed) —— Xposed 框架实现
