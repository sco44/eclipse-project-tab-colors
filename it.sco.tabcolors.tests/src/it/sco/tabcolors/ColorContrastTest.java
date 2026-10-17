package it.sco.tabcolors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;

class ColorContrastTest {

    private static final RGB WHITE = new RGB(255, 255, 255);
    private static final RGB BLACK = new RGB(0, 0, 0);
    private static final RGB LIGHT_TAB = new RGB(0xF0, 0xF0, 0xF0);
    private static final RGB DARK_TAB = new RGB(0x2F, 0x2F, 0x2F);

    @Test
    void contrastMatchesWcagReferenceValues() {
        assertEquals(21.0, ColorContrast.contrast(WHITE, BLACK), 0.01);
        assertEquals(1.0, ColorContrast.contrast(DARK_TAB, DARK_TAB), 0.0001);
        // #777777 on white is the classic ~4.48:1 example
        assertEquals(4.48, ColorContrast.contrast(new RGB(0x77, 0x77, 0x77), WHITE), 0.01);
    }

    @Test
    void readableColorIsReturnedUnchanged() {
        RGB darkRed = new RGB(0x90, 0x20, 0x20);
        assertSame(darkRed, ColorContrast.readable(darkRed, WHITE, ColorContrast.TEXT));
    }

    @Test
    void unknownBackgroundLeavesColorUnchanged() {
        RGB c = new RGB(1, 2, 3);
        assertSame(c, ColorContrast.readable(c, null, ColorContrast.TEXT));
        assertNull(ColorContrast.readable(null, WHITE, ColorContrast.TEXT));
    }

    /** Light theme: a bright hashed color (e.g. yellow-green) gets darker but keeps its hue. */
    @Test
    void brightColorIsDarkenedOnLightTheme() {
        RGB hashed = ProjectColorStore.hashed("a"); // #82D94C, hue 97
        assertTrue(ColorContrast.contrast(hashed, LIGHT_TAB) < ColorContrast.TEXT, "precondition");

        RGB adjusted = ColorContrast.readable(hashed, LIGHT_TAB, ColorContrast.TEXT);

        assertTrue(ColorContrast.contrast(adjusted, LIGHT_TAB) >= ColorContrast.TEXT);
        assertTrue(ColorContrast.luminance(adjusted) < ColorContrast.luminance(hashed), "darker");
        assertHueKept(hashed, adjusted);
    }

    /** Dark theme: a deep color (e.g. navy) gets lighter but keeps its hue. */
    @Test
    void deepColorIsLightenedOnDarkTheme() {
        RGB navy = new RGB(0x20, 0x30, 0x90);
        RGB adjusted = ColorContrast.readable(navy, DARK_TAB, ColorContrast.TEXT);

        assertTrue(ColorContrast.contrast(adjusted, DARK_TAB) >= ColorContrast.TEXT);
        assertTrue(ColorContrast.luminance(adjusted) > ColorContrast.luminance(navy), "lighter");
        assertHueKept(navy, adjusted);
    }

    @Test
    void adjustmentIsMinimal() {
        RGB hashed = ProjectColorStore.hashed("a");
        RGB adjusted = ColorContrast.readable(hashed, LIGHT_TAB, ColorContrast.TEXT);
        float[] hsl = ColorContrast.toHsl(adjusted);
        // a little lighter would no longer be readable: the search went no darker than needed
        RGB lighter = ColorContrast.fromHsl(hsl[0], hsl[1], hsl[2] + 0.01f);
        assertTrue(ColorContrast.contrast(lighter, LIGHT_TAB) < ColorContrast.TEXT);
    }

    @Test
    void lineNeedsLessContrastThanText() {
        RGB hashed = ProjectColorStore.hashed("a");
        RGB line = ColorContrast.readable(hashed, LIGHT_TAB, ColorContrast.LINE);
        RGB text = ColorContrast.readable(hashed, LIGHT_TAB, ColorContrast.TEXT);
        assertTrue(ColorContrast.luminance(line) > ColorContrast.luminance(text));
    }

    @Test
    void softenedColorIsLighterOnLightTheme() {
        RGB color = new RGB(0xC0, 0x30, 0x30);
        RGB focused = ColorContrast.readable(color, LIGHT_TAB, ColorContrast.TEXT);
        RGB unfocused = ColorContrast.soften(color, LIGHT_TAB, ColorContrast.TEXT, 0.3, 3.0);

        assertTrue(ColorContrast.luminance(unfocused) > ColorContrast.luminance(focused), "lighter");
        assertTrue(ColorContrast.contrast(unfocused, LIGHT_TAB) >= 3.0, "still readable");
    }

    @Test
    void softenRespectsFloor() {
        RGB color = new RGB(0x80, 0x80, 0x20);
        RGB unfocused = ColorContrast.soften(color, LIGHT_TAB, ColorContrast.TEXT, 1.0, 3.0);
        assertEquals(3.0, ColorContrast.contrast(unfocused, LIGHT_TAB), 0.15);
    }

    @Test
    void mixBlendsLinearly() {
        assertEquals(BLACK, ColorContrast.mix(BLACK, WHITE, 0));
        assertEquals(WHITE, ColorContrast.mix(BLACK, WHITE, 1));
        assertEquals(new RGB(128, 128, 128), ColorContrast.mix(BLACK, WHITE, 0.5));
    }

    @Test
    void unreachableContrastGivesExtreme() {
        RGB midGray = new RGB(0x76, 0x76, 0x76);
        RGB adjusted = ColorContrast.readable(new RGB(0x80, 0x10, 0x10), midGray, 21);
        assertTrue(adjusted.equals(WHITE) || adjusted.equals(BLACK), adjusted.toString());
    }

    @Test
    void hslRoundTrips() {
        for (RGB c : new RGB[] { new RGB(0xE0, 0x6C, 0x75), new RGB(0x61, 0xAF, 0xEF), new RGB(10, 200, 30), WHITE, BLACK }) {
            float[] hsl = ColorContrast.toHsl(c);
            assertEquals(c, ColorContrast.fromHsl(hsl[0], hsl[1], hsl[2]));
        }
    }

    private static void assertHueKept(RGB original, RGB adjusted) {
        float h1 = ColorContrast.toHsl(original)[0];
        float h2 = ColorContrast.toHsl(adjusted)[0];
        float diff = Math.abs(h1 - h2);
        assertTrue(Math.min(diff, 360 - diff) < 4, "hue " + h1 + " -> " + h2);
    }
}
