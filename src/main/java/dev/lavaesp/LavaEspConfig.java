package dev.lavaesp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The three settings plus the on/off state: {@code color}, {@code fade}, {@code fadeStart}.
 *
 * <p>Defaults: cyan {@code 0xA000FFFF}, fade on, fade start 25 blocks (range 10..100).
 * Persisted as a plain properties file at {@code config/lavaesp.properties}.
 */
public final class LavaEspConfig {

    private static final Path FILE = Path.of("config", "lavaesp.properties");

    /** master switch; defaults to on so an installed mod is not silently inactive */

    public static boolean enabled = true;

    /** "Color" - 0xAARRGGBB, default = Color(0, 255, 255, 160) */
    public static int color = 0xA000FFFF;

    /** "Fade" */
    public static boolean fade = true;

    /** "Fade start" - 10.0 .. 100.0, default 25.0 */
    public static double fadeStart = 25.0;

    public static final double FADE_START_MIN = 10.0;
    public static final double FADE_START_MAX = 100.0;

    private LavaEspConfig() {
    }

    public static void load() {
        Properties p = new Properties();
        if (Files.isRegularFile(FILE)) {
            try (InputStream in = Files.newInputStream(FILE)) {
                p.load(in);
            } catch (IOException e) {
                LavaEspMod.LOGGER.warn("could not read {}", FILE, e);
            }
        }
        enabled = bool(p, "enabled", enabled);
        color = parseColor(p.getProperty("color"), color);
        fade = bool(p, "fade", fade);
        fadeStart = clamp(dbl(p, "fadeStart", fadeStart));
        save();
    }

    public static void save() {
        Properties p = new Properties();
        p.setProperty("enabled", Boolean.toString(enabled));
        p.setProperty("color", String.format("%08X", color));
        p.setProperty("fade", Boolean.toString(fade));
        p.setProperty("fadeStart", Double.toString(fadeStart));
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                p.store(out, "Lava ESP - Color is 0xAARRGGBB, Fade start is 10..100 blocks");
            }
        } catch (IOException e) {
            LavaEspMod.LOGGER.warn("could not write {}", FILE, e);
        }
    }

    public static double clamp(double v) {
        return Math.max(FADE_START_MIN, Math.min(FADE_START_MAX, v));
    }

    private static boolean bool(Properties p, String k, boolean def) {
        String v = p.getProperty(k);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    private static double dbl(Properties p, String k, double def) {
        try {
            String v = p.getProperty(k);
            return v == null ? def : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** accepts AARRGGBB or RRGGBB; falls back to {@code def} when unparsable */
    public static int parseColor(String s, int def) {
        Integer parsed = tryParseColor(s);
        return parsed == null ? def : parsed;
    }

    /** @return the ARGB value, or {@code null} when the text is not a colour */
    public static Integer tryParseColor(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        } else if (t.startsWith("0x") || t.startsWith("0X")) {
            t = t.substring(2);
        }
        if (t.length() == 6) {
            t = "FF" + t;
        }
        if (t.length() != 8) {
            return null;
        }
        try {
            return (int) Long.parseLong(t, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
