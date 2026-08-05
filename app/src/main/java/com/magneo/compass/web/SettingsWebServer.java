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
        try {
            server = new ServerSocket(PORT, 32, InetAddress.getByName("0.0.0.0")); // backlog 32，防轮询连接堵死
            thread = new Thread(SettingsWebServer::loop, "web-settings");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            server = null;
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
                + "get('/status',function(d){if(!d)return;for(var k in d){var e=document.querySelector('[name='+k+']');if(e)e.value=d[k];}"
                + "document.getElementById('provider').value=d.provider;"
                + "document.getElementById('msg').textContent='已加载设备当前配置';});"
                + "function save(){var b=new URLSearchParams(new FormData(document.getElementById('f')));"
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
                + "document.getElementById('statCore').textContent='CPU '+(d.cpu>=0?d.cpu+'%':'--')+' · 内存 '+(d.memPct>=0?d.memPct+'%':'--')+' · GPU '+(d.gpu>=0?d.gpu+'%':'--');"
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
                + "setInterval(function(){get('/system_status',renderSystem);},2000);"
                + "loadConv();setInterval(loadConv,3000);setInterval(streamState,3000);"
                + "</script></body></html>";
        byte[] b = html.getBytes("UTF-8");
        writeHead(out, "text/html; charset=utf-8", b.length);
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
                || k.equals("ignoreSsl") || k.equals("uaDesktop") || k.equals("noImages");
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
            o.put("gps", h == null ? "无" : (h.gpsStatus + (Double.isNaN(h.lat) ? "" : " 已定位")));
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
