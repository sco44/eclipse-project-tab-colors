package it.sco.tabcolors;

import org.eclipse.swt.graphics.RGB;

/**
 * Makes a project color readable on the current theme: keeps the hue and
 * saturation, and moves only the HSL lightness (lighter on dark backgrounds,
 * darker on light ones) until the WCAG contrast ratio reaches a minimum.
 * The stored project color is never changed; this runs at paint time.
 */
final class ColorContrast {

    /** WCAG 2 AA for normal text. */
    static final double TEXT = 4.5;
    /** WCAG 2 for non-text UI components (the tab line). */
    static final double LINE = 3.0;

    private ColorContrast() {
    }

    /**
     * The closest color to {@code color} (same hue and saturation) whose contrast
     * against {@code background} is at least {@code minContrast}. Returns
     * {@code color} unchanged if it already qualifies or the background is unknown.
     */
    static RGB readable(RGB color, RGB background, double minContrast) {
        if (color == null || background == null || contrast(color, background) >= minContrast) {
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
        double v = channel / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
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
