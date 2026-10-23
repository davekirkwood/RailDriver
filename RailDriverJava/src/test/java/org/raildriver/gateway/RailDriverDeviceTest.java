package org.raildriver.gateway;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Fake HID adapter: these checks establish software behaviour only. */
class RailDriverDeviceTest {
    static class FakeIo implements RailDriverDevice.Io {
        int result, attempts, presenceChecks; boolean attached = true, closed; byte id;
        public int read(byte[] bytes, int timeout) { assertEquals(100, timeout); bytes[1]=38; return result; }
        public boolean attached() { presenceChecks++; return attached; }
        public int write(byte[] bytes, byte reportId) { attempts++; id=reportId; return -1; }
        public String error() { return "synthetic USB failure"; }
        public void close() { closed=true; }
    }
    @Test void blockedConsoleDoesNotBlockReportsOrCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean daemon = new AtomicBoolean();
        BoundedDiagnostics diagnostics = new BoundedDiagnostics(message -> {
            daemon.set(Thread.currentThread().isDaemon());
            entered.countDown();
            // Simulate a console write that remains blocked despite interruption.
            boolean finished = false;
            while (!finished) {
                try { release.await(); finished = true; }
                catch (InterruptedException ignored) { }
            }
        });
        FakeIo io = new FakeIo() {
            int sequence;
            @Override public int read(byte[] bytes, int timeout) {
                super.read(bytes, timeout);
                bytes[1] = (byte) (38 + sequence++ % 100);
                return 14;
            }
        };
        try (RailDriverDevice device = new RailDriverDevice(io, diagnostics)) {
            assertNotNull(device.read());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(daemon.get());
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                // More changed reports than the one-slot queue can retain.
                for (int i = 0; i < 100; i++) assertNotNull(device.read());
                device.showNumber(9); // Failed LED diagnostic uses the same nonblocking producer.
                device.close();
            });
            assertTrue(io.closed);
            assertEquals(1, release.getCount(), "Console must still be blocked at shutdown");
        } finally {
            diagnostics.close();
            release.countDown();
        }
    }
    @Test void timeoutShortAndValidInput() throws Exception {
        FakeIo io = new FakeIo();
        try (RailDriverDevice device = new RailDriverDevice(io)) {
            assertNull(device.read());
            assertEquals(1, io.presenceChecks, "First read must check device presence");
            io.result=7;
            assertTrue(assertThrows(IOException.class, device::read).getMessage().contains("Invalid HID input length=7"));
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
