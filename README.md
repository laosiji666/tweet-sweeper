# 推特清理助手 · TweetSweeper

Android 原生 App（Kotlin + Jetpack Compose）。账号通过 **X 官方 OAuth 2.0（Authorization Code + PKCE）** 授权后，用官方 API v2 做批量取关、找不互关、封禁检测，以及点赞/转推清理。

- 包名：`com.yg.tweetsweeper`
- minSdk 26 / targetSdk 34 / compileSdk 35
- 无中间服务器，所有请求由手机直连 `api.x.com`

---

## 一、功能

| 模块 | 说明 | 用到的接口 |
| --- | --- | --- |
| 关系扫描 | 抓取全部关注 + 粉丝，本地比对 | `GET /2/users/:id/following`、`/followers` |
| 不互关 | 你关注了对方、对方没回关 | 本地集合运算 |
| 仅粉丝 | 对方关注你、你没回关 | 同上 |
| 封禁/注销检测 | 批量核对，标记已停用、已消失 | `GET /2/users?ids=…`（每批 100） |
| 批量取关 | 勾选/全选后逐条取关，带白名单、每日上限、随机间隔、试运行 | `DELETE /2/users/:id/following/:target` |
| 点赞清理 | 统计并取消最近 N 条点赞 | `GET /2/users/:id/likes`、`DELETE …/likes/:tweet` |
| 转推清理 | 统计并删除自己的转推（原推不受影响） | `GET /2/users/:id/tweets`、`DELETE /2/tweets/:id` |
| 导出 | CSV（关系 + 状态），走系统文件选择器 | 本地 |
| 安全 | 白名单保护、Dry run、每日配额、操作历史、加密存令牌 | 本地 |

## 一·二、零成本模式（离线页）

不需要 Client ID、不调用任何 API、不花一分钱：

1. X App → 设置 → 你的账号 → **下载你的数据存档**
2. 收到 zip 后，在 App 的「离线」页点「选择数据包 zip」
3. 自动解析 `data/following.js` / `data/follower.js`（只读 `accountId`），本地算出不互关 / 互关 / 仅粉丝
4. 点某个账号的「打开」，跳转 `https://x.com/i/user/<id>` → X App 打开对方主页，手动点取消关注
5. 点过「打开」的账号会自动标记**已处理**并从列表移除，顶部显示「待处理 N · 已处理 M」
6. 「显示已处理」可回看并撤销，「重置标记」可整批重来；标记存在本机，重启不丢
7. 可「复制本页链接」或导出 CSV

限制：数据是快照（取关后要重新申请导出）、包内没有用户名与封禁状态（要用户名/封禁判断请用 API 模式「扫描」页）。

## 二、目录结构

```
app/src/main/java/com/yg/tweetsweeper/
├── MainActivity.kt          接收 OAuth 回调（tweetsweeper://callback）
├── net/
│   ├── Models.kt            接口模型 + 错误映射
│   ├── RateGate.kt          限速闸门（解析 x-rate-limit-*，满额自动等待）
│   └── XApi.kt              API 封装（401 自动刷新、429 自动排队重试）
├── auth/
│   ├── Pkce.kt              PKCE 生成 + 授权 URL
│   └── AuthRepository.kt    令牌交换/刷新/加密存储（EncryptedSharedPreferences）
├── data/AppStore.kt         快照/白名单/历史落盘 + 配额与设置
├── data/ArchiveImporter.kt  官方数据包解析（following.js / follower.js）
├── vm/MainViewModel.kt      API 模式状态机（扫描、取关、清理、筛选）
├── vm/LocalViewModel.kt     离线模式状态机（零 API）
└── ui/                      Compose 界面：扫描 / 清理 / 工具 / 设置
```

## 三、X 开发者后台配置（必须）

1. 打开 <https://developer.x.com> → 创建 Project + App。
2. App 的 **User authentication settings** → Set up：
   - App permissions：`Read and write`
   - Type of App：**Native App**（public client，不需要 client secret）
   - Callback URI：`tweetsweeper://callback`
   - Website URL：随便填一个合法地址
3. 记下 **OAuth 2.0 Client ID**（不是 API Key / Secret）。
4. 确认套餐包含 **follows.write**（取关是写接口，免费档通常不含写入额度，需要 Basic 及以上）。

授权时 App 申请的 scope：

```
tweet.read users.read follows.read follows.write like.read like.write offline.access
```

## 四、编译

```bash
cd tweetsweeper
./build.sh            # 首次会生成 release.keystore 并写入 keystore.properties
# 产物：app/build/outputs/apk/release/app-release.apk
```

手动等价命令：

```bash
export JAVA_HOME=/opt/jdk17
export ANDROID_HOME=/root/android-sdk
gradle assembleRelease
```

`local.properties` 里是 `sdk.dir`；本机 proot 环境用 `~/.gradle/gradle.properties` 里的 `android.aapt2FromMavenOverride` 指向 arm64 版 aapt2。

## 五、使用流程

1. 打开 App → 填 Client ID → 「用 X 授权登录」→ 浏览器里同意 → 自动回到 App。
2. 「扫描」页 → 开始扫描（关注/粉丝全量 + 封禁检测）。
3. 「清理」页 → 筛选「不互关」→ 勾选或用「全选本页」→ 建议先开 **试运行** 跑一遍。
4. 「设置」里关掉试运行 → 回到「清理」→ 批量取关（弹窗二次确认）。
   —— 注意：2026-04 起取关写接口已从所有自助档位下架，官方 API 取关会返回 403；这条路径仅在 Enterprise 或旧套餐下可用。
   免费做法见上面的「零成本模式」。
5. 「工具」页可清点赞、删转推、导 CSV。

## 六、限速与额度（官方硬限制，App 会自动排队）

- 关注/粉丝列表：15 次 / 15 分钟，每页最多 1000 人
- 用户批量查询：用于封禁检测，每批 100 个 id
- 取关 / 取消点赞 / 删推：写入类接口按日或按窗口限额，`x-rate-limit-remaining` 见 0 时自动等待重置
- 本地还有一层「每日取关上限」（默认 50，可调 1–200）和随机间隔（默认 1.5–3.5 秒）

## 七、注意

- 取关不可批量撤销，先试运行核对名单；重要账号加进白名单。
- 扫描结果存在本机 `filesDir/snapshot.json`，可离线查看；换设备需重新扫描。
- 令牌用 Android Keystore 加密保存；「退出登录」只清令牌，不清快照。
- 账号被限制（read-only）时写接口会返回 403，界面会把原文打在日志里。

## 九、隐私

- 没有服务器、没有统计 SDK、没有崩溃上报，App 不向任何第三方发送数据
- OAuth 令牌存在 Android 加密存储（EncryptedSharedPreferences），卸载即清除
- 名单、快照、白名单、处理标记只写在本机 `filesDir`
- 官方数据包由用户手动导入本地解析，不上传
- 仓库里不包含任何密钥、令牌、账号信息；`keystore.properties` / `release.keystore` / `local.properties` 已被 `.gitignore` 排除

## 十、构建

```bash
./build.sh          # 自动挑 JDK、生成签名密钥（如缺失）、编译 release
# 产物 app/build/outputs/apk/release/app-release.apk
```

`./gradlew assembleRelease` 等价可用。
