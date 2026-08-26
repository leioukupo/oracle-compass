import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Generates the C110001 boot animation and its systemless Magisk module. */
public final class BootAnimationGenerator {
    private static final int SIZE = 800;
    private static final int FPS = 12;
    private static final int PART0_FRAMES = 70;
    private static final int PART1_FRAMES = 36;
    private static final long MAX_BOOT_ZIP_BYTES = 6L * 1024L * 1024L;

    private static final Color BG = new Color(7, 6, 4);
    private static final Color GOLD = new Color(212, 175, 55);
    private static final Color GOLD_DARK = new Color(145, 116, 48);
    private static final Color AETHER = new Color(70, 210, 214);

    private BootAnimationGenerator() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException(
                    "usage: BootAnimationGenerator <output-dir> [version]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        String version = args.length == 2 ? args[1].trim() : "0.2.4";
        Files.createDirectories(output);

        List<byte[]> frames = new ArrayList<>(PART0_FRAMES);
        for (int i = 0; i < PART0_FRAMES; i++) {
            frames.add(encodeJpeg(drawFrame(i), 0.88f));
        }
        List<byte[]> loopFrames = new ArrayList<>(PART1_FRAMES);
        for (int i = 0; i < PART1_FRAMES; i++) {
            loopFrames.add(encodeJpeg(drawLoopFrame(i), 0.88f));
        }
        byte[] bootZip = buildBootZip(frames, loopFrames);
        verifyBootZip(bootZip);
        if (bootZip.length > MAX_BOOT_ZIP_BYTES) {
            throw new IOException("bootanimation.zip is too large: " + bootZip.length);
        }

        Path module = output.resolve("oracle-compass-bootanimation-v" + version + ".zip");
        writeModule(module, version, bootZip);
        Path preview = output.resolve("oracle-compass-bootanimation-preview.jpg");
        Files.write(preview, frames.get(PART0_FRAMES - 1));

        System.out.println("bootanimation: " + bootZip.length + " bytes, "
                + PART0_FRAMES + "+" + PART1_FRAMES + " frames, "
                + SIZE + "x" + SIZE + "@" + FPS);
        System.out.println("module: " + module);
        System.out.println("preview: " + preview);
    }

    private static BufferedImage drawFrame(int frame) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(BG);
        g.fillRect(0, 0, SIZE, SIZE);

        double t = frame / (double) (PART0_FRAMES - 1);
        double spark = smoothRange(t, 0.00, 0.18);
        double eye = smoothRange(t, 0.12, 0.38) * (1.0 - smoothRange(t, 0.72, 0.98));
        double rings = smoothRange(t, 0.25, 0.58);
        double settle = smoothRange(t, 0.68, 1.00);
        double pulse = 0.5 + 0.5 * Math.sin(frame * 0.43);
        double cx = SIZE / 2.0;
        double cy = SIZE / 2.0;

        drawGlow(g, cx, cy, 16 + spark * 70, GOLD, (float) (0.32 * spark));
        drawOuterInstrument(g, cx, cy, rings, frame * 4.6, pulse);
        drawIris(g, cx, cy, eye, frame);
        drawScanner(g, cx, cy, rings * (1.0 - settle), frame);
        drawTaiji(g, cx, cy, 90, settle);

        g.dispose();
        return image;
    }

    private static BufferedImage drawLoopFrame(int frame) {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(BG);
        g.fillRect(0, 0, SIZE, SIZE);

        double phase = Math.PI * 2.0 * frame / PART1_FRAMES;
        double pulse = 0.5 + 0.5 * Math.sin((PART0_FRAMES - 1) * 0.43 + phase);
        double breathe = 0.5 - 0.5 * Math.cos(phase);
        double rotation = (PART0_FRAMES - 1) * 4.6 + 360.0 * frame / PART1_FRAMES;
        double cx = SIZE / 2.0;
        double cy = SIZE / 2.0;

        drawGlow(g, cx, cy, 86 + breathe * 5, GOLD, (float) (0.30 + breathe * 0.04));
        drawOuterInstrument(g, cx, cy, 1.0, rotation, pulse);
        drawTaiji(g, cx, cy, 90 + breathe * 1.5, 1.0);

        g.dispose();
        return image;
    }

    private static void drawOuterInstrument(Graphics2D g, double cx, double cy,
                                            double amount, double rotation, double pulse) {
        if (amount <= 0) return;
        float a = (float) amount;
        g.setComposite(AlphaComposite.SrcOver.derive(0.68f * a));
        g.setColor(GOLD_DARK);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        circle(g, cx, cy, 365);
        circle(g, cx, cy, 310);
        circle(g, cx, cy, 238);

        for (int i = 0; i < 48; i++) {
            double angle = Math.toRadians(i * 7.5 - 90);
            double outer = 365;
            double len = i % 6 == 0 ? 28 : 13;
            g.setComposite(AlphaComposite.SrcOver.derive(
                    (i % 6 == 0 ? 0.92f : 0.48f) * a));
            g.setColor(i % 6 == 0 ? GOLD : GOLD_DARK);
            g.setStroke(new BasicStroke(i % 6 == 0 ? 3f : 1.4f,
                    BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine((int) (cx + Math.cos(angle) * (outer - len)),
                    (int) (cy + Math.sin(angle) * (outer - len)),
                    (int) (cx + Math.cos(angle) * outer),
                    (int) (cy + Math.sin(angle) * outer));
        }

        g.setComposite(AlphaComposite.SrcOver.derive((float) ((0.38 + pulse * 0.34) * a)));
        g.setColor(AETHER);
        g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Arc2D.Double(cx - 349, cy - 349, 698, 698, rotation, 74, Arc2D.OPEN));
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawIris(Graphics2D g, double cx, double cy, double amount, int frame) {
        if (amount <= 0) return;
        double iris = 205 * (0.72 + 0.28 * amount);
        double pupil = 70 + 35 * amount;
        double rotation = frame * 3.0;
        for (int i = 0; i < 6; i++) {
            Graphics2D bladeG = (Graphics2D) g.create();
            bladeG.rotate(Math.toRadians(i * 60 + rotation), cx, cy);
            Path2D blade = new Path2D.Double();
            blade.moveTo(cx + pupil, cy);
            blade.curveTo(cx + iris * 0.38, cy - iris * 0.34,
                    cx + iris * 0.72, cy - iris * 0.24, cx + iris * 0.86, cy);
            blade.curveTo(cx + iris * 0.70, cy + iris * 0.12,
                    cx + iris * 0.45, cy + iris * 0.18, cx + pupil, cy);
            blade.closePath();
            bladeG.setComposite(AlphaComposite.SrcOver.derive((float) (0.14 * amount)));
            bladeG.setColor(Color.BLACK);
            bladeG.fill(blade);
            bladeG.setComposite(AlphaComposite.SrcOver.derive((float) (0.66 * amount)));
            bladeG.setColor(i % 2 == 0 ? GOLD : GOLD_DARK);
            bladeG.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            bladeG.draw(blade);
            bladeG.dispose();
        }

        g.setComposite(AlphaComposite.SrcOver.derive((float) (0.86 * amount)));
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(3f));
        circle(g, cx, cy, iris);
        circle(g, cx, cy, pupil);
        g.setComposite(AlphaComposite.SrcOver.derive((float) (0.24 * amount)));
        g.setColor(Color.BLACK);
        g.fill(new Ellipse2D.Double(cx - pupil, cy - pupil, pupil * 2, pupil * 2));
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawScanner(Graphics2D g, double cx, double cy, double amount, int frame) {
        if (amount <= 0) return;
        double angle = Math.toRadians(frame * 8.5 - 90);
        g.setComposite(AlphaComposite.SrcOver.derive((float) (0.78 * amount)));
        g.setColor(new Color(238, 228, 196));
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.drawLine((int) (cx + Math.cos(angle) * 76), (int) (cy + Math.sin(angle) * 76),
                (int) (cx + Math.cos(angle) * 232), (int) (cy + Math.sin(angle) * 232));
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawTaiji(Graphics2D g, double cx, double cy, double radius, double amount) {
        if (amount <= 0) return;
        Graphics2D tg = (Graphics2D) g.create();
        tg.setComposite(AlphaComposite.SrcOver.derive((float) amount));
        Ellipse2D clip = new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2);
        tg.clip(clip);
        tg.setColor(GOLD);
        tg.fill(clip);
        tg.setColor(Color.BLACK);
        tg.fill(new Rectangle2D.Double(cx, cy - radius, radius, radius * 2));
        tg.fill(new Ellipse2D.Double(cx - radius / 2, cy - radius, radius, radius));
        tg.setColor(GOLD);
        tg.fill(new Ellipse2D.Double(cx - radius / 2, cy, radius, radius));
        tg.setColor(GOLD);
        dot(tg, cx, cy - radius / 2, radius * 0.105);
        tg.setColor(Color.BLACK);
        dot(tg, cx, cy + radius / 2, radius * 0.105);
        tg.setClip(null);
        tg.setColor(GOLD_DARK);
        tg.setStroke(new BasicStroke(3f));
        tg.draw(clip);
        tg.dispose();
    }

    private static void drawGlow(Graphics2D g, double cx, double cy, double radius,
                                 Color color, float alpha) {
        if (alpha <= 0) return;
        for (int i = 8; i >= 1; i--) {
            float f = i / 8f;
            g.setComposite(AlphaComposite.SrcOver.derive(alpha * (1f - f) * 0.28f));
            g.setColor(color);
            double rr = radius * f;
            g.fill(new Ellipse2D.Double(cx - rr, cy - rr, rr * 2, rr * 2));
        }
        g.setComposite(AlphaComposite.SrcOver.derive(alpha));
        g.setColor(color);
        dot(g, cx, cy, Math.max(3, radius * 0.10));
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static byte[] buildBootZip(List<byte[]> frames, List<byte[]> loopFrames)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            putStored(zip, "desc.txt", (SIZE + " " + SIZE + " " + FPS
                    + "\np 1 0 part0\np 0 0 part1\n").getBytes(StandardCharsets.US_ASCII));
            for (int i = 0; i < frames.size(); i++) {
                putStored(zip, String.format(Locale.ROOT, "part0/%03d.jpg", i), frames.get(i));
            }
            for (int i = 0; i < loopFrames.size(); i++) {
                putStored(zip, String.format(Locale.ROOT, "part1/%03d.jpg", i),
                        loopFrames.get(i));
            }
        }
        return bytes.toByteArray();
    }

    private static void writeModule(Path output, String version, byte[] bootZip) throws IOException {
        String moduleProp = "id=oracle_compass_bootanimation\n"
                + "name=Oracle Compass Golden Mechanical Eye\n"
                + "version=" + version + "\n"
                + "versionCode=6\n"
                + "author=oracle-compass\n"
                + "description=800x800 boot animation and seamless HOME handoff for MAGNEO C110001 / Android 5.1\n";
        String customize = "ui_print \"- Oracle Compass golden mechanical eye\"\n"
                + "[ \"$API\" = \"22\" ] || abort \"Android 5.1 / API 22 required\"\n"
                + "[ -f /system/media/bootanimation.zip ] || abort \"Original bootanimation.zip not found\"\n"
                + "set_perm \"$MODPATH/system/media/bootanimation.zip\" 0 0 0644\n"
                + "set_perm \"$MODPATH/post-fs-data.sh\" 0 0 0755\n"
                + "set_perm \"$MODPATH/service.sh\" 0 0 0755\n";
        String systemProp = "curlockscreen=0\n"
                + "ro.lockscreen.disable.default=true\n";
        String postFsData = "#!/system/bin/sh\n"
                + "settings put secure lockscreen.disabled 1\n"
                + "resetprop -n curlockscreen 0\n"
                + "resetprop -n ro.lockscreen.disable.default true\n";
        String service = "#!/system/bin/sh\n"
                + "i=0\n"
                + "bridge=0\n"
                + "settings put secure lockscreen.disabled 1\n"
                + "while [ \"$(getprop sys.boot_completed)\" != \"1\" ] && [ \"$i\" -lt 120 ]; do\n"
                + "  resetprop -n curlockscreen 0\n"
                + "  resetprop -n ro.lockscreen.disable.default true\n"
                + "  if [ \"$bridge\" = \"0\" ]; then\n"
                + "    am start -a android.intent.action.MAIN -f 0x04000000 "
                + "-n com.magneo.compass/.BootHandoffActivity >/dev/null 2>&1\n"
                + "    if dumpsys activity activities | grep -q 'com.magneo.compass/.BootHandoffActivity'; then\n"
                + "      bridge=1\n"
                + "    fi\n"
                + "  fi\n"
                + "  sleep 1\n"
                + "  i=$((i + 1))\n"
                + "done\n"
                + "settings put secure lockscreen.disabled 1\n"
                + "am start -a android.intent.action.MAIN -c android.intent.category.HOME "
                + "-f 0x04000000 -n com.magneo.compass/.MainActivity >/dev/null 2>&1\n"
                + "j=0\n"
                + "while [ \"$j\" -lt 6 ]; do\n"
                + "  if [ \"$(settings get secure lockscreen.disabled)\" = \"1\" ] && "
                + "dumpsys window policy | grep -q 'mShowingLockscreen=true'; then\n"
                + "    input swipe 400 760 400 120 350 >/dev/null 2>&1\n"
                + "  fi\n"
                + "  sleep 1\n"
                + "  j=$((j + 1))\n"
                + "done\n";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            putDeflated(zip, "module.prop", moduleProp.getBytes(StandardCharsets.UTF_8));
            putDeflated(zip, "customize.sh", customize.getBytes(StandardCharsets.UTF_8));
            putDeflated(zip, "system.prop", systemProp.getBytes(StandardCharsets.US_ASCII));
            putDeflated(zip, "post-fs-data.sh", postFsData.getBytes(StandardCharsets.US_ASCII));
            putDeflated(zip, "service.sh", service.getBytes(StandardCharsets.US_ASCII));
            putDeflated(zip, "system/media/bootanimation.zip", bootZip);
        }
    }

    private static void verifyBootZip(byte[] bytes) throws IOException {
        int part0 = 0;
        int part1 = 0;
        boolean descOk = false;
        BufferedImage first = null;
        BufferedImage last = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                byte[] data = zip.readAllBytes();
                if (entry.getMethod() != ZipEntry.STORED) {
                    throw new IOException("boot entry is not STORED: " + entry.getName());
                }
                if ("desc.txt".equals(entry.getName())) {
                    String desc = new String(data, StandardCharsets.US_ASCII);
                    descOk = desc.equals("800 800 12\np 1 0 part0\np 0 0 part1\n");
                } else if (entry.getName().startsWith("part0/") && entry.getName().endsWith(".jpg")) {
                    part0++;
                    if (first == null) first = ImageIO.read(new ByteArrayInputStream(data));
                    last = ImageIO.read(new ByteArrayInputStream(data));
                } else if (entry.getName().startsWith("part1/") && entry.getName().endsWith(".jpg")) {
                    part1++;
                }
            }
        }
        if (!descOk || part0 != PART0_FRAMES || part1 != PART1_FRAMES) {
            throw new IOException("invalid boot archive: desc=" + descOk
                    + " part0=" + part0 + " part1=" + part1);
        }
        if (first == null || last == null || first.getWidth() != SIZE || first.getHeight() != SIZE
                || last.getWidth() != SIZE || last.getHeight() != SIZE) {
            throw new IOException("invalid boot frame dimensions");
        }
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static void putStored(ZipOutputStream zip, String name, byte[] data) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc.getValue());
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void putDeflated(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }

    private static void circle(Graphics2D g, double cx, double cy, double radius) {
        g.draw(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
    }

    private static void dot(Graphics2D g, double cx, double cy, double radius) {
        g.fill(new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2));
    }

    private static double smoothRange(double value, double start, double end) {
        double x = Math.max(0, Math.min(1, (value - start) / (end - start)));
        return x * x * (3 - 2 * x);
    }
}
