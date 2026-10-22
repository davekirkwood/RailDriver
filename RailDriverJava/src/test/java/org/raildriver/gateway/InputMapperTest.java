package org.raildriver.gateway;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** All reports below are synthetic, not device captures. */
class InputMapperTest {
    static byte[] report(int direction, int throttle, int brake, int independent, int switchValue, int key) {
        return new byte[] {0, (byte) direction, (byte) throttle, (byte) brake, (byte) independent,
                0, (byte) switchValue, (byte) key};
    }
    @Test void completeVectors() {
        assertArrayEquals(new byte[] {0,10,0,10,0,10,0,0,0,0,0,0,0,0,0,0,8,0,0,72},
                InputMapper.map(report(0,38,0,0,110,150)));
        assertArrayEquals(new byte[] {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,5,0,(byte)128,8},
                InputMapper.map(report(255,227,255,255,151,151)));
        assertArrayEquals(new byte[] {0,7,0,5,0,5,0,0,0,0,0,0,0,0,0,0,4,0,64,8},
                InputMapper.map(report(127,132,70,128,111,0)));
    }
    @Test void nearestEvenAndUnsigned() {
        // 64 / 25.6 = 2.5 -> 2; 192 / 25.6 = 7.5 -> 8.
        assertEquals(8, InputMapper.map(report(0,38,64,192,0,0))[1]);
        assertEquals(2, InputMapper.map(report(0,38,64,192,0,0))[3]);
        assertEquals(10, InputMapper.map(report(0,47,0,0,0,0))[5]);
        assertEquals(9, InputMapper.map(report(0,48,0,0,0,0))[5]);
        assertEquals(12, InputMapper.map(report(0,0,0,0,0,0))[5]);
        assertThrows(IllegalArgumentException.class, () -> InputMapper.map(report(0,255,0,0,0,0)));
    }
    @Test void boundaries() {
        for (int value : new int[] {109,110,111,149,150,151}) {
            int expected = value > 150 ? 128 : value > 110 && value < 150 ? 64 : 0;
            assertEquals(expected, Byte.toUnsignedInt(InputMapper.map(report(126,38,69,0,value,150))[18]));
        }
        assertEquals(8, InputMapper.map(report(126,38,70,0,0,150))[16]);
        assertEquals(5, InputMapper.map(report(127,38,70,0,0,151))[16]);
        assertEquals(72, InputMapper.map(report(0,38,69,0,0,0))[19]);
        assertEquals(8, InputMapper.map(report(0,38,70,0,0,0))[19]);
        assertThrows(IllegalArgumentException.class, () -> InputMapper.map(new byte[7]));
        assertThrows(IllegalArgumentException.class, () -> InputMapper.map(null));
    }
}
