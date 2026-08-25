# GPS Diagnostic Log

## 2026-08-25 Baseline

Device: MT6580 / Android 5.1 / rooted.

Current conclusion: GPS software stack is being requested and the MTK GPS driver is active, but the receiver is not getting usable satellite signal. This points more strongly to antenna/RF/hardware reception than to app-side location code.

Observed state:

- App requests GPS through Android `LocationManager`.
- System location setting is enabled: `location_mode=3`, `location_providers_allowed=gps,network`.
- MTK GPS services are running: `mnld`, `mtk_agpsd`, `wifi2agps`.
- GPS driver wakes correctly: `pwrctl=1`, `state=2`.
- A-GPS/SUPL network is reachable; `supl.qxwz.com:7276` and `ntp.aliyun.com` respond.
- Assistance/cache files were regenerated after reset: `EPO.DAT`, `BEE.BIN`, `ARC.BIN`, `mtkgps.dat`.
- NMEA output continues once per second, but reports no valid fix:
  - `GPGGA ... fix=0`
  - `GPRMC ... V`
  - `GPGSA,A,1`
  - often `GPGSV,1,1,0`
- MTK YGPS cross-check also showed `Satellite Count: 0`.
- Logcat repeatedly shows `MTK_GPS_MSG_FIX_READY,GET_RTC_FAIL`; current NMEA date is correct after reset, so this is not by itself enough to explain the no-fix state.

Actions already performed:

- Added app-side GPS cold-start command using `delete_aiding_data`.
- Added time/XTRA injection request.
- Added Web hardware self-test GPS status/action/driver display.
- Added Web `GPS 冷启动` action.
- Backed up original GPS cache/state files to:
  `/data/misc/gps_backup_20260825_125706`
- Cleared and regenerated MTK GPS cache files.
- Rebooted device after reset.
- Opened MTK YGPS for cross-check, then stopped it to avoid extra GPS drain.

## Outdoor Open-Sky Test Plan

Test location: open outdoor area, away from building walls, metal railings, cars, and dense tree cover.

Recommended steps:

1. Open the app.
2. Enter `坤 · 详情` -> `卫星星图`, or open Web console hardware self-test.
3. Tap `GPS 冷启动` once.
4. Keep the screen facing skyward and avoid covering the top/back area where the antenna may be.
5. Wait at least 10-15 minutes without moving too much.
6. Record these values:
   - visible satellite count
   - used satellite count
   - max SNR
   - whether latitude/longitude appears
   - whether YGPS also sees satellites, if opened

Interpretation:

- `0-2` visible satellites after 10-15 minutes outdoors: likely GPS antenna/RF hardware issue.
- `4+` visible satellites but `0` used satellites: likely HAL/A-GPS/ephemeris/config issue.
- `6+` visible satellites with max SNR above `30`, but no fix after several minutes: investigate MTK GPS HAL and aiding data further.
- Weak SNR, mostly below `20`: likely antenna placement/contact/shielding problem.
- Normal outdoor fix should usually show multiple satellites and valid coordinates within several minutes after cold start.

## Follow-Up Candidates

- If outdoor test still shows `0-2` satellites, inspect GPS antenna/contact/flex/shielding before spending more time on software.
- If satellites are visible but not used, capture fresh `logcat` filtered by `gps|mnl|agps|supl|nmea` and compare YGPS versus app output.
- After diagnosis, turn off verbose NMEA debug in `/data/misc/mnl.prop` to reduce log spam and battery drain.

## 2026-08-25 Outdoor Result

Outdoor open-sky capture files on device:

- `/sdcard/oracle-gps/gps_capture_20260825_212604_summary.txt`
- `/sdcard/oracle-gps/gps_capture_20260825_212604_logcat.txt`

Capture span:

- Start: `2026-08-25 21:26:04 CST`
- Last pulled sample: `2026-08-25 21:45:43 CST`
- Samples: `111`
- NMEA lines analyzed: `751`

Result summary:

- `GPGSV` max visible satellites: `2`
- `GPGSV` distribution: `0 satellites = 677`, `2 satellites = 74`
- `GPGGA` fix quality: all `0`
- `GPGGA` used satellites: all `0`
- `GPRMC` status: all `V` invalid
- Android framework GNSS samples: max `2`, all `ephemerisMask=0`, all `almanacMask=0`
- YGPS cross-check during the same period kept reporting `Satellite Count:0`
- GPS driver was active for most samples: `pwrctl=1/state=2` in `94` samples, off in `17` samples
- SUPL/A-GPS did exchange data and eventually logged `assist data are inject done`

Interpretation:

The outdoor test still did not reach a usable satellite view. Because SUPL/A-GPS is reachable and assistance injection happened, but raw GPS never exceeded `2` visible satellites and never got ephemeris/almanac, this is now strongly biased toward GPS antenna/RF/hardware reception failure or severe antenna shielding/contact problems.

Next recommended action:

- Inspect GPS antenna/flex/contact/shielding if the device can be opened.
- If hardware inspection is not practical, treat built-in GPS as unreliable and use Wi-Fi/network location or an external GPS source.
- Before normal daily use, disable verbose GPS/NMEA debug in `/data/misc/mnl.prop` to reduce log spam and battery drain.

## 2026-08-25 Open-Cover Check

Condition: rear cover opened, MTK YGPS used as hardware-level GPS test.

Observed state:

- GPS driver powered on correctly: `pwrctl=1`, `state=2`.
- Android location request came from `com.mediatek.ygps`.
- YGPS still reported `Satellite Count:0`.
- NMEA remained invalid:
  - `GPGSV,1,1,0`
  - `GPGGA ... fix=0`
  - `GPRMC ... V`
- After stopping YGPS, GPS powered down correctly: `pwrctl=0`, `state=0`.

Interpretation:

Opening the cover did not improve GPS reception in this test. If the device rear cover carries the GPS antenna or presses the antenna contacts, testing with the cover open may actually disconnect or worsen the antenna path. The next meaningful hardware test is to restore/press the antenna contacts or temporarily attach a known GPS antenna to the board-side feed/GND points.
