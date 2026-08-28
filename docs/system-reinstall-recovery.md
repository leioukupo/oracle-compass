# C110001 系统重装恢复手册

本文用于 MAGNEO C110001（MT6580、Android 5.1/API 22、800x800 圆屏）恢复出厂或重刷系统后，重新恢复真理罗盘、Root、配置、远程维护和开关机资源。

这不是完整 ROM 刷机包。仓库只保存 App 源码和本机原厂启动资源备份，不能代替匹配硬件版本的官方 ROM、scatter、`boot.img`、NVRAM 或射频校准数据。

## 1. 先判断重装类型

- **恢复出厂/清除 data**：通常会删除 App、配置、Wi-Fi、ADB 授权和 Magisk Manager 数据；是否保留 Root、内部存储和 Magisk 模块取决于清除方式。
- **完整刷回官方 ROM**：通常还会覆盖 `boot`、`system`、`logo`，因此 Root、开机动画模块和自定义静态首屏都需要重做。
- **只重装 APK**：不需要执行系统恢复步骤，直接通过 Web 控制台上传新版 APK 并本地安装。

完整刷机必须使用同一主板版本的固件。不要使用 `Format All + Download`，不要在没有可靠备份时格式化或覆盖 NVRAM/NVDATA、PROTECT、PROINFO、PERSIST 等设备校准分区，也不要随意刷入其它硬件版本的 preloader。

## 2. 重装前必须保存的内容

### 2.1 私有配置

在 Web 控制台进入 `记录 / 备份 -> 配置备份`，执行“导出当前配置”，把完整 JSON 保存到加密的私有存储。设备上自动生成的副本位于：

```text
/sdcard/oracle-compass-backup/prefs.json
```

这个文件包含大模型/API Key、ASR/TTS 地址、MCP Token、FRPC 配置、网盘账号密码和 Web 管理密码哈希，必须按密钥文件保护：

- 不要提交到 Git 仓库。
- 不要放到公开网盘或聊天记录。
- 重装完成并确认恢复后，删除不再需要的明文临时副本。

同时单独记录 Wi-Fi 名称、FRP 服务端信息和 GitHub Release 下载方式。系统保存的 Wi-Fi 密码不属于 App 配置，刷机后通常需要重新输入。

### 2.2 原厂启动资源

在 Web 控制台进入 `记录 / 备份 -> 启动资源`，确认以下文件都存在并下载一份离线副本：

- `logo-original.bin.gz`
- `bootanimation-original.zip`
- `shutanimation-original.zip`
- `manifest.json`
- `SHA256SUMS`

仓库也保存了这台 C110001 的恢复副本。重装前在主机验证：

```bash
cd device-backups/C110001/stock
sha256sum -c SHA256SUMS
```

只有全部显示 `OK` 才能用于恢复。`logo` 是整分区镜像，仅适用于同一 C110001 硬件版本。

### 2.3 其它建议备份

- 匹配本机版本的官方 ROM 和 scatter。
- 原始 `boot.img` 及其 Magisk 修补方式。
- 当前 Magisk 模块列表。
- 必须保留的用户文件、下载内容和音乐。
- 如需完整系统级灾难恢复，另行备份设备特有的校准分区；不要把这些二进制提交到公开仓库。

## 3. 刷机或恢复出厂

1. 电量保持在 50% 以上，优先接电操作。
2. 完整刷机时使用确认匹配 C110001 主板版本的官方固件和 scatter。
3. 优先使用不会格式化设备校准区的模式；除非有明确恢复方案，不刷 preloader、不执行全盘格式化。
4. 第一次启动先让 Android 完整进入系统，确认触摸、显示、Wi-Fi、声音、相机和存储正常。
5. 重新连接 Wi-Fi，并校准系统日期和时间。

如果系统无法正常启动，先恢复官方 ROM。不要在系统、Root 和供电仍不稳定时刷写自定义 `logo`。

## 4. 恢复 Root 和基础调试

完整刷机会覆盖 Magisk 修补过的 `boot`。使用该 ROM 对应的原始 `boot.img` 按原来的方式重新安装 Magisk；本仓库不提供通用 `boot.img`。

恢复后验证：

```bash
adb shell getprop ro.build.version.sdk
adb shell getprop ro.product.model
adb shell su -c id
```

预期 API 为 `22`，设备型号为 C110001/MAGNEO，`su -c id` 中包含 `uid=0`。如果只做了恢复出厂且 Root 仍在，可能只需要重新安装 Magisk Manager。

开发者选项中重新开启 USB 调试，并在设备上确认当前主机的 ADB 授权。刷机后旧的 `/data/misc/adb/adb_keys` 通常已经丢失，首次连接必须重新授权。

## 5. 首次安装真理罗盘

从 GitHub Releases 下载与目标版本对应的文件：

- `oracle-compass-v<版本>.apk`
- `oracle-compass-bootanimation-v<版本>.zip`
- `SHA256SUMS.txt`

先验证下载文件：

```bash
sha256sum -c SHA256SUMS.txt --ignore-missing
```

重装后的设备还没有真理罗盘 Web 服务，首次 APK 安装可选择：

1. 用设备浏览器直接打开 GitHub Release 或可信的局域网 HTTP 地址安装。
2. 在主机临时启动 HTTP 服务，设备浏览器访问主机地址：

   ```bash
   python3 -m http.server 8000 --directory /path/to/release
   ```

3. 如果浏览器不可用，首次引导可以使用 USB ADB：

   ```bash
   adb install -r oracle-compass-v<版本>.apk
   ```

安装前按系统提示开启“未知来源”。真理罗盘安装完成后，后续升级继续使用 Web 控制台 HTTP 上传和设备本地安装，不需要用 `adb install` 传 APK。

## 6. 恢复 App 配置

推荐在第一次打开 App **之前**，把私有 `prefs.json` 放回：

```text
/sdcard/oracle-compass-backup/prefs.json
```

可以通过 USB/MTP 复制；文件很小，也可以在首次引导时使用 ADB：

```bash
adb shell mkdir -p /sdcard/oracle-compass-backup
adb push /path/to/private/prefs.json /sdcard/oracle-compass-backup/prefs.json
```

真理罗盘主页面启动时会自动导入该文件。已经打开过 App 也没关系，可以在本机浏览器访问：

```text
http://<设备局域网IP>:8080/
```

然后进入 `记录 / 备份 -> 配置备份`，粘贴备份 JSON 并执行恢复。没有旧备份时，先在 Web 页面设置新的管理密码，再手动填写配置。

恢复后依次确认：

- 大模型 Base URL、模型名、API Key、Max Tokens 和思考强度。
- 快速 ASR、最终 ASR、TTS 模型和音色。
- 常驻监听、软打断和交互模式。
- MCP Server、Token 和工具刷新结果。
- 网盘连接、浏览器、摄像头和推流参数。
- FRPC 配置、ADB TCP 自启和端口。

建议在 Web 中分别运行 LLM、Final ASR、TTS voices、TTS 和 MCP 测试。语音后端需要重装时参见 [TTS/ASR 部署手册](tts-asr-deploy.md)。

## 7. 恢复默认桌面和系统行为

1. 打开真理罗盘 `设置 -> 桌面 -> 设为默认桌面`。
2. 在系统 HOME 选择器中选择真理罗盘并设为始终使用。
3. 暂时保留原厂 Launcher，确认真理罗盘能稳定启动后再安装开机动画模块。
4. Web 控制台中确认：
   - `启用系统锁屏`：专用终端通常关闭。
   - `Root 授权提示`：按需要开启或关闭。
5. 如果设备设置了 PIN、图案或密码，App 不会绕过安全锁屏；先在系统中移除安全凭据，再关闭滑动锁。

可用下面的命令检查当前状态：

```bash
adb shell dumpsys window policy | grep mShowingLockscreen
adb shell dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'
```

正常状态应为 `mShowingLockscreen=false`，前台窗口是 `com.magneo.compass/.MainActivity`。

## 8. 恢复开机和关机动画

确认 Magisk 和真理罗盘主页面都正常后，在 Magisk 中安装 Release 的：

```text
oracle-compass-bootanimation-v<版本>.zip
```

模块会 systemless 覆盖：

- `/system/media/bootanimation.zip`
- `/system/media/shutanimation.zip`

同时负责非安全锁屏兼容、HOME 预加载和开机动画到主页面的衔接。安装后重启一次，在 Web `启动日志` 中检查 `/data/local/oracle-compass-boot.log`。

不要把关机画面误认为 `logo` 分区中的某个槽位：这台 ROM 的 Android 关机动画是独立的 `shutanimation.zip`，必须由模块单独覆盖。

## 9. 恢复静态上电首屏（可选，高风险）

MTK `logo` 是独立物理分区。完整刷回官方 ROM 后，它通常恢复为原厂画面；恢复出厂一般不会改它。

### 9.1 先建立可信原厂备份

- 如果刚刷完确认无误的官方 ROM，先在 Web `启动资源` 中执行“创建原厂备份”，再改静态首屏。
- 如果只是清除了 data，而物理 `logo` 仍是自定义画面，不要把当前分区重新标记为“原厂”。应使用 `device-backups/C110001/stock/logo-original.bin.gz` 作为恢复源。

第二种情况可在主机执行下面的受认证导入。先在 Web 中设置管理密码；命令只把可信原厂镜像放回 App 的备份区，**不会写物理分区**：

```bash
BASE='http://<设备局域网IP>:8080'
gzip -dc device-backups/C110001/stock/logo-original.bin.gz > /tmp/logo-original.bin
SHA=$(sha256sum /tmp/logo-original.bin | awk '{print $1}')

read -rsp 'Web 管理密码: ' WEB_PASSWORD; echo
TOKEN=$(printf '%s' "$WEB_PASSWORD" | \
  curl -fsS -X POST --data-urlencode password@- "$BASE/appmgr/login" | \
  python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
unset WEB_PASSWORD

curl -fsS -X POST \
  -H "X-AppMgr-Token: $TOKEN" \
  -H "X-File-Sha256: $SHA" \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @/tmp/logo-original.bin \
  "$BASE/boot-assets/upload-original-logo"

unset TOKEN SHA
shred -u /tmp/logo-original.bin
```

导入成功后重新打开 Web `启动资源`，确认“logo 原厂”的容量和 SHA 与仓库 `manifest.json` 一致。

### 9.2 生成完整自定义 logo.bin

使用固定版本的 `mtklogo`、仓库配置和 Release 中的首帧：

```bash
gzip -dc device-backups/C110001/stock/logo-original.bin.gz > /tmp/logo-original.bin

tools/build-c110001-logo.sh /path/to/mtklogo \
  /tmp/logo-original.bin \
  /path/to/oracle-compass-first-frame.jpg \
  /tmp/logo-custom.bin
```

`mtklogo` 应使用 `e97f51944be9f6dbafff5b1d619341e1fa97dc4c`。脚本只替换槽位 `0` 和 `38`，校验其它 37 个槽位未改变，将结果补齐到原分区大小，并预交换红蓝通道以补偿本机 bootloader 的颜色顺序。

### 9.3 通过 Web 刷写

1. 保持电量超过 50% 或正在充电。
2. 打开 Web `记录 / 备份 -> 启动资源`。
3. 确认原厂 `logo`、开机动画、关机动画及清单都存在。
4. 上传完整的 `/tmp/logo-custom.bin`，不要上传 PNG、JPEG 或单独槽位。
5. 核对容量和 SHA 后执行“刷写新首屏”。
6. Web 会整分区回读并验证 SHA；校验失败时不要重启，先恢复原厂首屏。

静态 `logo` 刷写不是恢复 App 的必要步骤。系统、Root 或供电不稳定时应跳过。

## 10. 恢复 FRP 和公网 ADB

配置恢复并重启后，远程守护按以下顺序启动：

```text
Wi-Fi -> Android 启动完成 -> 本地 adbd AUTH 校验 -> FRPC
```

因此公网 Web/ADB 会比系统亮屏晚一段时间出现，这是正常的。确认 Web 已在线、FRPC 已登录后，再执行一次：

```bash
adb connect <公网主机>:<ADB远程端口>
adb -s <公网主机>:<ADB远程端口> get-state
```

预期返回 `device`。不要在启动阶段并发反复执行 `adb connect`。Web `FRP / ADB` 中应显示：

- `服务正常`
- `协议=AUTH` 或 `active transport`
- `开机同步=完成`
- `CLOSE_WAIT=0`

如果首次恢复后仍显示 `offline`，先断开主机旧会话，再在 Web 中只执行一次“启动 ADB TCP”，等待几秒后重新连接：

```bash
adb disconnect <公网主机>:<ADB远程端口>
adb connect <公网主机>:<ADB远程端口>
```

## 11. 可选系统清理

恢复出厂或完整刷机后，原厂 NeoBear/儿童组件可能重新启用并造成后台负载。按 [儿童机制与重置操作手册](%E5%8E%BB%E9%99%A4%E5%84%BF%E7%AB%A5%E6%9C%BA%E5%88%B6-%E9%87%8D%E7%BD%AE%E6%93%8D%E4%BD%9C%E6%89%8B%E5%86%8C.md) 的包名清单重新禁用或对当前用户卸载，不要盲目删除未知系统包。

## 12. 最终验收清单

- [ ] Android API 22、型号和触摸/显示/声音正常。
- [ ] Magisk Root 可用，Root 提示符合 Web 设置。
- [ ] 真理罗盘为默认 HOME，重启后直接进入主盘。
- [ ] 灭屏/亮屏不会再次出现滑动锁。
- [ ] `prefs.json` 已恢复，且私有临时副本已妥善保存或清理。
- [ ] LLM、快速 ASR、最终 ASR、TTS 和 MCP 单项测试通过。
- [ ] 常驻监听、语音打断、摄像头、RTSP 和网盘可用。
- [ ] 本地 Web、FRPC 和公网 ADB 均稳定。
- [ ] 公网 ADB 为 `device`，`CLOSE_WAIT=0`。
- [ ] 开机动画、关机动画和主页面衔接正常。
- [ ] 如刷写静态首屏，整分区回读 SHA 已通过。

## 13. 回滚

- **恢复原厂静态首屏**：Web `启动资源 -> 恢复原厂首屏`。
- **恢复原厂开关机动画**：在 Magisk 中停用或卸载 `oracle_compass_bootanimation` 模块后重启。
- **恢复原厂桌面和滑动锁**：

  ```bash
  adb shell su -c "service call lock_settings 1 s16 lockscreen.disabled i32 0 i32 0"
  adb shell su -c 'pm enable fr.neamar.kiss/.MainActivity'
  adb shell su -c 'pm enable com.android.launcher3/.Launcher'
  ```

- **恢复 App 配置**：重新导入确认过的私有 `prefs.json`。
- **系统仍不稳定**：停止自定义分区操作，回到匹配硬件版本的官方 ROM 和原始 `boot.img`。
