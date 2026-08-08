package com.magneo.compass.web;

import android.content.Context;

import com.magneo.compass.ConversationLog;
import com.magneo.compass.Prefs;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Enumeration;

/** 局域网网页设置服务：其他设备浏览器打开 http://<IP>:8080 可配置应用（不含推流）。 */
public class SettingsWebServer {
    public static final int PORT = 8080;
    private static volatile ServerSocket server;
    private static volatile Thread thread;
    private static volatile Context app;

    public static synchronized void start(Context c) {
        if (server != null) return;
        app = c.getApplicationContext();
        for (int attempt = 0; attempt < 3 && server == null; attempt++) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);   // 快速重启时避免 TIME_WAIT 占用
                ss.bind(new java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT), 32);
                server = ss;
                thread = new Thread(SettingsWebServer::loop, "web-settings");
                thread.setDaemon(true);
                thread.start();
            } catch (Exception e) {
                server = null;
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
        }
    }

    public static android.content.Context getAppContext() { return app; }

    public static String url() {
        return "http://" + localIp() + ":" + PORT + "/";
    }

    public static String localIp() {
        String bestPrivate = null;
        try {
            Enumeration<NetworkInterface> ens = NetworkInterface.getNetworkInterfaces();
            while (ens.hasMoreElements()) {
                NetworkInterface ni = ens.nextElement();
                try { if (!ni.isUp()) continue; } catch (Exception ignored) {}
                String n = ni.getName();
                boolean lan = n != null && (n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("en"));
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a.isLoopbackAddress() || !(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (lan) return ip;
                    if (bestPrivate == null && isPrivate(ip)) bestPrivate = ip;
                }
            }
        } catch (Exception ignored) {}
        return bestPrivate != null ? bestPrivate : "127.0.0.1";
    }

    private static boolean isPrivate(String ip) {
        return ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.");
    }

    private static void loop() {
        while (server != null && !server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "web-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException ignored) {}
        }
    }

    private static void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "ISO-8859-1"));
            String req = r.readLine();
            if (req == null) return;
            String[] parts = req.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1].split("\\?")[0];

            int len = 0;
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    try { len = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {}
                }
            }
            String body = "";
            if (method.equals("POST") && len > 0) {
                char[] buf = new char[len];
                int off = 0;
                while (off < len) {
                    int n = r.read(buf, off, len - off);
                    if (n < 0) break;
                    off += n;
                }
                body = new String(buf, 0, off);
            }

            OutputStream out = s.getOutputStream();
            if (path.equals("/")) serveHtml(out);
            else if (path.equals("/status")) serveStatus(out);
            else if (path.equals("/conversations")) serveConversations(out);
            else if (path.equals("/clear_conv")) serveClearConv(out);
            else if (path.equals("/stream")) { serveStream(s); return; }
            else if (path.equals("/h264")) { serveH264(s); return; }
            else if (path.equals("/h264fast")) { serveH264Fast(s); return; }
            else if (path.equals("/stream_state")) serveStreamState(out);
            else if (path.equals("/system_status")) serveSystemStatus(out);
            else if (path.equals("/key")) { serveKey(out, parts[1]); return; }
            else if (path.equals("/touch")) { serveTouch(out, parts[1]); return; }
            else if (path.equals("/cam")) { serveCamPage(out); }
            else if (path.equals("/camhttp")) { com.magneo.compass.cam.CameraHttpStreamer.serve(s); return; }
            else if (path.equals("/cam/start")) { com.magneo.compass.cam.CameraStreamService.start(app); serveText(out, "正在启动摄像头推流…"); }
            else if (path.equals("/cam/stop")) { com.magneo.compass.cam.CameraStreamService.stop(app); serveText(out, "已停止"); }
            else if (path.equals("/cam/status")) serveCamStatus(out);
            else if (path.equals("/cam/offer")) serveCamOffer(out, body);
            else if (path.equals("/cam/answer")) serveCamAnswer(out);
            else if (path.equals("/frpc/status")) serveFrpcStatus(out);
            else if (path.equals("/frpc/start")) serveText(out, com.magneo.compass.frp.FrpcManager.start(app));
            else if (path.equals("/frpc/stop")) serveText(out, com.magneo.compass.frp.FrpcManager.stop());
            else if (path.equals("/save")) serveSave(out, body);
            else serve404(out);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private static void serveHtml(OutputStream out) throws IOException {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>真理罗盘 · 网页设置</title>"
                + "<style>body{background:#0d0b08;color:#e8dcc0;font-family:sans-serif;margin:0;padding:16px}"
                + "h1{color:#d4af37;text-align:center;font-size:20px}"
                + "fieldset{border:1px solid #6b5a2e;border-radius:12px;margin:10px 0;padding:10px}"
                + "legend{color:#d4af37}.row{margin:6px 0}label{display:inline-block;width:110px;color:#d4af37;font-size:13px}"
                + "input[type=text]{width:calc(100% - 130px);background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px}"
                + "button{background:#d4af37;color:#0d0b08;border:none;border-radius:8px;padding:8px 14px;margin:4px}"
                + ".navbtn{width:74px;height:42px;border-radius:21px;background:#171512;color:#d4af37;border:1px solid #6b5a2e;font-size:13px;margin:0 5px;cursor:pointer}"
                + ".navbtn:active{background:#d4af37;color:#0d0b08}"
                + ".ok{color:#8fbf6a}</style></head><body>"
                + "<h1>☯ 真理罗盘 · 网页设置</h1>"
                + "<form id='f' onsubmit='save();return false'>"
                + "<fieldset><legend>大模型</legend>"
                + "<div class='row'><label>Provider</label><input type='text' name='provider' id='provider'></div>"
                + "<div class='row'><label>API Key</label><input type='text' name='apiKey'></div>"
                + "<div class='row'><label>Base URL</label><input type='text' name='baseUrl'></div>"
                + "<div class='row'><label>文本模型</label><input type='text' name='textModel'></div>"
                + "<div class='row'><label>视觉模型</label><input type='text' name='visionModel'></div></fieldset>"
                + "<fieldset><legend>语音</legend>"
                + "<div class='row'><label>ASR 地址</label><input type='text' name='asrUrl'></div>"
                + "<div class='row'><label>ASR 模型</label><input type='text' name='asrModel'></div>"
                + "<div class='row'><label>TTS 地址</label><input type='text' name='ttsUrl'></div>"
                + "<div class='row'><label>TTS 模型</label><input type='text' name='ttsModel'></div>"
                + "<div class='row'><label>TTS 音色</label><input type='text' name='ttsVoice'></div>"
                + "<div class='row' style='margin-top:10px'><label style='width:100%'>语音系统提示词</label></div>"
                + "<textarea name='sysPromptVoice' style='width:calc(100% - 14px);height:64px;background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'></textarea>"
                + "</fieldset>"
                + "<fieldset><legend>视觉 / 监听 / 浏览器</legend>"
                + "<div class='row'><label>视觉间隔秒</label><input type='text' name='visionInterval'></div>"
                + "<div class='row'><label>VAD 灵敏度</label><input type='text' name='vadSensitivity'></div>"
                + "<div class='row'><label>搜索引擎</label><input type='text' name='searchEngine'></div>"
                + "<div class='row' style='margin-top:10px'><label style='width:100%'>视觉系统提示词</label></div>"
                + "<textarea name='sysPromptVision' style='width:calc(100% - 14px);height:80px;background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px'></textarea>"
                + "</fieldset>"
                + "<fieldset><legend>屏幕推流</legend>"
                + "<div class='row'><label>推流方式</label><select name='streamMode'>"
                + "<option value='h264'>H.264 硬编（采集慢）</option>"
                + "<option value='h264fast'>H.264 高速（虚拟显示）</option>"
                + "<option value='mjpeg'>MJPEG 兼容</option></select></div>"
                + "<div class='row'><label>帧率(fps)</label><select name='streamFps'>"
                + "<option>1</option><option>2</option><option>3</option><option>5</option></select></div>"
                + "<div class='row'><label>码率(Kbps)</label><select name='streamBitrate'>"
                + "<option value='600'>600</option><option value='1000'>1000</option>"
                + "<option value='1500'>1500</option><option value='2500'>2500</option>"
                + "<option value='4000'>4000</option><option value='6000'>6000</option>"
                + "<option value='8000'>8000(最大)</option></select></div>"
                + "<div class='row'><label>画质(MJPEG)</label><select name='streamQuality'>"
                + "<option value='30'>低</option><option value='55'>中</option><option value='75'>高</option></select></div>"
                + "<div class='row'><label>尺寸(MJPEG)</label><select name='streamScale'>"
                + "<option value='2'>半尺寸(400×400)</option><option value='1'>原始(800×800)</option></select></div>"
                + "<div style='text-align:center'><button type='button' onclick='toggleStream()' id='sbtn'>开始推流</button>"
                + "<span id='sstate' style='font-size:12px;color:#8fbf6a'></span></div>"
                + "<div style='display:flex;justify-content:center;gap:20px;flex-wrap:wrap;align-items:center'>"
                + "<div style='text-align:center'>"
                + "<div style='position:relative;width:92mm;height:92mm'>"
                + "<video id='h264v' muted autoplay playsinline style='width:100%;height:100%;"
                + "border-radius:50%;border:1px solid #6b5a2e;display:none;object-fit:cover;filter:brightness(1.55) contrast(1.15)'></video>"
                + "<img id='screen' style='width:100%;height:100%;border-radius:50%;"
                + "border:1px solid #6b5a2e;display:none;object-fit:cover;filter:brightness(1.55) contrast(1.15)'>"
                + "<div id='touchpad' style='position:absolute;inset:0;border-radius:50%;cursor:crosshair;touch-action:none;user-select:none'></div></div>"
                + "<div style='margin-top:8px;color:#8a8272;font-size:11px'>点击/长按/拖动圆面 → 远程操作设备屏幕</div>"
                + "<div style='margin-top:4px'>"
                + "<button type='button' class='navbtn' onclick='keyEvent(4)'>◀ 返回</button>"
                + "<button type='button' class='navbtn' onclick='keyEvent(3)'>● 桌面</button>"
                + "<button type='button' class='navbtn' onclick='keyEvent(187)'>▢ 最近</button>"
                + "<div id='tstat' style='color:#8fbf6a;font-size:11px;margin-top:4px;min-height:14px'></div></div></div>"
                + "<div style='width:92mm;height:92mm;border-radius:50%;border:1px solid #6b5a2e;background:#171512;position:relative;box-sizing:border-box'>"
                + "<div style='position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center'>"
                + "<div id='statTime' style='font-size:34px;color:#d4af37;font-weight:bold;font-family:monospace'></div>"
                + "<div id='statDate' style='font-size:12px;color:#e8dcc0;margin-top:2px'></div>"
                + "<div id='statCore' style='font-size:10px;color:#8fbf6a;margin-top:6px'></div>"
                + "<div id='statGps' style='font-size:10px;color:#d4af37;margin-top:3px'></div></div>"
                + "<div id='ring' style='position:absolute;inset:0'></div></div></div>"
                + "<div style='color:#8a8272;font-size:11px'>H.264 走 MT6580 硬件编码器（720×720），省 CPU、省带宽；MJPEG 为兼容模式。改参数先点保存。</div>"
                + "</fieldset>"
                + "<fieldset><legend>定位 API</legend>"
                + "<div class='row'><label>显示定位</label><input type='checkbox' name='showLoc' id='showLoc' style='width:auto;vertical-align:middle'>"
                + "<span style='font-size:11px;color:#8a8272'>显示 GPS / WiFi 定位信息（关闭后不再请求定位，省电）</span></div>"
                + "<div class='row'><label>WiFi 定位地址</label><input type='text' name='locWifiUrl'></div>"
                + "<div class='row'><label>IP 定位地址</label><input type='text' name='locIpUrl'></div>"
                + "<div style='color:#8a8272;font-size:11px'>WiFi：POST JSON {wifiAccessPoints:[{macAddress,signalStrength}]}，返回 {location:{lat,lng},accuracy}（MLS 格式，可换带自己 key 的地址）；IP：GET 返回 {status:success,lat,lon}。保存后下一轮定位（≤30s）生效。</div>"
                + "<fieldset><legend>摄像头推流</legend>"
                + "<div class='row'><label>摄像头</label><select name='camId'><option value='0'>后置（默认）</option><option value='1'>前置</option></select></div>"
                + "<div class='row'><label>分辨率</label><select name='camWidth'><option>640</option><option>800</option><option>1280</option></select> × "
                + "<select name='camHeight'><option>480</option><option>800</option><option>720</option></select></div>"
                + "<div style='color:#8a8272;font-size:11px'>相机支持尺寸：640×480（19fps 流畅，默认）、800×800（10fps 圆屏原生）、1280×720（7fps 高清）。选择不匹配时自动取最接近的支持尺寸。</div>"
                + "<div class='row'><label>帧率</label><select name='camFps'><option>24</option></select>"
                + "<span style='font-size:11px;color:#8a8272'>设备摄像头实际 16fps（硬件上限）</span></div>"
                + "<div class='row'><label>码率(Kbps)</label><select name='camBitrate'><option>2000</option><option>4000</option><option>5000</option><option>6000</option><option>8000</option><option>12000</option><option>20000</option></select></div>"
                + "<div style='color:#8a8272;font-size:11px'>码率影响 720p 帧率：2-5Mbps≈7-8fps（穿透推荐），20Mbps 只有 5fps。要帧率降分辨率，要画质提码率。</div>"
                + "<div class='row'><label>RTSP 端口</label><input type='text' name='rtspPort'></div>"
                + "<div class='row'><label>RTMP 地址</label><input type='text' name='rtmpUrl' placeholder='rtmp://VPS:1935/cam/stream（留空=不推）'></div>"
                + "<div class='row'><label>开机自动推流</label><input type='checkbox' name='camAutoStart' style='width:auto'>"
                + "<span style='font-size:11px;color:#8a8272'>应用启动时自动开始摄像头推流（默认开）</span></div>"
                + "<div style='text-align:center'><button type='button' onclick='camToggle()' id='camBtn'>启动推流</button>"
                + "<span id='camMsg'></span></div>"
                + "<div style='color:#8a8272;font-size:11px'>状态：<span id='camState'>未知</span>"
                + "<div id='camUrls' style='margin-top:4px'></div></div>"
                + "<div style='color:#8a8272;font-size:11px'>RTSP 用 VLC 等播放 <b>rtsp://设备IP:端口/cam</b>（建议选 TCP 传输）；网页播放 <a href='/cam' target='_blank' style='color:#d4af37'>点这里打开摄像头直播页</a>。已实测：720p 可达，摄像头回调硬件上限 16fps（60/30fps 目标自动降级），码率 VBR 最高按设置值。状态区会显示实际帧率。</div>"
                + "</fieldset>"
                + "<fieldset><legend>内网穿透 frpc</legend>"
                + "<div class='row'><label style='width:100%'>frpc.toml 配置（保存后生效）</label></div>"
                + "<textarea name='frpcConfig' rows='12' style='width:calc(100% - 14px);height:220px;background:#171512;color:#e8dcc0;border:1px solid #6b5a2e;border-radius:8px;padding:6px;font-family:monospace;font-size:12px'></textarea>"
                + "<div style='color:#8a8272;font-size:11px'>示例：serverAddr = '你的服务器IP' / serverPort = 7000 / auth.token = '密钥'，代理用 [[proxies]]：name='web' type='tcp' localIP='127.0.0.1' localPort=18080 remotePort=8080（详细格式见 frp 官方文档，改完先点保存）</div>"
                + "<div class='row'>状态：<span id='frpcState' style='color:#d4af37'>未知</span>"
                + "<span style='font-size:11px;color:#8a8272;margin-left:8px'>应用启动时自动运行（配置非空）</span></div>"
                + "<div style='text-align:center'><button type='button' onclick='frpcStart()'>启动 frpc</button>"
                + "<button type='button' onclick='frpcStop()'>停止 frpc</button>"
                + "<span id='frpcMsg'></span></div>"
                + "<div style='color:#8a8272;font-size:11px'>运行日志（最近部分，自动刷新）：</div>"
                + "<pre id='frpcLog' style='background:#171512;border:1px solid #6b5a2e;border-radius:10px;padding:8px;max-height:200px;overflow-y:auto;font-size:11px;white-space:pre-wrap;color:#8fbf6a'></pre>"
                + "</fieldset>"
                + "<fieldset><legend>对话记录</legend>"
                + "<div class='row'><label>大小上限(KB)</label><input type='text' name='convMaxKb'></div>"
                + "<div class='row'><label>清理间隔(分钟)</label><input type='text' name='convCleanMin'>"
                + "<div style='color:#8a8272;font-size:11px;margin-left:114px'>0=关闭定时清理（超出上限时写入仍会自动裁剪）</div></div>"
                + "<div style='text-align:center'><button type='button' onclick='clearConv()'>清空记录</button><span id='convMsg'></span></div>"
                + "<div id='conv' style='background:#171512;border:1px solid #6b5a2e;border-radius:10px;padding:8px;"
                + "max-height:320px;overflow-y:auto;font-size:12px;line-height:1.5'></div></fieldset>"
                + "<div style='text-align:center'><button type='submit'>保存设置</button><span id='msg' class='ok'></span></div>"
                + "</form>"
                + "<script>"
                + "function get(url,cb){var x=new XMLHttpRequest();x.open('GET',url,true);"
                + "x.onload=function(){try{cb(JSON.parse(x.responseText));}catch(e){cb(null);}};"
                + "x.onerror=function(){cb(null);};x.send();}"
                + "get('/status',function(d){if(!d)return;for(var k in d){var e=document.querySelector('[name='+k+']');if(!e)continue;"
                + "if(e.type==='checkbox'){e.checked=(d[k]===true||d[k]==='true');}else{e.value=d[k];}}"
                + "document.getElementById('provider').value=d.provider;"
                + "document.getElementById('msg').textContent='已加载设备当前配置';});"
                + "function save(){var b=new URLSearchParams(new FormData(document.getElementById('f')));"
                + "var cbs=document.querySelectorAll('input[type=checkbox]');for(var i=0;i<cbs.length;i++){b.set(cbs[i].name,cbs[i].checked?'true':'false');}"
                + "var x=new XMLHttpRequest();x.open('POST','/save',true);"
                + "x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');"
                + "x.onload=function(){document.getElementById('msg').textContent=x.responseText;};x.send(b.toString());}"
                + "function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;');}"
                + "function convHtml(d){var h='';var arr=d.entries||[];"
                + "for(var i=Math.max(0,arr.length-200);i<arr.length;i++){var e=arr[i];"
                + "var who=e.role==='user'?'你':(e.role==='assistant'?'AI':'系统');"
                + "var color=e.role==='error'?'#e74c3c':(e.role==='user'?'#d4af37':'#8fbf6a');"
                + "h+=\"<div style='margin:4px 0'><span style='color:#6b6b6b;font-size:10px'>\"+esc(e.ts)+'</span> <b style=\"color:'+color+'\">'+who+':</b> '+esc(e.text)+'</div>';}"
                + "if(arr.length===0)h='<div style=\"color:#8a8272\">暂无对话记录，用语音和罗盘对话后会显示在这里</div>';"
                + "h+=\"<div style='color:#6b6b6b;font-size:10px;margin-top:6px'>共 \"+arr.length+\" 条 · 文件 \"+d.sizeKb+\" KB / 上限 \"+d.maxKb+\" KB</div>\";return h;}"
                + "function loadConv(){get('/conversations',function(d){if(!d)return;var v=document.getElementById('conv');"
                + "v.innerHTML=convHtml(d);v.scrollTop=v.scrollHeight;});}"
                + "function clearConv(){var x=new XMLHttpRequest();x.open('POST','/clear_conv',true);"
                + "x.onload=function(){document.getElementById('convMsg').textContent='已清空';loadConv();};x.send();}"
                + "var streamOn=false,streamEnded=false;var mse=null,sb=null,abortCtl=null,watchdog=null,sess=0;"
                + "var boxBuf=new Uint8Array(0),boxOff=0,initDone=false,appending=false,pending=[],gotData=false;"
                + "function currentMode(){var e=document.querySelector('[name=streamMode]');return e?e.value:'h264';}"
                + "function boxAt(pos){if(pos+8>boxBuf.length)return null;"
                + "var size=((boxBuf[pos]<<24)|(boxBuf[pos+1]<<16)|(boxBuf[pos+2]<<8)|boxBuf[pos+3])>>>0;"
                + "var type=String.fromCharCode(boxBuf[pos+4],boxBuf[pos+5],boxBuf[pos+6],boxBuf[pos+7]);"
                + "return {size:size,type:type,start:pos};}"
                + "function flushBoxes(my){while(true){"
                + "if(!initDone){var a=boxAt(boxOff),b=boxAt(boxOff+(a?a.size:0));"
                + "if(!a||!b||a.type!=='ftyp'||b.type!=='moov'||boxOff+a.size+b.size>boxBuf.length)break;"
                + "pending.push(boxBuf.slice(boxOff,boxOff+a.size+b.size).buffer);boxOff+=a.size+b.size;initDone=true;}"
                + "else{var c=boxAt(boxOff),d=boxAt(boxOff+(c?c.size:0));"
                + "if(!c||!d||c.type!=='moof'||d.type!=='mdat'||boxOff+c.size+d.size>boxBuf.length)break;"
                + "pending.push(boxBuf.slice(boxOff,boxOff+c.size+d.size).buffer);boxOff+=c.size+d.size;}"
                + "if(boxOff===boxBuf.length){boxBuf=new Uint8Array(0);boxOff=0;}"
                + "pumpSb(my);}}"
                + "function pumpSb(my){if(my!==sess||!mse||!sb||appending||!pending.length)return;"
                + "try{appending=true;sb.appendBuffer(pending.shift());}"
                + "catch(e){appending=false;pending=[];"
                + "if(e.name!=='InvalidStateError'){var st=document.getElementById('sstate');st.textContent='MSE 追加失败：'+e;}}}"
                + "function startH264(){var video=document.getElementById('h264v');var st=document.getElementById('sstate');"
                + "if(!window.MediaSource){st.textContent='浏览器不支持 MSE';return;}"
                + "var my=++sess;boxBuf=new Uint8Array(0);boxOff=0;initDone=false;appending=false;pending=[];gotData=false;streamEnded=false;"
                + "mse=new MediaSource();video.src=URL.createObjectURL(mse);"
                + "mse.addEventListener('sourceopen',function(){"
                + "if(my!==sess){return;}"
                + "var mySb=null;"
                + "try{mySb=mse.addSourceBuffer('video/mp4; codecs=\"avc1.42E01E\"');}"
                + "catch(e){try{mySb=mse.addSourceBuffer('video/mp4; codecs=\"avc1.4D401E\"');}"
                + "catch(e2){st.textContent='无法创建解码器';return;}}"
                + "sb=mySb;mySb.mode='segments';"
                + "mySb.addEventListener('updateend',function(){if(my!==sess){appending=false;pending=[];return;}appending=false;pumpSb(my);});"
                + "mySb.addEventListener('error',function(){if(my===sess)st.textContent='MSE 错误：浏览器拒绝该媒体数据（H.264 封装不兼容）';});"
                + "abortCtl=new AbortController();"
                + "fetch(currentMode()==='h264fast'?'/h264fast':'/h264',{signal:abortCtl.signal}).then(function(r){"
                + "if(my!==sess){return;}"
                + "if(!r.ok||!r.body){st.textContent='推流失败 '+r.status+'，请点停止后重试';return;}"
                + "var reader=r.body.getReader();"
                + "function step(){reader.read().then(function(res){"
                + "if(my!==sess){return;}"
                + "if(res.done){st.textContent='推流已结束（保持最后一帧）';try{video.pause();}catch(e){}streamEnded=true;return;}"
                + "var nb=new Uint8Array(boxBuf.length+res.value.length);nb.set(boxBuf,0);nb.set(res.value,boxBuf.length);boxBuf=nb;"
                + "flushBoxes(my);gotData=true;"
                + "if(watchdog){clearTimeout(watchdog);watchdog=null;}"
                + "var cur=st.textContent;"
                + "if(cur.indexOf('失败')<0&&cur.indexOf('错误')<0&&cur.indexOf('未解码')<0&&cur.indexOf('已解码')<0)"
                + "st.textContent=initDone?'推流中 · 画面持续更新':'已连接，等待首帧…';"
                + "video.play().catch(function(){});"
                + "step();}).catch(function(e){if(e.name!=='AbortError'&&my===sess){st.textContent='推流中断：'+e;}});}"
                + "step();}).catch(function(e){if(e.name!=='AbortError'&&my===sess){st.textContent='推流失败：'+e;}});"
                + "watchdog=setTimeout(function(){if(my===sess&&!gotData&&streamOn){st.textContent='12 秒未收到数据：设备端推流可能未启动，点停止后重试';}},12000);"
                + "});}"
                + "function stopH264(){sess++;if(abortCtl){try{abortCtl.abort();}catch(e){}abortCtl=null;}"
                + "if(watchdog){clearTimeout(watchdog);watchdog=null;}"
                + "if(mse){try{mse.endOfStream();}catch(e){}mse=null;sb=null;}"
                + "boxBuf=new Uint8Array(0);boxOff=0;initDone=false;appending=false;pending=[];"
                + "var v=document.getElementById('h264v');v.removeAttribute('src');v.load();}"
                + "function streamState(){if(!streamOn)return;"
                + "var vv=document.getElementById('h264v');var st2=document.getElementById('sstate');"
                + "if(vv&&currentMode()==='h264'&&gotData){"
                + "if(vv.videoWidth>0){if(st2.textContent.indexOf('已解码')<0)st2.textContent='推流中 · 画面已解码 '+vv.videoWidth+'x'+vv.videoHeight+'（持续更新）';}"
                + "else if(st2.textContent.indexOf('未解码')<0&&st2.textContent.indexOf('推流中')>=0){st2.textContent='推流中 · 浏览器尚未解码出视频轨道（readyState='+vv.readyState+'）';}"
                + "if(!streamEnded&&vv.paused&&gotData)vv.play().catch(function(){});}"
                + "get('/stream_state',function(d){if(!d)return;"
                + "var st3=document.getElementById('sstate');"
                + "if(d.mode==='mjpeg'&&st3.textContent.indexOf('推流中')<0)st3.textContent='推流中 · MJPEG '+d.fps+'fps';"
                + "if(d.mode==='idle'&&currentMode()==='mjpeg')st3.textContent='已停止（可重新开始）';});}"
                + "function toggleStream(){streamOn=!streamOn;var btn=document.getElementById('sbtn');"
                + "var st=document.getElementById('sstate');var img=document.getElementById('screen');"
                + "var video=document.getElementById('h264v');"
                + "if(streamOn){var m=currentMode();st.textContent='正在启动推流…';"
                + "if(m==='h264'||m==='h264fast'){img.style.display='none';video.style.display='inline-block';startH264();}"
                + "else{video.style.display='none';img.src='/stream';img.style.display='inline-block';}"
                + "btn.textContent='停止推流';}"
                + "else{streamEnded=true;stopH264();img.src='';img.style.display='none';video.style.display='none';"
                + "btn.textContent='开始推流';st.textContent='';}}"
                + "function renderSystem(d){if(!d)return;"
                + "document.getElementById('statTime').textContent=d.time||'--:--';"
                + "document.getElementById('statDate').textContent=d.date||'';"
                + "document.getElementById('statCore').textContent='CPU '+(d.cpu>=0?d.cpu+'%':'--')+' · 内存 '+(d.memPct>=0?d.memPct+'%':'--')+' · GPU '+(d.gpu>=0?d.gpu+'%':'--')+' · 电 '+(d.battery>=0?d.battery+'%':'--');"
                + "var sg=document.getElementById('statGps');if(sg)sg.textContent=d.gps?'GPS '+d.gps:'';"
                + "var temps=d.temps||[];var ring=document.getElementById('ring');ring.innerHTML='';"
                + "var n=Math.max(1,temps.length),cw=ring.clientWidth||346,cx=cw/2,cy=cw/2,r=cw*0.42;"
                + "for(var i=0;i<temps.length;i++){var ang=(-90+i*(360/n))*Math.PI/180;"
                + "var x=cx+r*Math.cos(ang),y=cy+r*Math.sin(ang);"
                + "var el=document.createElement('div');el.style.cssText='position:absolute;left:'+x+'px;top:'+y+'px;transform:translate(-50%,-50%);text-align:center;pointer-events:none';"
                + "el.innerHTML='<div style=\"font-size:9px;color:#8a8272\">'+temps[i].name+'</div><div style=\"font-size:13px;color:#d4af37\">'+temps[i].c.toFixed(0)+'°</div>';"
                + "ring.appendChild(el);}"
                + "if(temps.length===0){var e=document.createElement('div');e.style.cssText='position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);color:#8a8272;font-size:11px';e.textContent='无温度数据';ring.appendChild(e);}}"
                + "function keyEvent(code){var x=new XMLHttpRequest();x.open('GET','/key?code='+code,true);x.send();"
                + "var nm={4:'返回',3:'桌面',187:'最近'},ts=document.getElementById('tstat');if(ts){ts.textContent='已发送：'+nm[code];setTimeout(function(){ts.textContent='';},1200);}}"
                + "var tp=document.getElementById('touchpad');var td={on:false,moved:false,long:false,timer:null,x:0,y:0,lx:0,ly:0,last:0};"
                + "function tPos(ev){if(!tp)return null;var r=tp.getBoundingClientRect();var cx=(ev.clientX-r.left)/r.width,cy=(ev.clientY-r.top)/r.height;"
                + "var dx=cx-0.5,dy=cy-0.5;if(dx*dx+dy*dy>0.25)return null;"
                + "return {x:Math.max(0,Math.min(800,Math.round(cx*800))),y:Math.max(0,Math.min(800,Math.round(cy*800)))};}"
                + "function tSend(q){var x=new XMLHttpRequest();x.open('GET','/touch?'+q,true);x.send();}"
                + "function tMsg(m){var s=document.getElementById('tstat');if(s){s.textContent=m;if(m)setTimeout(function(){if(s.textContent===m)s.textContent='';},1200);}}"
                + "if(tp){tp.addEventListener('pointerdown',function(ev){ev.preventDefault();if(td.on)return;var p=tPos(ev);if(!p)return;"
                + "try{tp.setPointerCapture(ev.pointerId);}catch(e){}"
                + "td.on=true;td.moved=false;td.long=false;td.x=p.x;td.y=p.y;td.lx=p.x;td.ly=p.y;td.last=0;"
                + "td.timer=setTimeout(function(){if(td.on&&!td.moved){td.long=true;tSend('act=long&x='+td.x+'&y='+td.y);tMsg('长按 '+td.x+','+td.y);}},650);});"
                + "tp.addEventListener('pointermove',function(ev){ev.preventDefault();if(!td.on)return;var p=tPos(ev);if(!p)return;"
                + "if(!td.moved&&Math.abs(p.x-td.x)+Math.abs(p.y-td.y)<10)return;"
                + "if(!td.moved){td.moved=true;if(td.timer){clearTimeout(td.timer);td.timer=null;}}"
                + "var now=Date.now();if(now-td.last>=60){tSend('act=move&x='+p.x+'&y='+p.y+'&px='+td.lx+'&py='+td.ly);td.lx=p.x;td.ly=p.y;td.last=now;tMsg('拖动 '+p.x+','+p.y);}});"
                + "function tEnd(){if(!td.on)return;td.on=false;if(td.timer){clearTimeout(td.timer);td.timer=null;}"
                + "if(!td.moved&&!td.long){tSend('act=tap&x='+td.x+'&y='+td.y);tMsg('点击 '+td.x+','+td.y);}}"
                + "tp.addEventListener('pointerup',tEnd);tp.addEventListener('pointercancel',tEnd);"
                + "tp.addEventListener('contextmenu',function(ev){ev.preventDefault();});}"
                + "function camRefresh(){get('/cam/status',function(d){if(!d)return;"
                + "var st=document.getElementById('camState');st.textContent=d.status==='running'?('运行中 · '+d.detail):d.status;"
                + "var u=document.getElementById('camUrls');u.innerHTML=(d.rtsp?'<div>RTSP: '+esc(d.rtsp)+'</div>':'')"
                + "+(d.rtmpUrl?'<div>RTMP: '+esc(d.rtmpUrl)+'</div>':'')"
                + "+(d.webrtc?'<div>WebRTC: '+esc(d.webrtc)+'</div>':'')"
                + "+(d.realFps?'<div style=\"color:#8fbf6a\">实际帧率: '+esc(d.realFps)+' fps</div>':'');"
                + "document.getElementById('camBtn').textContent=d.status==='running'?'停止推流':'启动推流';});}"
                + "function camToggle(){get('/cam/status',function(d){"
                + "if(d&&d.status==='running'){camStop();}else{camStart();}});}"
                + "function camStart(){var x=new XMLHttpRequest();x.open('GET','/cam/start',true);"
                + "x.onload=function(){document.getElementById('camMsg').textContent=x.responseText;setTimeout(camRefresh,1500);};x.send();}"
                + "function camStop(){var x=new XMLHttpRequest();x.open('GET','/cam/stop',true);"
                + "x.onload=function(){document.getElementById('camMsg').textContent=x.responseText;setTimeout(camRefresh,500);};x.send();}"
                + "function frpcRefresh(){get('/frpc/status',function(d){if(!d)return;"
                + "document.getElementById('frpcState').textContent=d.status==='running'?'运行中':'已停止';"
                + "var l=document.getElementById('frpcLog');l.textContent=d.log;l.scrollTop=l.scrollHeight;});}"
                + "function frpcStart(){var x=new XMLHttpRequest();x.open('GET','/frpc/start',true);"
                + "x.onload=function(){document.getElementById('frpcMsg').textContent=x.responseText;frpcRefresh();};x.send();}"
                + "function frpcStop(){var x=new XMLHttpRequest();x.open('GET','/frpc/stop',true);"
                + "x.onload=function(){document.getElementById('frpcMsg').textContent=x.responseText;frpcRefresh();};x.send();}"
                + "setInterval(function(){get('/system_status',renderSystem);},2000);"
                + "loadConv();setInterval(loadConv,3000);setInterval(streamState,3000);frpcRefresh();setInterval(frpcRefresh,3000);camRefresh();setInterval(camRefresh,3000);"
                + "</script></body></html>";
        byte[] b = html.getBytes("UTF-8");
        writeHead(out, "text/html; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveCamStatus(OutputStream out) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("status", com.magneo.compass.cam.CameraStreamService.status());
            o.put("detail", com.magneo.compass.cam.CameraStreamService.statusDetail());
            String ip = localIp();
            int port = Prefs.getI(app, Prefs.K_RTSP_PORT, 8554);
            o.put("rtsp", "rtsp://" + ip + ":" + port + "/cam");
            o.put("rtmpUrl", Prefs.get(app, Prefs.K_RTMP_URL, ""));
            o.put("camAutoStart", Prefs.getB(app, Prefs.K_CAM_AUTO_START, true));
            o.put("webrtc", com.magneo.compass.cam.WebRtcStreamer.get().state());
            o.put("webrtcError", com.magneo.compass.cam.WebRtcStreamer.get().error());
            o.put("realFps", com.magneo.compass.cam.CameraStreamService.realFps());
            o.put("fpsInfo", com.magneo.compass.cam.CameraStreamService.fpsInfo());
            o.put("camDiag", com.magneo.compass.cam.CameraStreamService.camDiag());
            byte[] b = o.toString().getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        } catch (Exception e) {
            byte[] b = ("{\"status\":\"error\",\"err\":\"" + e + "\"}").getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        }
    }

    private static void serveCamOffer(OutputStream out, String body) throws IOException {
        String ok = "false";
        try {
            JSONObject o = new JSONObject(body);
            String sdp = o.optString("sdp", "");
            if (!sdp.isEmpty()) {
                ok = String.valueOf(com.magneo.compass.cam.WebRtcStreamer.get().handleOffer(sdp));
            }
        } catch (Exception e) { ok = "false"; }
        serveText(out, ok);
    }

    private static void serveCamAnswer(OutputStream out) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("sdp", com.magneo.compass.cam.WebRtcStreamer.get().answer());
            byte[] b = o.toString().getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        } catch (Exception e) {
            byte[] b = "{\"sdp\":\"\"}".getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        }
    }

    private static void serveCamPage(OutputStream out) throws IOException {
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><title>真理罗盘 · 摄像头</title>"
                + "<style>body{background:#0d0b08;color:#e8dcc0;font-family:sans-serif;text-align:center;margin:0;padding:20px}"
                + "h1{color:#d4af37;font-size:18px}"
                + "video{width:92mm;height:92mm;border-radius:50%;border:1px solid #6b5a2e;object-fit:cover;background:#000;display:block;margin:0 auto}"
                + "#st{color:#8fbf6a;margin-top:10px;font-size:13px;min-height:18px}"
                + "button{background:#d4af37;color:#0d0b08;border:none;border-radius:8px;padding:8px 14px;margin:6px}"
                + "a{color:#d4af37}</style></head><body>"
                + "<h1>☯ 摄像头直播</h1>"
                + "<video id='v' autoplay playsinline muted></video>"
                + "<div id='st'>连接中…</div>"
                + "<div><button type='button' onclick='startMse()'>MSE 播放</button>"
                + "<button type='button' onclick='startWr()'>WebRTC（实验）</button></div>"
                + "<div style='margin-top:6px;font-size:11px;color:#8a8272'>MSE = H.264 实时流（默认，兼容好）；WebRTC 在这台 MT6580 上硬编兼容性有限，失败时用 MSE 或 RTSP</div>"
                + "<script>"
                + "var v=document.getElementById('v'),st=document.getElementById('st');"
                + "var mse=null,sb=null,abortCtl=null,boxBuf=new Uint8Array(0),boxOff=0,initDone=false,appending=false,pending=[];"
                + "function boxAt(pos){if(pos+8>boxBuf.length)return null;"
                + "var size=((boxBuf[pos]<<24)|(boxBuf[pos+1]<<16)|(boxBuf[pos+2]<<8)|boxBuf[pos+3])>>>0;"
                + "var type=String.fromCharCode(boxBuf[pos+4],boxBuf[pos+5],boxBuf[pos+6],boxBuf[pos+7]);"
                + "return {size:size,type:type,start:pos};}"
                + "function pumpSb(){if(!mse||!sb||sb.updating)return;"
                + "if(pending.length>12){pending=pending.slice(-2);st.textContent='追帧中…';}"
                + "if(!pending.length)return;"
                + "try{sb.appendBuffer(pending.shift());}catch(e){st.textContent='MSE 追加失败: '+e;}}"
                + "function flush(){while(true){"
                + "if(!initDone){var a=boxAt(boxOff),b=boxAt(boxOff+(a?a.size:0));"
                + "if(!a||!b||a.type!=='ftyp'||b.type!=='moov'||boxOff+a.size+b.size>boxBuf.length)break;"
                + "pending.push(boxBuf.slice(boxOff,boxOff+a.size+b.size).buffer);boxOff+=a.size+b.size;initDone=true;}"
                + "else{var c=boxAt(boxOff),d=boxAt(boxOff+(c?c.size:0));"
                + "if(!c||!d||c.type!=='moof'||d.type!=='mdat'||boxOff+c.size+d.size>boxBuf.length)break;"
                + "pending.push(boxBuf.slice(boxOff,boxOff+c.size+d.size).buffer);boxOff+=c.size+d.size;}"
                + "if(boxOff===boxBuf.length){boxBuf=new Uint8Array(0);boxOff=0;}"
                + "pumpSb();}}"
                + "function append(c){var nb=new Uint8Array(boxBuf.length+c.length);nb.set(boxBuf,0);nb.set(c,boxBuf.length);boxBuf=nb;flush();}"
                + "function startMse(){stopWr();st.textContent='MSE 连接中…';"
                + "if(!window.MediaSource){st.textContent='浏览器不支持 MSE';return;}"
                + "mse=new MediaSource();v.src=URL.createObjectURL(mse);boxBuf=new Uint8Array(0);boxOff=0;initDone=false;pending=[];"
                + "mse.addEventListener('sourceopen',function(){"
                + "try{sb=mse.addSourceBuffer('video/mp4; codecs=\"avc1.42E01E\"');}"
                + "catch(e){try{sb=mse.addSourceBuffer('video/mp4; codecs=\"avc1.4D401E\"');}"
                + "catch(e2){st.textContent='无法创建解码器';return;}}"
                + "sb.addEventListener('updateend',pumpSb);"
                + "abortCtl=new AbortController();"
                + "fetch('/camhttp',{signal:abortCtl.signal}).then(function(r){"
                + "if(!r.ok||!r.body){st.textContent='连接失败 '+r.status;return;}"
                + "var reader=r.body.getReader();"
                + "function step(){reader.read().then(function(res){"
                + "if(res.done){st.textContent='流结束';try{mse.endOfStream();}catch(e){}return;}"
                + "append(res.value);v.play().catch(function(){});"
                + "st.textContent='推流中 · H.264';step();}).catch(function(e){if(e.name!=='AbortError')st.textContent='流中断: '+e;});}"
                + "step();}).catch(function(e){if(e.name!=='AbortError')st.textContent='连接失败: '+e;});});}"
                + "function stopWr(){if(abortCtl){try{abortCtl.abort();}catch(e){}abortCtl=null;}"
                + "if(mse){try{mse.endOfStream();}catch(e){}mse=null;sb=null;}"
                + "boxBuf=new Uint8Array(0);boxOff=0;initDone=false;pending=[];}"
                + "function startMse2(){startMse();}"
                + "async function sleep(ms){return new Promise(r=>setTimeout(r,ms));}"
                + "function startWr(){stopWr();st.textContent='WebRTC 连接中…';"
                + "var pc=new RTCPeerConnection();"
                + "pc.ontrack=function(e){v.srcObject=e.streams[0];st.textContent='WebRTC 已连接';};"
                + "pc.onconnectionstatechange=function(){if(pc.connectionState==='failed')st.textContent='WebRTC 失败，请用 MSE 播放';};"
                + "(async function(){try{pc.addTransceiver('video',{direction:'recvonly'});"
                + "var offer=await pc.createOffer();await pc.setLocalDescription(offer);"
                + "while(pc.iceGatheringState!=='complete'){await sleep(200);}"
                + "var r=await fetch('/cam/offer',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sdp:pc.localDescription.sdp})});"
                + "var ok=await r.text();if(ok!=='true'){st.textContent='设备拒绝连接';return;}"
                + "st.textContent='等待设备应答…';var ans=null;"
                + "for(var i=0;i<80;i++){var r2=await fetch('/cam/answer');var d=await r2.json();"
                + "if(d&&d.sdp){ans=d;break;}await sleep(500);}"
                + "if(!ans){st.textContent='设备无应答（WebRTC 不可用）';return;}"
                + "await pc.setRemoteDescription(ans);"
                + "}catch(e){st.textContent='WebRTC 错误: '+e;}})();}"
                + "startMse();"
                + "</script></body></html>";
        byte[] b = html.getBytes("UTF-8");
        writeHead(out, "text/html; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveFrpcStatus(OutputStream out) throws IOException {
        try {
            JSONObject o = new JSONObject();
            o.put("status", com.magneo.compass.frp.FrpcManager.status());
            o.put("log", com.magneo.compass.frp.FrpcManager.logTail(4000));
            byte[] b = o.toString().getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        } catch (Exception e) {
            byte[] b = ("{\"status\":\"error\",\"log\":\"\"}").getBytes("UTF-8");
            writeHead(out, "application/json; charset=utf-8", b.length);
            out.write(b);
        }
    }

    private static void serveText(OutputStream out, String text) throws IOException {
        byte[] b = (text == null ? "" : text).getBytes("UTF-8");
        writeHead(out, "text/plain; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveStatus(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("provider", Prefs.get(app, Prefs.K_PROVIDER, ""));
            o.put("apiKey", Prefs.get(app, Prefs.K_API_KEY, ""));
            o.put("baseUrl", Prefs.get(app, Prefs.K_BASE_URL, ""));
            o.put("textModel", Prefs.get(app, Prefs.K_TEXT_MODEL, ""));
            o.put("visionModel", Prefs.get(app, Prefs.K_VISION_MODEL, ""));
            o.put("asrUrl", Prefs.get(app, Prefs.K_ASR_URL, ""));
            o.put("asrModel", Prefs.get(app, Prefs.K_ASR_MODEL, ""));
            o.put("ttsUrl", Prefs.get(app, Prefs.K_TTS_URL, ""));
            o.put("ttsModel", Prefs.get(app, Prefs.K_TTS_MODEL, ""));
            o.put("ttsVoice", Prefs.get(app, Prefs.K_TTS_VOICE, ""));
            o.put("visionInterval", String.valueOf(Prefs.getI(app, Prefs.K_VISION_INTERVAL, 2)));
            o.put("vadSensitivity", String.valueOf(Prefs.getI(app, Prefs.K_VAD_SENSITIVITY, 600)));
            o.put("searchEngine", Prefs.get(app, Prefs.K_SEARCH_ENGINE, "https://www.bing.com/search?q=%s"));
            o.put("convMaxKb", String.valueOf(Prefs.getI(app, Prefs.K_CONV_MAX_KB, 1024)));
            o.put("convCleanMin", String.valueOf(Prefs.getI(app, Prefs.K_CONV_CLEAN_MIN, 60)));
            o.put("sysPromptVoice", Prefs.get(app, Prefs.K_SYS_PROMPT_VOICE, Prefs.DEFAULT_SYS_PROMPT_VOICE));
            o.put("sysPromptVision", Prefs.get(app, Prefs.K_SYS_PROMPT_VISION, Prefs.DEFAULT_SYS_PROMPT_VISION));
            o.put("streamFps", String.valueOf(Prefs.getI(app, Prefs.K_STREAM_FPS, 1)));
            o.put("streamQuality", String.valueOf(Prefs.getI(app, Prefs.K_STREAM_QUALITY, 55)));
            o.put("streamScale", String.valueOf(Prefs.getI(app, Prefs.K_STREAM_SCALE, 2)));
            o.put("streamBitrate", String.valueOf(Prefs.getI(app, Prefs.K_STREAM_BITRATE, 1500)));
            o.put("locWifiUrl", Prefs.get(app, Prefs.K_LOC_WIFI_URL, Prefs.DEFAULT_LOC_WIFI_URL));
            o.put("locIpUrl", Prefs.get(app, Prefs.K_LOC_IP_URL, Prefs.DEFAULT_LOC_IP_URL));
            o.put("showLoc", Prefs.getB(app, Prefs.K_SHOW_LOC, true));
            o.put("frpcConfig", Prefs.get(app, Prefs.K_FRPC_CONFIG, ""));
            o.put("camId", String.valueOf(Prefs.getI(app, Prefs.K_CAM_ID, 0)));
            o.put("camWidth", String.valueOf(Prefs.getI(app, Prefs.K_CAM_WIDTH, 1280)));
            o.put("camHeight", String.valueOf(Prefs.getI(app, Prefs.K_CAM_HEIGHT, 720)));
            o.put("camFps", String.valueOf(Prefs.getI(app, Prefs.K_CAM_FPS, 24)));
            o.put("camBitrate", String.valueOf(Prefs.getI(app, Prefs.K_CAM_BITRATE, 5000)));
            o.put("rtspPort", String.valueOf(Prefs.getI(app, Prefs.K_RTSP_PORT, 8554)));
            o.put("rtmpUrl", Prefs.get(app, Prefs.K_RTMP_URL, ""));
            o.put("camAutoStart", Prefs.getB(app, Prefs.K_CAM_AUTO_START, true));
            o.put("mode", H264SurfaceStreamer.isActive() ? "h264fast"
                    : (H264Streamer.isActive() ? "h264" : (ScreenStreamer.isActive() ? "mjpeg" : "idle")));
            o.put("ip", localIp());
        } catch (Exception ignored) {}
        byte[] b = o.toString().getBytes("UTF-8");
        writeHead(out, "application/json; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveSave(OutputStream out, String body) throws IOException {
        try {
            String[] pairs = body.split("&");
            for (String pair : pairs) {
                String[] kv = pair.split("=", 2);
                if (kv.length < 2) continue;
                String k = URLDecoder.decode(kv[0], "UTF-8");
                String v = URLDecoder.decode(kv[1], "UTF-8");
                if (k.equals("visionInterval") || k.equals("vadSensitivity")) {
                    try { Prefs.putI(app, k, Integer.parseInt(v)); } catch (Exception ignored) {}
                } else if (k.equals("convMaxKb")) {
                    try { Prefs.putI(app, k, Math.max(100, Math.min(20480, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals("convCleanMin")) {
                    try { Prefs.putI(app, k, Math.max(0, Math.min(1440, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals("streamFps")) {
                    try { Prefs.putI(app, k, Math.max(1, Math.min(10, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals("streamQuality")) {
                    try { Prefs.putI(app, k, Math.max(20, Math.min(90, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals("streamScale")) {
                    try { Prefs.putI(app, k, Math.max(1, Math.min(2, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (k.equals("streamBitrate")) {
                    try { Prefs.putI(app, k, Math.max(300, Math.min(8000, Integer.parseInt(v)))); } catch (Exception ignored) {}
                } else if (isBoolKey(k)) {
                    Prefs.putB(app, k, "true".equalsIgnoreCase(v) || "1".equals(v));
                } else {
                    Prefs.put(app, k, v);
                }
            }
            byte[] b = "设置已保存".getBytes("UTF-8");
            writeHead(out, "text/plain; charset=utf-8", b.length);
            out.write(b);
        } catch (Exception e) {
            byte[] b = ("保存失败: " + e.getMessage()).getBytes("UTF-8");
            writeHead(out, "text/plain; charset=utf-8", b.length);
            out.write(b);
        }
    }

    private static boolean isBoolKey(String k) {
        return k.equals("localTtsFirst") || k.equals("visionEnabled") || k.equals("vadEnabled")
                || k.equals("ignoreSsl") || k.equals("uaDesktop") || k.equals("noImages")
                || k.equals("showLoc");
    }

    private static void serveConversations(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("entries", ConversationLog.read(app));
            o.put("sizeKb", ConversationLog.size(app) / 1024L);
            o.put("maxKb", Prefs.getI(app, Prefs.K_CONV_MAX_KB, 1024));
            o.put("cleanMin", Prefs.getI(app, Prefs.K_CONV_CLEAN_MIN, 60));
        } catch (Exception ignored) {}
        byte[] b = o.toString().getBytes("UTF-8");
        writeHead(out, "application/json; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveClearConv(OutputStream out) throws IOException {
        ConversationLog.clear(app);
        byte[] b = "已清空".getBytes("UTF-8");
        writeHead(out, "text/plain; charset=utf-8", b.length);
        out.write(b);
    }

    private static void serveSystemStatus(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("time", new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date()));
            o.put("date", new java.text.SimpleDateFormat("yyyy-MM-dd EEE", java.util.Locale.getDefault()).format(new java.util.Date()));
            o.put("cpu", readCpuPct());
            long[] mem = readMem();
            o.put("memTotalMb", mem[0] / 1024);
            o.put("memUsedMb", mem[1] / 1024);
            o.put("memPct", mem[0] > 0 ? (int) Math.round(mem[1] * 100.0 / mem[0]) : -1);
            o.put("gpu", readGpuPct());
            o.put("temps", readTemps());
            com.magneo.compass.SensorHub h = com.magneo.compass.SensorHub.instance;
            String gpsTxt = Prefs.getB(app, Prefs.K_SHOW_LOC, true) ? "无" : "已关闭";
            if (Prefs.getB(app, Prefs.K_SHOW_LOC, true) && h != null) {
                if (!Double.isNaN(h.lat)) gpsTxt = h.gpsStatus + " 已定位";
                else if (!Double.isNaN(h.netLat))
                    gpsTxt = "定位(" + h.netSrc + ") " + String.format(java.util.Locale.US, "%.5f,%.5f ±%.0fm", h.netLat, h.netLon, h.netAcc);
                else gpsTxt = h.gpsStatus;
            }
            o.put("gps", gpsTxt);
            o.put("battery", h == null ? -1 : h.battery);
        } catch (Exception ignored) {}
        byte[] b = o.toString().getBytes("UTF-8");
        writeHead(out, "application/json; charset=utf-8", b.length);
        out.write(b);
    }

    private static String qParam(String qs, String name) {
        if (qs == null) return null;
        String[] q = qs.split("\\?", 2);
        if (q.length < 2) return null;
        for (String kv : q[1].split("&")) {
            String[] p = kv.split("=", 2);
            if (p.length == 2 && p[0].equals(name)) {
                try { return java.net.URLDecoder.decode(p[1], "UTF-8"); } catch (Exception e) { return p[1]; }
            }
        }
        return null;
    }

    /** 三大金刚键：返回/桌面/最近任务 */
    private static void serveKey(OutputStream out, String qs) throws IOException {
        String code = qParam(qs, "code");
        byte[] b = "ok".getBytes("UTF-8");
        writeHead(out, "text/plain; charset=utf-8", b.length);
        out.write(b);
        if (code != null && !code.isEmpty()) runRoot("input keyevent " + code);
    }

    /** 远程触摸：tap=点击 long=长按 move=拖动（px/py 为上一坐标） */
    private static void serveTouch(OutputStream out, String qs) throws IOException {
        String act = qParam(qs, "act"), x = qParam(qs, "x"), y = qParam(qs, "y");
        byte[] b = "ok".getBytes("UTF-8");
        writeHead(out, "text/plain; charset=utf-8", b.length);
        out.write(b);
        if (act == null || x == null || y == null) return;
        if (act.equals("tap")) runRoot("input tap " + x + " " + y);
        else if (act.equals("long")) runRoot("input swipe " + x + " " + y + " " + x + " " + y + " 800");
        else if (act.equals("move")) {
            String px = qParam(qs, "px"), py = qParam(qs, "py");
            if (px == null) px = x; if (py == null) py = y;
            runRoot("input swipe " + px + " " + py + " " + x + " " + y + " 50");
        }
    }

    private static void runRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
        } catch (Exception ignored) {}
    }

    private static long[] prevCpuTicks;
    private static int lastCpuPct = 0;

    private static int readCpuPct() {
        try {
            long[] cur = readCpuTicks();
            if (cur == null) return lastCpuPct;
            if (prevCpuTicks == null) { prevCpuTicks = cur; return 0; }
            long busy = 0, total = 0;
            boolean glitch = false;
            for (int i = 0; i < 7; i++) {
                long d = cur[i] - prevCpuTicks[i];
                if (d < 0) glitch = true;   // MTK 热插拔/计数器跳变：本次采样作废
                total += d;
                if (i != 3 && i != 4) busy += d;
            }
            prevCpuTicks = cur;
            if (glitch || total <= 0) return lastCpuPct;
            lastCpuPct = (int) Math.min(100, Math.round(busy * 100.0 / total));
            return lastCpuPct;
        } catch (Exception e) { return lastCpuPct; }
    }

    private static long[] readCpuTicks() throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream("/proc/stat")));
        String line = r.readLine();
        r.close();
        if (line == null || !line.startsWith("cpu ")) return null;
        String[] p = line.trim().split("\\s+");
        long[] v = new long[7];
        for (int i = 1; i < p.length && i <= 7; i++) v[i - 1] = Long.parseLong(p[i]);
        return v;
    }

    private static long[] readMem() {
        long total = 0, free = 0, cached = 0, buffers = 0;
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream("/proc/meminfo")));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("MemTotal")) total = kbOf(line);
                else if (line.startsWith("MemFree")) free = kbOf(line);
                else if (line.startsWith("Cached")) cached = kbOf(line);
                else if (line.startsWith("Buffers")) buffers = kbOf(line);
            }
            r.close();
        } catch (Exception ignored) {}
        long used = Math.max(0, total - free - cached - buffers);
        return new long[]{total, used};
    }

    private static long kbOf(String line) {
        String[] p = line.trim().split("\\s+");
        try { return Long.parseLong(p[1]); } catch (Exception e) { return 0; }
    }

    private static int readGpuPct() {
        try {
            String s = readFile("/proc/mali/utilization");
            if (s == null) return -1;
            if (s.contains("clock off") || s.trim().isEmpty()) return 0;
            // 实际格式: "GPU/GP/PP: 60/17/59, Frequency: 500500"（GPU 为第一个数字）
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("GPU/GP/PP:\\s*(\\d+)").matcher(s);
            if (m.find()) return clampPct(Integer.parseInt(m.group(1)));
            m = java.util.regex.Pattern.compile("\\d+").matcher(s);
            if (m.find()) return clampPct(Integer.parseInt(m.group()));
            return 0;
        } catch (Exception e) { return -1; }
    }

    private static int clampPct(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static org.json.JSONArray readTemps() {
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            for (int i = 0; i < 12; i++) {
                String type = readFile("/sys/class/thermal/thermal_zone" + i + "/type");
                String t = readFile("/sys/class/thermal/thermal_zone" + i + "/temp");
                if (type == null || t == null) continue;
                int milli;
                try { milli = Integer.parseInt(t.trim()); } catch (Exception e) { continue; }
                if (milli < -50000 || milli > 200000) continue;
                arr.put(new JSONObject().put("name", tempName(type.trim())).put("c", milli / 1000.0));
            }
        } catch (Exception ignored) {}
        return arr;
    }

    private static String tempName(String type) {
        if (type.contains("cpu")) return "CPU";
        if (type.contains("battery")) return "电池";
        if (type.contains("pmi")) return "PMIC";
        if (type.contains("wmt")) return "WiFi";
        if (type.contains("AP")) return "AP";
        if (type.matches("mtkts[0-9]+")) return "热区" + type.substring(5);
        return type;
    }

    private static String readFile(String path) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(path);
            byte[] b = new byte[128];
            int n = in.read(b);
            in.close();
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : "";
        } catch (Exception e) { return null; }
    }

    private static void serveStream(Socket s) {
        ScreenStreamer.serve(s, app);
    }

    private static void serveH264(Socket s) {
        H264Streamer.serve(s, app);
    }

    private static void serveH264Fast(Socket s) {
        H264SurfaceStreamer.serve(s, app);
    }

    private static void serveStreamState(OutputStream out) throws IOException {
        JSONObject o = new JSONObject();
        try {
            o.put("state", ScreenStreamer.state());
            o.put("fps", Prefs.getI(app, Prefs.K_STREAM_FPS, 1));
            o.put("quality", Prefs.getI(app, Prefs.K_STREAM_QUALITY, 55));
            o.put("scale", Prefs.getI(app, Prefs.K_STREAM_SCALE, 2));
            o.put("mode", H264SurfaceStreamer.isActive() ? "h264fast"
                    : (H264Streamer.isActive() ? "h264" : (ScreenStreamer.isActive() ? "mjpeg" : "idle")));
        } catch (Exception ignored) {}
        byte[] b = o.toString().getBytes("UTF-8");
        writeHead(out, "application/json; charset=utf-8", b.length);
        out.write(b);
    }

    private static void writeHead(OutputStream out, String type, long len) throws IOException {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + len
                + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
    }

    private static void serve404(OutputStream out) throws IOException {
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes());
    }
}
