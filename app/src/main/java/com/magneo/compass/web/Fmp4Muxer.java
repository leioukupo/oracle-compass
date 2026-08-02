package com.magneo.compass.web;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** 轻量 fMP4 封装：init 段（ftyp+moov）+ 每帧一个 fragment（moof+mdat），供浏览器 MSE 播放。 */
public class Fmp4Muxer {
    private static final int TS = 1_000_000; // 微秒时间基

    private int seq = 1;

    public int timescale() { return TS; }

    /** 生成 init 段。sps/pps 为裸 NAL（不含起始码）。 */
    public byte[] init(int w, int h, byte[] sps, byte[] pps) {
        byte[] avcC = avcC(sps, pps);
        byte[] stsd = box("stsd", full(0, 0, i32(1), box("avc1", avc1Body(w, h, avcC))));
        byte[] stbl = box("stbl", stsd,
                box("stts", full(0, 0, i32(0))),          // stts 空
                box("stsc", full(0, 0, i32(0))),          // stsc 空
                box("stsz", full(0, 0, i32(0), i32(0))),  // stsz 空
                box("stco", full(0, 0, i32(0))));         // stco 空
        byte[] vmhd = box("vmhd", full(1, 0, i16(0), i16(0), i16(0), i16(0)));
        byte[] url = box("url", full(1, 0));
        byte[] dref = box("dref", full(0, 0, i32(1), url));
        byte[] dinf = box("dinf", dref);
        byte[] minf = box("minf", vmhd, dinf, stbl);
        byte[] mdhd = box("mdhd", full(0, 0, i32(0), i32(0), i32(TS), i32(0), i16(0x55c4), i16(0)));
        byte[] hdlr = box("hdlr", full(0, 0, i32(0), "vide".getBytes(StandardCharsets.US_ASCII), i32(0), i32(0), i32(0),
                "VideoHandler".getBytes(StandardCharsets.US_ASCII), new byte[]{0}));
        byte[] mdia = box("mdia", mdhd, hdlr, minf);
        byte[] tkhd = box("tkhd", full(3, 0, i32(0), i32(0), i32(1), i32(0), i32(0), i64(0), i16(0), i16(0), i16(0x0100), i16(0),
                matrix(), i32(w << 16), i32(h << 16)));
        byte[] trak = box("trak", tkhd, mdia);
        byte[] trex = box("trex", full(0, 0, i32(1), i32(1), i32(0), i32(0), i32(0)));
        byte[] mvex = box("mvex", trex);
        byte[] mvhd = box("mvhd", full(0, 0, i32(0), i32(0), i32(1000), i32(0), i32(0x00010000), i16(0x0100), i16(0), i64(0),
                matrix(), i32(0), i32(0), i32(0), i32(0), i32(0), i32(0), i32(2))); // pre_defined[6] + next_track_ID
        byte[] moov = box("moov", mvhd, trak, mvex);
        byte[] ftyp = box("ftyp", "isom".getBytes(StandardCharsets.US_ASCII), i32(0x200),
                "isom".getBytes(StandardCharsets.US_ASCII), "iso2".getBytes(StandardCharsets.US_ASCII),
                "avc1".getBytes(StandardCharsets.US_ASCII), "mp41".getBytes(StandardCharsets.US_ASCII));
        return concat(ftyp, moov);
    }

    /** 生成一个 fragment（一帧）。data 为 AVCC 格式（4 字节长度前缀 NAL）。 */
    public byte[] fragment(long ptsUs, long durUs, boolean key, byte[] data) {
        byte[] tfhd = box("tfhd", full(0x020000, 0, i32(1)));
        byte[] tfdt = box("tfdt", full(0, 0, i32((int) ptsUs)));
        byte[] trun = box("trun", full(0x000701, 0, i32(1), i32(0), i32((int) durUs), i32(data.length), i32(key ? 0x02000000 : 0x01010000)));
        byte[] traf = box("traf", tfhd, tfdt, trun);
        byte[] moof = box("moof", box("mfhd", full(0, 0, i32(seq++))), traf);
        // data_offset 在 moof 头之后指向 mdat 数据（default-base-is-moof）
        int off = moof.length + 8;
        // 回填 trun.data_offset
        int trunPos = moof.length - trun.length;
        moof[trunPos + 16] = (byte) (off >>> 24);
        moof[trunPos + 17] = (byte) (off >>> 16);
        moof[trunPos + 18] = (byte) (off >>> 8);
        moof[trunPos + 19] = (byte) off;
        byte[] mdat = box("mdat", data);
        return concat(moof, mdat);
    }

    /** Annex-B（起始码）转 AVCC（4 字节长度前缀），并拆分出 sps/pps（供 init 段，若编码器未发 config）。 */
    public static byte[] toAvcc(byte[] annexB, byte[] outSps, byte[] outPps) {
        ByteArrayOutputStream o = new ByteArrayOutputStream(annexB.length + 16);
        int i = 0;
        while (i + 3 < annexB.length) {
            if (!(annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 0 && annexB[i + 3] == 1)
                    && !(annexB[i] == 0 && annexB[i + 1] == 0 && annexB[i + 2] == 1)) {
                i++;
                continue;
            }
            boolean three = annexB[i + 2] == 1;
            int start = i + (three ? 3 : 4);
            int end = start;
            while (end < annexB.length) {
                boolean isStart = annexB[end] == 0 && annexB[end + 1] == 0
                        && ((end + 2 < annexB.length && annexB[end + 2] == 1)
                        || (end + 3 < annexB.length && annexB[end + 2] == 0 && annexB[end + 3] == 1));
                if (isStart) break;
                end++;
            }
            if (end < start) break;
            int type = annexB[start] & 0x1f;
            byte[] nal = java.util.Arrays.copyOfRange(annexB, start, end);
            if (type == 7 && outSps.length > 0) System.arraycopy(nal, 0, outSps, 0, Math.min(outSps.length, nal.length));
            if (type == 8 && outPps.length > 0) System.arraycopy(nal, 0, outPps, 0, Math.min(outPps.length, nal.length));
            o.write((nal.length >>> 24) & 0xff);
            o.write((nal.length >>> 16) & 0xff);
            o.write((nal.length >>> 8) & 0xff);
            o.write(nal.length & 0xff);
            o.write(nal, 0, nal.length);
            i = end;
        }
        return o.toByteArray();
    }

    private static byte[] avcC(byte[] sps, byte[] pps) {
        int len = 8 + 2 + sps.length + 3 + pps.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream(len);
        o.write(1);                    // configurationVersion
        o.write(sps[1]);               // profile
        o.write(sps[2]);               // compat
        o.write(sps[3]);               // level
        o.write(0xff);                 // 6 bits reserved + lengthSizeMinusOne=3
        o.write(0xe1);                 // 3 bits reserved + numSPS=1
        o.write((sps.length >>> 8) & 0xff); o.write(sps.length & 0xff);
        o.write(sps, 0, sps.length);
        o.write(1);                    // numPPS
        o.write((pps.length >>> 8) & 0xff); o.write(pps.length & 0xff);
        o.write(pps, 0, pps.length);
        return o.toByteArray();
    }

    /** avc1 样本条目的内容（不含盒头；盒头由 box("avc1", ...) 包裹）。 */
    private static byte[] avc1Body(int w, int h, byte[] avcC) {
        try {
        ByteArrayOutputStream o = new ByteArrayOutputStream(100 + avcC.length);
        o.write(new byte[6], 0, 6);           // reserved
        o.write(0); o.write(1);               // data_reference_index=1
        o.write(0); o.write(0);               // pre_defined
        o.write(0); o.write(0);               // reserved
        o.write(new byte[12], 0, 12);         // pre_defined[3]
        o.write((w >>> 8) & 0xff); o.write(w & 0xff);
        o.write((h >>> 8) & 0xff); o.write(h & 0xff);
        o.write(new byte[]{0x00, 0x48, 0x00, 0x00}); // horizresolution 72dpi (16.16)
        o.write(new byte[]{0x00, 0x48, 0x00, 0x00}); // vertresolution
        o.write(new byte[]{0, 0, 0, 0});            // reserved
        o.write(new byte[]{0, 1});                  // frame_count=1
        byte[] name = new byte[32];           // compressorname（Pascal 风格：首字节长度）
        byte[] n = "TruthScreen".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        name[0] = (byte) Math.min(n.length, 31);
        System.arraycopy(n, 0, name, 1, Math.min(n.length, 31));
        o.write(name, 0, 32);
        o.write(0x00); o.write(0x18);         // depth=24
        o.write(0xff); o.write(0xff);         // pre_defined=-1
        byte[] avccBox = box("avcC", avcC);
        o.write(avccBox, 0, avccBox.length);
        byte[] btrt = box("btrt", i32(0), i32(0x0001b254), i32(0x0001b254));
        o.write(btrt, 0, btrt.length);
        return o.toByteArray();
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static byte[] matrix() {
        byte[] m = new byte[36];
        m[0] = 0; m[1] = 0x01; m[2] = 0; m[3] = 0; // 1.0
        m[16] = 0; m[17] = 0x01; m[18] = 0; m[19] = 0;
        m[32] = 0x40; m[33] = 0; m[34] = 0; m[35] = 0; // 1.0 << 14
        return m;
    }

    private static byte[] box(String type, byte[]... children) {
        int len = 8;
        for (byte[] c : children) len += c.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream(len);
        o.write((len >>> 24) & 0xff); o.write((len >>> 16) & 0xff); o.write((len >>> 8) & 0xff); o.write(len & 0xff);
        byte[] tb = type.getBytes(StandardCharsets.US_ASCII);
        o.write(tb, 0, tb.length);
        for (int i = tb.length; i < 4; i++) o.write(' '); // 不足 4 字节的类型补空格（如 "url "）
        for (byte[] c : children) o.write(c, 0, c.length);
        return o.toByteArray();
    }

    private static byte[] full(int flags, int version, byte[]... payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(version & 0xff);
        o.write((flags >>> 16) & 0xff); o.write((flags >>> 8) & 0xff); o.write(flags & 0xff);
        for (byte[] c : payload) o.write(c, 0, c.length);
        return o.toByteArray();
    }

    private static byte[] i32(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }
    private static byte[] i16(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }
    private static byte[] i64(long v) {
        return new byte[]{(byte) (v >>> 56), (byte) (v >>> 48), (byte) (v >>> 40), (byte) (v >>> 32),
                (byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }
    private static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, off, p.length); off += p.length; }
        return out;
    }
}
