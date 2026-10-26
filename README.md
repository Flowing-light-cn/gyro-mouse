# 陀螺仪鼠标（Android → Windows 蓝牙 HID 鼠标）

手机通过蓝牙把自己注册成**标准 HID 鼠标**，Windows 免驱直接识别。鼠标移动由手机重力方向
（`TYPE_GRAVITY`，系统融合加速度计+陀螺仪）控制：手机哪边向下倾斜，指针就往哪边走，
倾斜越大移动越快。屏幕下方提供左键、右键、中键、滚轮和侧键（后退/前进）。

界面支持**简体中文 / English**，在「设置 → 语言」中随时切换，默认跟随系统语言。

- 手机端：Android 9（API 28）及以上，需支持经典蓝牙 HID Device profile
- 电脑端：Windows 10/11，**无需安装任何软件或驱动**

## 下载

- 最新安装包：[Releases 页面](https://github.com/Flowing-light-cn/gyro-mouse/releases/latest)，
  或直接下载 [`app-release.apk`](https://github.com/Flowing-light-cn/gyro-mouse/releases/latest/download/app-release.apk)
- 当前版本 **v0.2.0**（versionCode 3），使用调试密钥签名，仅供测试体验
- 想自己从源码编译，见下方「构建」

## 安装

1. 下载上面的 `app-release.apk`，传到手机（数据线、微信/QQ 文件传输均可）。
2. 手机上允许“安装未知来源应用”，安装并打开。
3. 首次打开授予“附近的设备（蓝牙）”权限；Android 13+ 还会请求通知权限。

## 使用步骤

1. 打开手机蓝牙。
2. 打开 App，等待状态显示“HID 已注册，等待电脑连接”。
3. 点“电脑发现本机”（让手机进入可被发现状态，300 秒）。
4. 电脑上：**设置 → 蓝牙和其他设备 → 添加设备 → 蓝牙 → 选择你的手机**。
   配对完成后，状态会变成绿色的“已连接：电脑名”。
   - 如果电脑之前和这台手机配对过（当普通蓝牙设备），请先在电脑上“删除设备”，再重新配对，
     否则 Windows 可能不会加载 HID 鼠标服务。
5. 倾斜手机移动指针：
   - 手机平放 = 不动；哪一边向下倾斜，指针就往哪边移动；倾斜角度越大速度越快。
   - 启动时会把当时的姿态当作零点。手持角度不顺手时，把手机摆到舒服的角度，点“校准中位”。
6. 按钮面板：
   - 左键 / 右键：按下保持（可拖拽），抬起释放
   - 滚轮区：上下滑动滚动，轻点 = 中键
   - 后退 / 前进：鼠标侧键（浏览器后退/前进）

## 调节手感

- 左上角「设置」打开设置弹窗。
- **语言**：设置弹窗顶部可选「跟随系统 / 中文 / English」，点选后界面立即切换。
- **鼠标灵敏度**滑块：300–3500 单位/秒，默认约 1580。觉得慢就右拉，觉得飘就左拉。
- **滚轮灵敏度**滑块：手指每滑动多少距离滚动一格，越靠右越灵敏（默认 34dp/格）。
- **陀螺仪反向**：手机倒置/反装握持时勾选，移动方向整体翻转。
- **编辑按钮布局**：进入编辑模式后，拖动任意按钮或倾角指示器调整位置，拖右下角圆点缩放；
  点“完成布局”保存，点“重置默认”恢复默认布局。
- **死区**固定 2.5°，**满速倾角**固定 30°，响应曲线指数 1.6（在 `TiltController.java` 中可改）。
- 指针漂移：重新点“校准中位”。
- 以上设置和布局都会自动保存。

## 语言（中文 / English）

- 默认「跟随系统」：系统语言是中文时显示简体中文，其它语言显示英文。
- 「设置 → 语言」里可强制选择「跟随系统 / 中文 / English」，点选后立即生效，下次启动仍然保留。
- 切换语言不会断开蓝牙连接，已配对的电脑无需重新配对；常驻通知的文字也会同步刷新。
- 桌面图标名称由系统按系统语言显示；应用内界面、弹窗、Toast 与常驻通知都会随所选语言切换。
- 文案位于 `app/src/main/res/values/strings.xml`（英文，默认）与 `values-zh/strings.xml`（简体中文），
  代码里统一 `getString(R.string.xxx)`；服务的状态文本以资源 id 传给界面，切换语言后不会残留旧语言。
- 语言逻辑见 `I18n.java`：Activity / Service 在 `attachBaseContext` 中换成带目标语言的 Context。

## 已知限制（初始版本）

- 仅竖屏使用（已锁定竖屏），横屏映射未做。
- 需要 Android 9+；个别厂商 ROM 可能裁剪了 `BluetoothHidDevice`，需真机验证。
- 依赖 `TYPE_GRAVITY` 虚拟传感器；极少数设备没有该传感器时会提示不可用。
- 中键为“轻点滚轮区”，不支持按住中键拖拽。
- 前台服务常驻通知用于保活，属正常现象。

## 构建

需要 JDK 17+、Android SDK Platform 34 + Build-Tools 34.0.0、Gradle 8.7。

```powershell
.\build.ps1
```

或手动：

```powershell
$env:ANDROID_HOME = "$PWD\.tools\sdk"
$env:GRADLE_USER_HOME = "$PWD\.tools\gradle-home"
& "$PWD\.tools\gradle-8.7\bin\gradle.bat" -p "$PWD" assembleRelease
```

产物：`app\build\outputs\apk\release\app-release-unsigned.apk`。
用本机已生成的调试密钥签名（`.tools\debug.keystore`，密码 `android`）：

```powershell
.\sign.ps1
```

也可手动执行（脚本按自身位置定位文件，所以在哪个目录执行都行；手动命令则要求当前目录就是本项目）：

```powershell
$env:ANDROID_HOME = "$PWD\.tools\sdk"
& "$env:ANDROID_HOME\build-tools\34.0.0\apksigner.bat" sign --ks "$PWD\.tools\debug.keystore" --ks-pass pass:android --key-pass pass:android --ks-key-alias androiddebugkey --out "app\build\outputs\apk\release\app-release.apk" "app\build\outputs\apk\release\app-release-unsigned.apk"
```

## 目录

```
app/src/main/java/com/gyromouse/mouse/
  MainActivity.java       权限、界面、状态显示、设置（灵敏度/语言/校准）入口
  HidMouseService.java    前台服务、HID 注册、鼠标报告发送
  TiltController.java     重力→倾角→速度映射
  ControlPanelView.java   底部按钮面板与滚轮触摸处理
  I18n.java               语言模式（跟随系统/中文/English）与 Context 包装
app/src/main/res/
  values/strings.xml      默认（英文）文案
  values-zh/strings.xml   简体中文文案
```
