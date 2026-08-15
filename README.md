# 真理罗盘（MAGNEO C110001）

面向 MT6580 / Android 5.1 / 800×800 圆屏的桌面级 App：罗盘桌面 + 全传感器 + 大模型（视觉/语音）+ 内置浏览器 + 网络文件系统（FTP/WebDAV/SMB）。

## 构建

环境：JDK 21、Gradle 8.14.3、Android SDK（platform-36、build-tools 36.1.0、NDK 27.0.12077973、CMake 3.22.1）。

```bash
export JAVA_HOME=/root/android/jdk
export ANDROID_HOME=/root/android/sdk
cd /root/oracle-compass
# 首次需要 espeak-ng 源码（本地中文 TTS）：
git clone --depth 1 https://github.com/espeak-ng/espeak-ng.git third_party/espeak-ng
# 生成语音数据（主机版）与 Android 静态库：
cmake -B third_party/espeak-ng/build-host -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release
cmake --build third_party/espeak-ng/build-host -j8
cmake -B third_party/espeak-ng/build-android \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/27.0.12077973/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=armeabi-v7a -DANDROID_PLATFORM=android-22 \
  -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS=-fPIC -DCMAKE_CXX_FLAGS=-fPIC
cmake --build third_party/espeak-ng/build-android -j8
# 打包数据与库（脚本已按 cmn/en 精简）：
mkdir -p app/src/main/cpp/prebuilt/armeabi-v7a app/src/main/assets/espeak-ng-data
cp -r third_party/espeak-ng/build-host/espeak-ng-data/{lang,intonations,phondata,phondata-manifest,phonindex,phontab} app/src/main/assets/espeak-ng-data/
cp third_party/espeak-ng/build-host/espeak-ng-data/{cmn_dict,en_dict} app/src/main/assets/espeak-ng-data/
cp -r third_party/espeak-ng/build-host/espeak-ng-data/voices/!v app/src/main/assets/espeak-ng-data/voices/
cp third_party/espeak-ng/build-android/src/libespeak-ng/libespeak-ng.a app/src/main/cpp/prebuilt/armeabi-v7a/
cp third_party/espeak-ng/build-android/src/ucd-tools/libucd.a app/src/main/cpp/prebuilt/armeabi-v7a/
cp third_party/espeak-ng/build-android/src/speechPlayer/libspeechPlayer.a app/src/main/cpp/prebuilt/armeabi-v7a/
cp third_party/espeak-ng/COPYING app/src/main/assets/GPLv3.txt

gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用

- 安装后打开即罗盘桌面；`设置 → 设为默认桌面` 可设为 HOME。
- `设置`页：填大模型 Provider（默认通义，兼容 OpenAI 协议）、语音 API（ASR/TTS 独立配置）。
- 罗盘外圈八区：应用抽屉 / 网盘 / 设置 / 语音 / 音乐 / 灵眼(视觉) / 浏览 / 详情。
- 绕圈滑动：按住屏幕任意位置沿圆周滑动，中央放大显示对应功能，松手即打开；点按外圈或中心（语音）仍可用。
- 语音：按住设备功能键说话松开发送；点中央太极开关对话；设置内可开持续监听（VAD）。
- 本地 TTS：eSpeak NG（普通话/英文离线），同时注册为系统 TTS 引擎“真理罗盘 TTS”。
- 网盘：`网盘`区添加 FTP/WebDAV/SMB 连接，可浏览/下载/上传/流式播放。
- 浏览器：圆形视口 WebView，地址栏+Go、书签、历史、下载、UA 切换；无多余按钮（返回=物理返回键）。

## 说明

- eSpeak NG 为 GPLv3，许可文件随 APK 附带（assets/GPLv3.txt）。
- 设备触摸屏存在物理盲区（边角/部分点不响应），扇区触摸带已放宽适配。
- 密码明文存 SharedPreferences（API 22 无 Keystore AES），仅限个人设备使用。

## 圆屏 UI 设计规范

800×800 / 物理直径 92mm / 系统 density 320（真实物理 dpi≈220）。圆屏适配要点：

- 物理：可见半径 R=400px=46mm；1dp≈0.229mm（物理上 1dp≈0.159mm，本机物理放大 ≈1.45×）；四角裁切深度约 19mm。
- 范式（参考系统 `com.android.music` / `com.android.settings` 实测 bounds）：
  - 顶/底带只放单个居中主键 + 居中 "⋯" overflow 圆键（弹 `RoundDialog` 收纳次键），不横排多键。
  - 中带用大圆形主内容（Music 式 300×300 圆封面）或 ListView 沿圆中带滚动（Settings 范式，靠物理玻璃自然裁）。
  - 真圆屏通常不需在 app 内再套 oval mask（物理玻璃已裁），仅在 ListView/GridView 触控防误命中角点时套 `OutlineUtil.oval(v)`。
- 工具：`com.magneo.compass.ui.RoundScreen`（`R`、`safeHalfWidthAt(y)`、`maxCellHalf(r, angle, R)`）、`RoundFrame`、`OutlineUtil`、`Ui.dp`。详见 [设备说明.md](设备说明.md)。

## 已知限制

- 本机 MAGNEO ROM 有全局“防误退”机制（checkAllowQuitState / isAllowQuit=false），返回键被框架拦截。本 App 通过 BaseActivity 覆写 dispatchKeyEvent 直接 finish() 绕过，物理返回键可正常退出；浏览器已无内置返回按钮，其余页面保留“◀ 返回”按钮兜底。

> 儿童机制与防退的排查/处理详见 [docs/去除儿童机制-重置操作手册.md](docs/去除儿童机制-重置操作手册.md)
