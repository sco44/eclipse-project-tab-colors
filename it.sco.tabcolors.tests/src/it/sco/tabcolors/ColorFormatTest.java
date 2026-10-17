package it.sco.tabcolors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.eclipse.swt.graphics.RGB;
import org.junit.jupiter.api.Test;

class ColorFormatTest {

    @Test
    void parsesUpperAndLowerCaseHex() {
        assertEquals(new RGB(0xE0, 0x6C, 0x75), ProjectColorStore.parse("#E06C75"));
        assertEquals(new RGB(0xE0, 0x6C, 0x75), ProjectColorStore.parse("#e06c75"));
        assertEquals(new RGB(0, 0, 0), ProjectColorStore.parse("#000000"));
        assertEquals(new RGB(255, 255, 255), ProjectColorStore.parse("#FFFFFF"));
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertEquals(new RGB(0x11, 0x22, 0x33), ProjectColorStore.parse("  #112233 \t"));
    }

    @Test
    void rejectsInvalidValues() {
        assertNull(ProjectColorStore.parse(null));
        assertNull(ProjectColorStore.parse(""));
        assertNull(ProjectColorStore.parse("E06C75"));
        assertNull(ProjectColorStore.parse("#E06C7"));
        assertNull(ProjectColorStore.parse("#E06C755"));
        assertNull(ProjectColorStore.parse("#GG6C75"));
        assertNull(ProjectColorStore.parse("#E06C75FF"));
        assertNull(ProjectColorStore.parse("rgb(1,2,3)"));
    }

    @Test
    void formatsAsUpperCaseHex() {
        assertEquals("#E06C75", ProjectColorStore.toHex(new RGB(0xE0, 0x6C, 0x75)));
        assertEquals("#000A0F", ProjectColorStore.toHex(new RGB(0, 10, 15)));
    }

    @Test
    void roundTrips() {
        RGB rgb = new RGB(12, 200, 99);
        assertEquals(rgb, ProjectColorStore.parse(ProjectColorStore.toHex(rgb)));
    }

    /**
     * Golden values: the hashed color must be identical on every machine and
     * across releases. Computed once, cross-checked with java.awt.Color.HSBtoRGB.
     */
    @Test
    void hashedColorIsStable() {
        assertEquals(new RGB(0xD9, 0x5A, 0x4C), ProjectColorStore.hashed("order-service"));   // hue 6
        assertEquals(new RGB(0xD6, 0x4C, 0xD9), ProjectColorStore.hashed("billing-service")); // hue 299
        // "gateway".hashCode() is negative: exercises the sign mask
        assertEquals(new RGB(0xD9, 0x7B, 0x4C), ProjectColorStore.hashed("gateway"));         // hue 20
    }

    @Test
    void hashedColorIsDeterministic() {
        assertEquals(ProjectColorStore.hashed("x"), ProjectColorStore.hashed(new String("x")));
    }
}
