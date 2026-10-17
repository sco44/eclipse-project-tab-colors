package it.sco.tabcolors;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.eclipse.swt.graphics.RGB;

/**
 * Makes a project color readable on the current theme: keeps the hue and
 * saturation, and moves only the HSL lightness (lighter on dark backgrounds,
 * darker on light ones) until the WCAG contrast ratio reaches a minimum.
 * The stored project color is never changed; this runs at paint time.
 * <p>
 * Results are memoized: there are only a few projects, themes and variants, and
 * the explorer decorator and every tab repaint ask for the same colors again.
 * Thread-safe.
 */
final class ColorContrast {

    /** WCAG 2 AA for normal text. */
    static final double TEXT = 4.5;
    /** WCAG 2 for non-text UI components (the tab line). */
    static final double LINE = 3.0;

    /** sRGB channel (0-255) to linear light, so luminance needs no Math.pow at paint time. */
    private static final double[] LINEAR = new double[256];
    private static final int CACHE_LIMIT = 4096;
    private static final Map<Key, RGB> CACHE = new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            LINEAR[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
    }

    private record Key(RGB color, RGB background, double minContrast, double fraction, double floor) {
    }

    private ColorContrast() {
    }

    /**
     * The closest color to {@code color} (same hue and saturation) whose contrast
     * against {@code background} is at least {@code minContrast}. Returns
     * {@code color} unchanged if it already qualifies or the background is unknown.
     */
    static RGB readable(RGB color, RGB background, double minContrast) {
        if (color == null || background == null) {
            return color;
        }
        return cached(new Key(color, background, minContrast, -1, -1),
                () -> computeReadable(color, background, minContrast));
    }

    private static RGB computeReadable(RGB color, RGB background, double minContrast) {
        if (contrast(color, background) >= minContrast) {
            return color;
        }
        float[] hsl = toHsl(color);
        double toWhite = contrast(new RGB(255, 255, 255), background);
        double toBlack = contrast(new RGB(0, 0, 0), background);
        double target = toWhite >= toBlack ? 1 : 0;
        RGB extreme = fromHsl(hsl[0], hsl[1], (float) target);
        if (contrast(extreme, background) < minContrast) {
            return extreme; // unreachable (mid-gray background): best effort
        }
        // Luminance is monotonic in lightness, so binary search the smallest change.
        double ok = target;
        double bad = hsl[2];
        for (int i = 0; i < 24; i++) {
            double mid = (ok + bad) / 2;
            if (contrast(fromHsl(hsl[0], hsl[1], (float) mid), background) >= minContrast) {
                ok = mid;
            } else {
                bad = mid;
            }
        }
        return fromHsl(hsl[0], hsl[1], (float) ok);
    }

    /**
     * A softer variant for tabs without focus: the {@link #readable} color blended
     * {@code fraction} of the way toward the background (lighter on light themes),
     * but never below {@code floor} contrast.
     */
    static RGB soften(RGB color, RGB background, double minContrast, double fraction, double floor) {
        if (color == null || background == null) {
            return color;
        }
        return cached(new Key(color, background, minContrast, fraction, floor),
                () -> computeSoften(color, background, minContrast, fraction, floor));
    }

    private static RGB computeSoften(RGB color, RGB background, double minContrast, double fraction, double floor) {
        RGB base = readable(color, background, minContrast);
        RGB blended = mix(base, background, fraction);
        if (contrast(blended, background) >= floor) {
            return blended;
        }
        // Contrast falls monotonically while mixing toward the background: find the furthest mix.
        double ok = 0;
        double bad = fraction;
        for (int i = 0; i < 16; i++) {
            double mid = (ok + bad) / 2;
            if (contrast(mix(base, background, mid), background) >= floor) {
                ok = mid;
            } else {
                bad = mid;
            }
        }
        return mix(base, background, ok);
    }

    private static RGB cached(Key key, Supplier<RGB> compute) {
        RGB result = CACHE.get(key);
        if (result == null) {
            if (CACHE.size() >= CACHE_LIMIT) {
                CACHE.clear(); // only reached with pathological theme churn
            }
            result = compute.get();
            CACHE.put(key, result);
        }
        return result;
    }

    /** Linear blend from {@code a} (t = 0) to {@code b} (t = 1). */
    static RGB mix(RGB a, RGB b, double t) {
        return new RGB((int) Math.round(a.red + (b.red - a.red) * t),
                (int) Math.round(a.green + (b.green - a.green) * t),
                (int) Math.round(a.blue + (b.blue - a.blue) * t));
    }

    /** WCAG 2 contrast ratio, 1 to 21. */
    static double contrast(RGB a, RGB b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** WCAG 2 relative luminance, 0 (black) to 1 (white). */
    static double luminance(RGB c) {
        return 0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue);
    }

    private static double linear(int channel) {
        return LINEAR[channel];
    }

    /** {hue 0-360, saturation 0-1, lightness 0-1}. */
    static float[] toHsl(RGB c) {
        float r = c.red / 255f;
        float g = c.green / 255f;
        float b = c.blue / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2;
        float d = max - min;
        if (d == 0) {
            return new float[] { 0, 0, l };
        }
        float s = d / (1 - Math.abs(2 * l - 1));
        float h;
        if (max == r) {
            h = 60 * (((g - b) / d) % 6);
        } else if (max == g) {
            h = 60 * ((b - r) / d + 2);
        } else {
            h = 60 * ((r - g) / d + 4);
        }
        return new float[] { h < 0 ? h + 360 : h, s, l };
    }

    static RGB fromHsl(float h, float s, float l) {
        float c = (1 - Math.abs(2 * l - 1)) * s;
        float x = c * (1 - Math.abs((h / 60) % 2 - 1));
        float m = l - c / 2;
        float r;
        float g;
        float b;
        if (h < 60) {
            r = c; g = x; b = 0;
        } else if (h < 120) {
            r = x; g = c; b = 0;
        } else if (h < 180) {
            r = 0; g = c; b = x;
        } else if (h < 240) {
            r = 0; g = x; b = c;
        } else if (h < 300) {
            r = x; g = 0; b = c;
        } else {
            r = c; g = 0; b = x;
        }
        return new RGB(channel(r + m), channel(g + m), channel(b + m));
    }

    private static int channel(float v) {
        return Math.max(0, Math.min(255, Math.round(v * 255)));
    }
}
