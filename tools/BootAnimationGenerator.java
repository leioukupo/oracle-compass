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
    private static final int PART0_FRAMES = 64;
    private static final int PART1_FRAMES = 36;
    private static final long MAX_BOOT_ZIP_BYTES = 6L * 1024L * 1024L;
    private static final float JPEG_QUALITY = 0.72f;

    private static final double TAIJI_RADIUS = 90;
    private static final double CORE_RADIUS = 112;
    private static final double FLOWER_INNER_RADIUS = 118;
    private static final double FLOWER_OUTER_RADIUS = 227;
    private static final double FLOWER_RING_RADIUS = 247;
    private static final double INNER_RING_RADIUS = 266;
    private static final double BAGUA_RADIUS = 292;
    private static final double MIDDLE_RING_RADIUS = 338;
    private static final double OUTER_RING_RADIUS = 382;

    private static final Color BG = new Color(7, 6, 4);
    private static final Color GOLD = new Color(212, 175, 55);
    private static final Color GOLD_MID = new Color(178, 143, 50);
    private static final Color GOLD_DARK = new Color(145, 116, 48);
    private static final double LOOP_PULSE = 0.5
            + 0.5 * Math.sin((PART0_FRAMES - 1) * 0.43);

    private BootAnimationGenerator() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException(
                    "usage: BootAnimationGenerator <output-dir> [version]");
        }
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        String version = args.length == 2 ? args[1].trim() : "0.2.5";
        Files.createDirectories(output);
        verifyGeometry();

        List<byte[]> frames = new ArrayList<>(PART0_FRAMES);
        for (int i = 0; i < PART0_FRAMES; i++) {
            BufferedImage frame = drawFrame(i);
            verifyBlackGoldPalette(frame);
            frames.add(encodeJpeg(frame, JPEG_QUALITY));
        }
        List<byte[]> loopFrames = new ArrayList<>(PART1_FRAMES);
        for (int i = 0; i < PART1_FRAMES; i++) {
            // Phase zero is already the final intro frame. Start at phase 1/36 so the
            // boot player never holds the same picture for two frame intervals.
            BufferedImage frame = drawLoopFrame(i + 1);
            verifyBlackGoldPalette(frame);
            loopFrames.add(encodeJpeg(frame, JPEG_QUALITY));
        }
        verifyLoopSeam(drawLoopFrame(0), drawLoopFrame(PART1_FRAMES));
        verifyLoopSeam(drawFrame(PART0_FRAMES - 1), drawLoopFrame(0));
        verifyEncodedMotion(frames, loopFrames);
        byte[] bootZip = buildBootZip(frames, loopFrames);
        byte[] shutdownZip = buildShutdownZip(frames.get(0));
        verifyBootZip(bootZip);
        if (bootZip.length > MAX_BOOT_ZIP_BYTES) {
            throw new IOException("bootanimation.zip is too large: " + bootZip.length);
        }

        Path module = output.resolve("oracle-compass-bootanimation-v" + version + ".zip");
        writeModule(module, version, bootZip, shutdownZip);
        Path preview = output.resolve("oracle-compass-bootanimation-preview.jpg");
        Files.write(preview, frames.get(PART0_FRAMES - 1));
        Path firstFrame = output.resolve("oracle-compass-first-frame.jpg");
        Files.write(firstFrame, frames.get(0));

        System.out.println("bootanimation: " + bootZip.length + " bytes, "
                + PART0_FRAMES + "+" + PART1_FRAMES + " frames, "
                + SIZE + "x" + SIZE + "@" + FPS);
        System.out.println("module: " + module);
        System.out.println("preview: " + preview);
        System.out.println("first frame: " + firstFrame);
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
        // The physical MTK logo slot uses this exact encoded frame, so frame zero must
        // already be a complete, deliberate image rather than an almost black placeholder.
        double core = 1.0;
        double outerRing = smoothRange(t, 0.08, 0.34);
        double middleRing = smoothRange(t, 0.15, 0.42);
        double innerRing = smoothRange(t, 0.22, 0.50);
        double largePetals = smoothRange(t, 0.27, 0.52);
        double mediumPetals = smoothRange(t, 0.34, 0.59);
        double smallPetals = smoothRange(t, 0.41, 0.66);
        double taiji = 1.0;
        double settle = smoothRange(t, 0.70, 1.00);
        double pulse = 0.5 + 0.5 * Math.sin(frame * 0.43);
        // Reach the loop's exact angle and angular velocity at the handoff. The
        // 1.25t^2-0.25t^3 curve starts at rest and ends at a 1.75D normalized
        // slope, matching a D-degree turn over the 3-second loop.
        double introPetalMotion = 1.25 * t * t - 0.25 * t * t * t;
        double cx = SIZE / 2.0;
        double cy = SIZE / 2.0;

        drawGlow(g, cx, cy, 16 + spark * 70, GOLD, (float) (0.32 * spark));
        drawInstrument(g, cx, cy, outerRing, middleRing, innerRing,
                -7.5 * (1.0 - settle), 22.5 * (1.0 - settle),
                -15.0 * (1.0 - settle), pulse);
        drawBagua(g, cx, cy, innerRing, -45.0 * introPetalMotion);
        drawLotus(g, cx, cy, largePetals, mediumPetals, smallPetals, 0,
                30.0 * introPetalMotion, -60.0 * introPetalMotion,
                90.0 * introPetalMotion);
        drawFlowerBoundary(g, cx, cy,
                Math.max(largePetals, Math.max(mediumPetals, smallPetals)));
        drawCoreMedallion(g, cx, cy, core, taiji, 0);

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

        double cycle = frame / (double) PART1_FRAMES;
        double cx = SIZE / 2.0;
        double cy = SIZE / 2.0;

        drawGlow(g, cx, cy, 86, GOLD, 0.32f);
        drawInstrument(g, cx, cy, 1.0, 1.0, 1.0,
                7.5 * cycle, -22.5 * cycle, 15.0 * cycle, LOOP_PULSE);
        drawBagua(g, cx, cy, 1.0, -45.0 * cycle);
        // Each layer completes an integer number of 30-degree symmetry periods.
        // The loop therefore closes continuously while the three layers keep moving
        // at visibly different speeds.
        drawLotus(g, cx, cy, 1.0, 1.0, 1.0, 0,
                30.0 * cycle, -60.0 * cycle, 90.0 * cycle);
        drawFlowerBoundary(g, cx, cy, 1.0);
        drawCoreMedallion(g, cx, cy, 1.0, 1.0, 0);

        g.dispose();
        return image;
    }

    private static void drawInstrument(Graphics2D g, double cx, double cy,
                                       double outerAmount, double middleAmount,
                                       double innerAmount, double outerRotation,
                                       double middleRotation, double innerRotation,
                                       double pulse) {
        if (outerAmount > 0) {
            float a = (float) outerAmount;
            g.setComposite(AlphaComposite.SrcOver.derive(0.70f * a));
            g.setColor(GOLD_DARK);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            circle(g, cx, cy, OUTER_RING_RADIUS);
            for (int i = 0; i < 48; i++) {
                double angle = Math.toRadians(i * 7.5 - 90 + outerRotation);
                g.setComposite(AlphaComposite.SrcOver.derive(0.46f * a));
                g.setColor(GOLD_DARK);
                g.setStroke(new BasicStroke(1.4f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                radialLine(g, cx, cy, angle, OUTER_RING_RADIUS - 13, OUTER_RING_RADIUS);
            }
            for (int i = 0; i < 8; i++) {
                double angle = Math.toRadians(i * 45 - 90);
                g.setComposite(AlphaComposite.SrcOver.derive(0.92f * a));
                g.setColor(GOLD);
                g.setStroke(new BasicStroke(3f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                radialLine(g, cx, cy, angle, OUTER_RING_RADIUS - 28, OUTER_RING_RADIUS);
            }
        }

        if (middleAmount > 0) {
            float a = (float) middleAmount;
            g.setComposite(AlphaComposite.SrcOver.derive(0.58f * a));
            g.setColor(GOLD_DARK);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            circle(g, cx, cy, MIDDLE_RING_RADIUS);
            circle(g, cx, cy, MIDDLE_RING_RADIUS - 18);
            for (int i = 0; i < 16; i++) {
                double start = i * 22.5 - 90 + middleRotation;
                g.setComposite(AlphaComposite.SrcOver.derive(
                        (float) ((0.48 + pulse * 0.20) * a)));
                g.setColor(GOLD_DARK);
                g.setStroke(new BasicStroke(2f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(cx - (MIDDLE_RING_RADIUS - 9),
                        cy - (MIDDLE_RING_RADIUS - 9),
                        (MIDDLE_RING_RADIUS - 9) * 2, (MIDDLE_RING_RADIUS - 9) * 2,
                        start, 12.5, Arc2D.OPEN));
            }
            for (int i = 0; i < 4; i++) {
                double start = i * 90 - 90;
                g.setComposite(AlphaComposite.SrcOver.derive(0.82f * a));
                g.setColor(GOLD);
                g.setStroke(new BasicStroke(3f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Arc2D.Double(cx - (MIDDLE_RING_RADIUS - 9),
                        cy - (MIDDLE_RING_RADIUS - 9),
                        (MIDDLE_RING_RADIUS - 9) * 2, (MIDDLE_RING_RADIUS - 9) * 2,
                        start, 12.5, Arc2D.OPEN));
            }
        }

        if (innerAmount > 0) {
            float a = (float) innerAmount;
            g.setComposite(AlphaComposite.SrcOver.derive(0.72f * a));
            g.setColor(GOLD_DARK);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            circle(g, cx, cy, INNER_RING_RADIUS);
            for (int i = 0; i < 24; i++) {
                double angle = Math.toRadians(i * 15 - 90 + innerRotation);
                g.setComposite(AlphaComposite.SrcOver.derive(0.48f * a));
                g.setColor(GOLD_DARK);
                g.setStroke(new BasicStroke(1.3f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                radialLine(g, cx, cy, angle, INNER_RING_RADIUS - 6, INNER_RING_RADIUS);
            }
            for (int i = 0; i < 8; i++) {
                double angle = Math.toRadians(i * 45 - 90);
                g.setComposite(AlphaComposite.SrcOver.derive(0.90f * a));
                g.setColor(GOLD);
                g.setStroke(new BasicStroke(2.6f,
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                radialLine(g, cx, cy, angle, INNER_RING_RADIUS - 10, INNER_RING_RADIUS);
            }
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawLotus(Graphics2D g, double cx, double cy,
                                  double largeAmount, double mediumAmount,
                                  double smallAmount, double breathe,
                                  double largeRotation, double mediumRotation,
                                  double smallRotation) {
        drawPetalLayer(g, cx, cy, largeAmount, breathe, largeRotation, 12,
                CORE_RADIUS - 40, FLOWER_OUTER_RADIUS, 64,
                GOLD_DARK, 0.24f, 0.86f);
        drawPetalLayer(g, cx, cy, mediumAmount, breathe, 10.0 + mediumRotation, 12,
                CORE_RADIUS - 40, 188, 48,
                GOLD_MID, 0.30f, 0.92f);
        drawPetalLayer(g, cx, cy, smallAmount, breathe, 20.0 + smallRotation, 12,
                CORE_RADIUS - 40, 160, 34,
                GOLD, 0.38f, 0.98f);
    }

    private static void drawPetalLayer(Graphics2D g, double cx, double cy,
                                       double amount, double breathe, double rotation,
                                       int count,
                                       double root, double fullTip, double fullWidth,
                                       Color color, float fillAlpha, float strokeAlpha) {
        if (amount <= 0) return;
        double extension = (fullTip - root) * amount * (1.0 + breathe * 0.02);
        double tip = Math.min(FLOWER_OUTER_RADIUS, root + extension);
        double width = Math.max(2, fullWidth * amount);
        for (int i = 0; i < count; i++) {
            Graphics2D pg = (Graphics2D) g.create();
            pg.rotate(Math.toRadians(i * (360.0 / count) - 90 + rotation), cx, cy);
            Path2D petal = new Path2D.Double();
            petal.moveTo(cx + root, cy);
            petal.curveTo(cx + root + extension * 0.10, cy - width * 0.42,
                    cx + root + extension * 0.54, cy - width, cx + tip, cy);
            petal.curveTo(cx + root + extension * 0.54, cy + width,
                    cx + root + extension * 0.10, cy + width * 0.42, cx + root, cy);
            petal.closePath();
            pg.setComposite(AlphaComposite.SrcOver.derive((float) (fillAlpha * amount)));
            pg.setColor(color);
            pg.fill(petal);
            pg.setComposite(AlphaComposite.SrcOver.derive((float) (strokeAlpha * amount)));
            pg.setColor(color);
            pg.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            pg.draw(petal);
            pg.setComposite(AlphaComposite.SrcOver.derive((float) (0.28 * amount)));
            pg.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            pg.drawLine((int) (cx + root + 8), (int) cy,
                    (int) Math.max(cx + root + 8, cx + tip - 14), (int) cy);
            pg.dispose();
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawBagua(Graphics2D g, double cx, double cy,
                                  double amount, double rotation) {
        if (amount <= 0) return;
        int[] trigrams = {7, 3, 5, 1, 0, 4, 2, 6};
        g.setComposite(AlphaComposite.SrcOver.derive((float) (0.90 * amount)));
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(3.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 8; i++) {
            Graphics2D bg = (Graphics2D) g.create();
            bg.rotate(Math.toRadians(i * 45.0 - 90.0 + rotation), cx, cy);
            int pattern = trigrams[i];
            for (int row = 0; row < 3; row++) {
                double x = cx + BAGUA_RADIUS + (row - 1) * 11.0;
                boolean solid = ((pattern >> row) & 1) != 0;
                if (solid) {
                    bg.drawLine((int) Math.round(x), (int) Math.round(cy - 16),
                            (int) Math.round(x), (int) Math.round(cy + 16));
                } else {
                    bg.drawLine((int) Math.round(x), (int) Math.round(cy - 16),
                            (int) Math.round(x), (int) Math.round(cy - 4));
                    bg.drawLine((int) Math.round(x), (int) Math.round(cy + 4),
                            (int) Math.round(x), (int) Math.round(cy + 16));
                }
            }
            bg.dispose();
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawFlowerBoundary(Graphics2D g, double cx, double cy, double amount) {
        if (amount <= 0) return;
        g.setComposite(AlphaComposite.SrcOver.derive((float) (0.64 * amount)));
        g.setColor(GOLD_DARK);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        circle(g, cx, cy, FLOWER_RING_RADIUS);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static void drawCoreMedallion(Graphics2D g, double cx, double cy,
                                          double coreAmount, double taijiAmount,
                                          double breathe) {
        if (coreAmount <= 0) return;
        float a = (float) coreAmount;
        g.setComposite(AlphaComposite.SrcOver.derive(a));
        g.setColor(BG);
        dot(g, cx, cy, CORE_RADIUS);
        g.setColor(GOLD_DARK);
        g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        circle(g, cx, cy, CORE_RADIUS);
        g.setComposite(AlphaComposite.SrcOver.derive(0.58f * a));
        g.setColor(GOLD);
        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        circle(g, cx, cy, CORE_RADIUS - 8);
        if (taijiAmount > 0) {
            drawTaiji(g, cx, cy, TAIJI_RADIUS + breathe * 1.2, taijiAmount * coreAmount);
        } else {
            g.setComposite(AlphaComposite.SrcOver.derive(a));
            g.setColor(GOLD);
            dot(g, cx, cy, 7);
        }
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

    private static byte[] buildShutdownZip(byte[] frame) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            putStored(zip, "desc.txt", (SIZE + " " + SIZE + " " + FPS
                    + "\np 0 0 part1\n").getBytes(StandardCharsets.US_ASCII));
            putStored(zip, "part1/001.jpg", frame);
        }
        return bytes.toByteArray();
    }

    private static void writeModule(Path output, String version, byte[] bootZip,
                                    byte[] shutdownZip) throws IOException {
        String moduleProp = "id=oracle_compass_bootanimation\n"
                + "name=Oracle Compass Golden Lotus Compass\n"
                + "version=" + version + "\n"
                + "versionCode=9\n"
                + "author=oracle-compass\n"
                + "description=800x800 boot animation and seamless HOME handoff for MAGNEO C110001 / Android 5.1\n";
        String customize = "ui_print \"- Oracle Compass golden lotus compass\"\n"
                + "[ \"$API\" = \"22\" ] || abort \"Android 5.1 / API 22 required\"\n"
                + "[ -f /system/media/bootanimation.zip ] || abort \"Original bootanimation.zip not found\"\n"
                + "set_perm \"$MODPATH/system/media/bootanimation.zip\" 0 0 0644\n"
                + "set_perm \"$MODPATH/system/media/shutanimation.zip\" 0 0 0644\n"
                + "set_perm \"$MODPATH/post-fs-data.sh\" 0 0 0755\n"
                + "set_perm \"$MODPATH/service.sh\" 0 0 0755\n"
                + "set_perm \"$MODPATH/uninstall.sh\" 0 0 0755\n";
        // These properties are dynamic because the Web console can restore the system lockscreen.
        String systemProp = "# Oracle Compass boot preferences are applied by boot scripts.\n";
        String postFsData = "#!/system/bin/sh\n"
                + "state_dir=/data/adb/oracle-compass\n"
                + "mkdir -p \"$state_dir\"\n"
                + "chmod 700 \"$state_dir\"\n"
                + "if [ -f \"$state_dir/root-grant-notifications-enabled\" ]; then\n"
                + "  magisk --sqlite \"DROP TRIGGER IF EXISTS oracle_compass_silent_policies;"
                + "UPDATE policies SET notification=1;\" >/dev/null 2>&1\n"
                + "else\n"
                + "  magisk --sqlite \"UPDATE policies SET notification=0;"
                + "DROP TRIGGER IF EXISTS oracle_compass_silent_policies;"
                + "CREATE TRIGGER oracle_compass_silent_policies AFTER INSERT ON policies "
                + "WHEN NEW.notification!=0 BEGIN UPDATE policies SET notification=0 "
                + "WHERE uid=NEW.uid; END;\" >/dev/null 2>&1\n"
                + "fi\n"
                + "if [ -f \"$state_dir/system-lockscreen-enabled\" ]; then\n"
                + "  resetprop -n curlockscreen 1\n"
                + "  resetprop -n ro.lockscreen.disable.default false\n"
                + "else\n"
                + "  resetprop -n curlockscreen 0\n"
                + "  resetprop -n ro.lockscreen.disable.default true\n"
                + "fi\n"
                + "boot_log=/data/local/oracle-compass-boot.log\n"
                + "ready_file=/data/data/com.magneo.compass/files/boot-handoff-ready\n"
                + "rm -f \"$ready_file\"\n"
                + "(\n"
                + "  guard=0\n"
                + "  while [ ! -f \"$ready_file\" ] && [ \"$guard\" -lt 450 ]; do\n"
                + "    resetprop -n service.bootanim.exit 0\n"
                + "    sleep 0.1\n"
                + "    guard=$((guard + 1))\n"
                + "  done\n"
                + "  if [ -f \"$ready_file\" ]; then\n"
                + "    echo \"$(cat /proc/uptime) early guard ready\" >> \"$boot_log\"\n"
                + "  else\n"
                + "    echo \"$(cat /proc/uptime) early guard timeout\" >> \"$boot_log\"\n"
                + "  fi\n"
                + "  resetprop -n service.bootanim.exit 1\n"
                + ") >/dev/null 2>&1 &\n";
        String service = "#!/system/bin/sh\n"
                + "i=0\n"
                + "bridge=0\n"
                + "wifi_attempts=0\n"
                + "state_dir=/data/adb/oracle-compass\n"
                + "boot_log=/data/local/oracle-compass-boot.log\n"
                + "ready_file=/data/data/com.magneo.compass/files/boot-handoff-ready\n"
                + ": > \"$boot_log\"\n"
                + "echo \"$(cat /proc/uptime) service start\" >> \"$boot_log\"\n"
                + "if [ -f \"$state_dir/system-lockscreen-enabled\" ]; then\n"
                + "  lock_disabled=0\n"
                + "  resetprop -n curlockscreen 1\n"
                + "  resetprop -n ro.lockscreen.disable.default false\n"
                + "else\n"
                + "  lock_disabled=1\n"
                + "  resetprop -n curlockscreen 0\n"
                + "  resetprop -n ro.lockscreen.disable.default true\n"
                + "fi\n"
                + "(\n"
                + "  lock_wait=0\n"
                + "  while ! service check lock_settings 2>/dev/null | grep -q ': found'; do\n"
                + "    lock_wait=$((lock_wait + 1))\n"
                + "    [ \"$lock_wait\" -ge 400 ] && break\n"
                + "    sleep 0.1\n"
                + "  done\n"
                + "  if service check lock_settings 2>/dev/null | grep -q ': found'; then\n"
                + "    service call lock_settings 1 s16 lockscreen.disabled i32 \"$lock_disabled\" i32 0 >> \"$boot_log\" 2>&1\n"
                + "    service call lock_settings 4 s16 lockscreen.disabled i32 0 i32 0 >> \"$boot_log\" 2>&1\n"
                + "    echo \"$(cat /proc/uptime) lock_settings applied disabled=$lock_disabled\" >> \"$boot_log\"\n"
                + "  else\n"
                + "    echo \"$(cat /proc/uptime) lock_settings unavailable\" >> \"$boot_log\"\n"
                + "  fi\n"
                + ") &\n"
                + "boot_ticks_set=0\n"
                + "while [ ! -f \"$ready_file\" ] && [ \"$i\" -lt 450 ]; do\n"
                + "  resetprop -n service.bootanim.exit 0\n"
                + "  if [ \"$boot_ticks_set\" = \"0\" ]; then\n"
                + "    boot_pid=\"$(pidof bootanimation 2>/dev/null | awk '{print $1}')\"\n"
                + "    if [ -n \"$boot_pid\" ] && [ -r \"/proc/$boot_pid/stat\" ]; then\n"
                + "      boot_ticks=\"$(awk '{print $22}' /proc/$boot_pid/stat)\"\n"
                + "      resetprop -n oracle.bootanim.start_ticks \"$boot_ticks\"\n"
                + "      echo \"$(cat /proc/uptime) bootanim pid=$boot_pid ticks=$boot_ticks\" >> \"$boot_log\"\n"
                + "      boot_ticks_set=1\n"
                + "    fi\n"
                + "  fi\n"
                + "  if [ $((i % 20)) -eq 0 ] && [ \"$wifi_attempts\" -lt 15 ]; then\n"
                + "    svc wifi enable >/dev/null 2>&1\n"
                + "    wifi_attempts=$((wifi_attempts + 1))\n"
                + "  fi\n"
                + "  if [ \"$bridge\" = \"0\" ]; then\n"
                + "    if pm path com.magneo.compass >/dev/null 2>&1; then\n"
                + "      pm disable fr.neamar.kiss/.MainActivity >/dev/null 2>&1\n"
                + "      pm disable com.android.launcher3/.Launcher >/dev/null 2>&1\n"
                + "      am start -a android.intent.action.MAIN -f 0x04000000 "
                + "-n com.magneo.compass/.BootHandoffActivity >/dev/null 2>&1\n"
                + "      if dumpsys activity activities | grep -q 'com.magneo.compass/.BootHandoffActivity'; then\n"
                + "        bridge=1\n"
                + "        echo \"$(cat /proc/uptime) handoff activity ready\" >> \"$boot_log\"\n"
                + "      fi\n"
                + "    fi\n"
                + "  fi\n"
                + "  sleep 0.1\n"
                + "  i=$((i + 1))\n"
                + "done\n"
                + "if [ -f \"$ready_file\" ]; then\n"
                + "  echo \"$(cat /proc/uptime) main frame ready; releasing bootanimation\" >> \"$boot_log\"\n"
                + "else\n"
                + "  echo \"$(cat /proc/uptime) handoff timeout; releasing bootanimation\" >> \"$boot_log\"\n"
                + "fi\n"
                + "resetprop -n service.bootanim.exit 1\n"
                + "am start -a android.intent.action.MAIN -c android.intent.category.HOME "
                + "-f 0x04000000 -n com.magneo.compass/.MainActivity >/dev/null 2>&1\n"
                + "dumpsys window policy | grep -E 'mScreenOn|mShowingLockscreen' >> \"$boot_log\" 2>&1\n"
                + "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp' >> \"$boot_log\" 2>&1\n";
        String uninstall = "#!/system/bin/sh\n"
                + "pm enable fr.neamar.kiss/.MainActivity >/dev/null 2>&1\n"
                + "pm enable com.android.launcher3/.Launcher >/dev/null 2>&1\n"
                + "magisk --sqlite \"DROP TRIGGER IF EXISTS oracle_compass_silent_policies;"
                + "UPDATE policies SET notification=1;\" >/dev/null 2>&1\n"
                + "rm -rf /data/adb/oracle-compass\n"
                + "service call lock_settings 1 s16 lockscreen.disabled i32 0 i32 0 >/dev/null 2>&1\n"
                + "resetprop -n curlockscreen 1\n"
                + "resetprop -n ro.lockscreen.disable.default false\n";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            putDeflated(zip, "module.prop", moduleProp.getBytes(StandardCharsets.UTF_8));
            putDeflated(zip, "customize.sh", customize.getBytes(StandardCharsets.UTF_8));
            putDeflated(zip, "system.prop", systemProp.getBytes(StandardCharsets.US_ASCII));
            putDeflated(zip, "post-fs-data.sh", postFsData.getBytes(StandardCharsets.US_ASCII));
            putDeflated(zip, "service.sh", service.getBytes(StandardCharsets.US_ASCII));
            putDeflated(zip, "uninstall.sh", uninstall.getBytes(StandardCharsets.US_ASCII));
            putDeflated(zip, "system/media/bootanimation.zip", bootZip);
            putDeflated(zip, "system/media/shutanimation.zip", shutdownZip);
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

    private static void radialLine(Graphics2D g, double cx, double cy, double angle,
                                   double inner, double outer) {
        g.drawLine((int) Math.round(cx + Math.cos(angle) * inner),
                (int) Math.round(cy + Math.sin(angle) * inner),
                (int) Math.round(cx + Math.cos(angle) * outer),
                (int) Math.round(cy + Math.sin(angle) * outer));
    }

    private static void verifyGeometry() throws IOException {
        if (TAIJI_RADIUS >= CORE_RADIUS
                || CORE_RADIUS + 4 >= FLOWER_INNER_RADIUS
                || FLOWER_OUTER_RADIUS >= FLOWER_RING_RADIUS
                || FLOWER_RING_RADIUS + 8 >= INNER_RING_RADIUS
                || INNER_RING_RADIUS + 14 >= BAGUA_RADIUS
                || BAGUA_RADIUS + 20 >= MIDDLE_RING_RADIUS
                || INNER_RING_RADIUS >= MIDDLE_RING_RADIUS
                || MIDDLE_RING_RADIUS >= OUTER_RING_RADIUS
                || OUTER_RING_RADIUS >= SIZE / 2.0) {
            throw new IOException("invalid lotus/ring geometry");
        }
    }

    private static void verifyBlackGoldPalette(BufferedImage image) throws IOException {
        for (int y = 0; y < image.getHeight(); y += 4) {
            for (int x = 0; x < image.getWidth(); x += 4) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                if (b > r + 6 && b > g + 6) {
                    throw new IOException("non black-gold pixel at " + x + "," + y);
                }
            }
        }
    }

    private static void verifyLoopSeam(BufferedImage start, BufferedImage end)
            throws IOException {
        double average = frameDifference(start, end);
        if (average > 0.8) {
            throw new IOException(String.format(Locale.ROOT,
                    "loop seam mismatch: %.3f", average));
        }
    }

    private static void verifyEncodedMotion(List<byte[]> intro, List<byte[]> loop)
            throws IOException {
        List<BufferedImage> decoded = new ArrayList<>(PART1_FRAMES);
        for (byte[] frame : loop) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(frame));
            if (image == null) throw new IOException("unable to decode encoded loop frame");
            decoded.add(image);
        }
        double[] steps = new double[PART1_FRAMES];
        double sum = 0;
        for (int i = 0; i < PART1_FRAMES; i++) {
            steps[i] = frameDifference(decoded.get(i), decoded.get((i + 1) % PART1_FRAMES));
            sum += steps[i];
        }
        double mean = sum / steps.length;
        double variance = 0;
        for (double step : steps) variance += (step - mean) * (step - mean);
        double deviation = Math.sqrt(variance / steps.length);
        double cv = mean <= 0 ? Double.POSITIVE_INFINITY : deviation / mean;
        double seamDeviation = mean <= 0 ? Double.POSITIVE_INFINITY
                : Math.abs(steps[PART1_FRAMES - 1] - mean) / mean;

        BufferedImage introLast = ImageIO.read(new ByteArrayInputStream(
                intro.get(intro.size() - 1)));
        double introStep = frameDifference(introLast, decoded.get(0));
        double introDeviation = mean <= 0 ? Double.POSITIVE_INFINITY
                : Math.abs(introStep - mean) / mean;
        if (cv > 0.05) {
            throw new IOException(String.format(Locale.ROOT,
                    "encoded loop speed variation %.2f%% exceeds 5%%", cv * 100));
        }
        if (seamDeviation > 0.10) {
            throw new IOException(String.format(Locale.ROOT,
                    "encoded loop seam deviation %.2f%% exceeds 10%%", seamDeviation * 100));
        }
        if (introDeviation > 0.15 || introStep < mean * 0.50) {
            throw new IOException(String.format(Locale.ROOT,
                    "intro-loop handoff deviation %.2f%% (step %.3f, mean %.3f)",
                    introDeviation * 100, introStep, mean));
        }
        System.out.println(String.format(Locale.ROOT,
                "encoded motion: mean=%.3f cv=%.2f%% seam=%.2f%% intro=%.2f%%",
                mean, cv * 100, seamDeviation * 100, introDeviation * 100));
    }

    private static double frameDifference(BufferedImage first, BufferedImage second) {
        long difference = 0;
        long samples = 0;
        for (int y = 0; y < SIZE; y += 2) {
            for (int x = 0; x < SIZE; x += 2) {
                int a = first.getRGB(x, y);
                int b = second.getRGB(x, y);
                difference += Math.abs(((a >> 16) & 0xff) - ((b >> 16) & 0xff));
                difference += Math.abs(((a >> 8) & 0xff) - ((b >> 8) & 0xff));
                difference += Math.abs((a & 0xff) - (b & 0xff));
                samples += 3;
            }
        }
        return difference / (double) samples;
    }

    private static double smoothRange(double value, double start, double end) {
        double x = Math.max(0, Math.min(1, (value - start) / (end - start)));
        return x * x * (3 - 2 * x);
    }
}
