# walkwalk · 起来走走（久坐提醒）

一个只靠手机就能用的久坐提醒安卓 App：用计步判断你有没有起身走动；手机立在桌上时，还可以用前置摄像头判断你是否离开了座位。连续坐满设定时间就振动提醒。

- 支持安卓 8.0（API 26）及以上，targetSdk 34
- 不申请联网权限，所有数据只保存在手机上
- 摄像头画面只在手机上即时分析，不保存

## 目录结构

```
AndroidManifest.xml        清单：权限、前台服务类型（health|camera）
src/com/qilai/zouzou/
  SitTracker.java          久坐判断状态机（纯 Java，不依赖 Android，可在电脑上测试）
  MonitorService.java      前台服务：计步、定时判断、摄像头调度、通知和振动
  FaceChecker.java         打开前置摄像头取几帧判断有没有人脸，随后立即关闭
  MainActivity.java        界面（纯代码布局）：状态、设置、今日统计、桌面常亮模式
  Prefs.java               设置和当天统计（SharedPreferences）
res/                       图标（由 tools/make_icons.py 生成）
test/SitTrackerTest.java   判断逻辑的场景测试（32 项）
tools/
  make_icons.py            生成图标
  pack_apk.py              合并资源和 dex，按 4 字节对齐
  verify_apk.py            独立校验 v2 签名、ZIP 完整性和对齐
  signer/                  v2 签名工具（AOSP 的 ApkSignerV2 + 一个小入口 SignV2.java）
build.sh                   打包脚本
```

## 判断规则（SitTracker）

| 规则 | 默认值 |
|---|---|
| 提醒间隔 | 45 分钟（界面可选 30/45/60/90） |
| 没起身时重复提醒 | 每 5 分钟 |
| 算作“起身活动过” | 5 分钟内走够 50 步 |
| 算作“正在走动”（暂停计时） | 30 秒内 ≥ 8 步 |
| 摄像头判断离开座位 | 之前看到过人脸，随后连续 3 分钟看不到 |
| 不再相信“没看到人” | 超过 2 小时没看到过人脸（多半是摄像头没对准） |

摄像头没有可用结果时（关闭、手机平放、在兜里、被占用），只要没检测到走动就当作坐着。手机平放时不会打开摄像头。

## 运行测试

```
javac -encoding UTF-8 -d /tmp/t src/com/qilai/zouzou/SitTracker.java test/SitTrackerTest.java
java -cp /tmp/t SitTrackerTest
```

## 打包

这个项目没有用 Gradle，`build.sh` 直接调用这些工具：aapt2 → javac → dx → 对齐 → v2 签名。需要把下面四个文件放进同一个目录，再用 `TOOLS=那个目录 ./build.sh` 运行：

| 文件 | 来源 |
|---|---|
| `aapt2` | Apktool 仓库自带的 Linux 版 aapt2 |
| `android-34.jar` | Android SDK 的 platforms/android-34/android.jar |
| `dx.jar` | 用 AOSP `platform/dalvik` 的 `dx/src`（android-11.0.0_r1）编译 |
| `signv2.jar` | 用本项目 `tools/signer/` 下的源码编译，`Main-Class: SignV2` |

另外需要 JDK（javac/keytool）和 Python 3（打包脚本和图标生成需要 Pillow）。

签名密钥默认放在 `keystore/release.p12`，这个目录不随源码提供；第一次运行 `build.sh` 时会自动生成新密钥。注意：用不同密钥打出的安装包不能覆盖安装旧版本，需要先卸载。

## 已知限制

- 只在电脑上测试过判断逻辑，尚未在真机上验证
- 离开座位时既没带手机、也没开摄像头，App 会以为你还坐着（可以点“我刚活动过”）
- 站着办公但不走动时，计步无法区分站和坐
- 国产手机需要手动允许自启动、后台运行、电池策略“不限制”，否则可能被系统关闭
