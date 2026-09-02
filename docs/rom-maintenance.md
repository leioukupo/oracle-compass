# Android 5.1 ROM 维护手册

这台设备是 Android 5.1、32 位 MTK 终端。维护时优先收集证据和保留回滚命令，不删除系统 APK，不修改 SELinux，也不强制改变 CPU/GPU governor。

## 只读审计

先把输出保存到主机，再做任何停用操作：

```sh
adb shell getprop > rom-getprop.txt
adb shell pm list packages -s > rom-system-packages.txt
adb shell pm list packages -3 > rom-third-party-packages.txt
adb shell dumpsys package > rom-package-dump.txt
adb shell dumpsys activity services > rom-services.txt
adb shell dumpsys sensorservice > rom-sensors.txt
adb shell dumpsys location > rom-location.txt
adb shell 'cat /proc/loadavg; cat /proc/meminfo' > rom-runtime.txt
```

需要 root 时，可以在设备 shell 中额外收集：

```sh
su -c 'for p in com.adups.fota com.adups.fota.sysoper; do
  echo "=== $p ===";
  pm path "$p" 2>&1;
  dumpsys package "$p" | grep -E "Package\[|enabled=|stopped=";
done'
```

建议同时记录当前启用状态。不要把序列号、MAC、IP、密码、API Key 或 Magisk 数据库提交到仓库。

## 保守停用 OTA

只有确认设备不依赖厂商 OTA 后才停用，并且一次只处理一个包：

```sh
su -c 'pm disable-user --user 0 com.adups.fota'
pm list packages -d | grep com.adups.fota
```

如果设备仍有对应的 `com.adups.fota.sysoper` 且确认它只是 OTA 运营组件，再单独处理：

```sh
su -c 'pm disable-user --user 0 com.adups.fota.sysoper'
```

恢复命令：

```sh
su -c 'pm enable com.adups.fota'
su -c 'pm enable com.adups.fota.sysoper'
```

如果包名不存在，命令返回失败即可，不要根据相似名称扩大停用范围。天气、OMACP、厂商语音解锁等组件先保持启用，必须完成启动、设置、输入法、相机、语音、网盘和浏览器回归后再评估。

## 应用侧资源策略

- 对话和链路日志使用尾部读取与轮转，单文件上限为 4 MiB，最多保留两个旧文件。
- `last_voice.wav` 仅在超过 7 天后清理；上传中的 APK 和临时日志超过 24 小时才清理。
- 屏幕策略默认“插电常亮、拔电空闲约 60 秒后允许熄屏”。屏幕推流期间单独保持亮屏，连接断开后释放。
- Wi-Fi/IP 粗定位使用缓存、BSSID 变化和退避，正常刷新约 12 分钟，不再每 30 秒主动扫描。
- Web 控制台状态轮询在页面隐藏时停止；屏幕、摄像头和触摸流需要管理会话。

## 升级与回滚顺序

1. 先保存只读审计输出和当前 Prefs 备份。
2. 先安装 APK，确认 Web、ADB、FRP、语音和网盘可用。
3. 再停用单个 OTA 组件，冷启动和亮灭屏测试一次。
4. 出现异常立即执行 `pm enable <package>`，重启并重新回归。
5. 不执行 `pm uninstall --user 0`、删除 `/system` APK、刷写未知分区或开放任意 root 命令。
