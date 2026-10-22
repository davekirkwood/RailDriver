package org.raildriver.gateway;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Fake HID adapter: these checks establish software behaviour only. */
class RailDriverDeviceTest {
    static class FakeIo implements RailDriverDevice.Io {
        int result, attempts; boolean attached = true, closed; byte id;
        public int read(byte[] bytes, int timeout) { assertEquals(100, timeout); bytes[1]=38; return result; }
        public boolean attached() { return attached; }
        public int write(byte[] bytes, byte reportId) { attempts++; id=reportId; return -1; }
        public String error() { return "synthetic USB failure"; }
        public void close() { closed=true; }
    }
    @Test void timeoutShortAndValidInput() throws Exception {
        FakeIo io = new FakeIo();
        try (RailDriverDevice device = new RailDriverDevice(io)) {
            assertNull(device.read());
            io.result=7; assertNull(device.read());
            io.result=14;
            byte[] normalized=device.read();
            assertEquals(15, normalized.length); assertEquals(38, normalized[2]);
            assertEquals(10, InputMapper.map(normalized)[5]);
        }
        assertTrue(io.closed);
    }
    @Test void negativeReadAndDetachFailClearly() {
        FakeIo io = new FakeIo(); io.result=-1;
        try (RailDriverDevice device = new RailDriverDevice(io)) {
            assertTrue(assertThrows(IOException.class, device::read).getMessage().contains("read failed"));
        }
        io.result=0; io.attached=false;
        try (RailDriverDevice device = new RailDriverDevice(io)) {
            assertTrue(assertThrows(IOException.class, device::read).getMessage().contains("detached"));
        }
    }
    @Test void ledPayloadsAndBoundedAttempt() {
        assertArrayEquals(new byte[] {124,92,62,0,0,0,0}, RailDriverDevice.logo());
        assertArrayEquals(new byte[] {63,0,0,0,0,0,0}, RailDriverDevice.number(0));
        assertArrayEquals(new byte[] {103,0,0,0,0,0,0}, RailDriverDevice.number(9));
        assertArrayEquals(new byte[] {91,6,0,0,0,0,0}, RailDriverDevice.number(12));
        assertArrayEquals(new byte[] {7,63,6,0,0,0,0}, RailDriverDevice.number(107));
        // Hundreds outside 0..9 blank, exactly as VB's digit table default.
        assertArrayEquals(new byte[] {109,109,0,0,0,0,0}, RailDriverDevice.number(25755));
        FakeIo io = new FakeIo();
        try (RailDriverDevice device = new RailDriverDevice(io)) { device.showLogo(); device.showNumber(9); }
        assertEquals(2,io.attempts); assertEquals(134,Byte.toUnsignedInt(io.id));
        assertThrows(IllegalArgumentException.class, () -> RailDriverDevice.normalize(new byte[13]));
    }
}
