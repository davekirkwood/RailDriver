package org.raildriver.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic fixtures only. Uses real UDP transport; never claims physical mapping proof. */
class GatewayWorkbenchTest {
    @TempDir Path directory;
    static byte[] raw() { return new byte[]{0,38,0,0,0,110,(byte)150,1,0,0,0,0,0,0}; }
    static class Io extends UdpGatewayTest.PumpIo {
        volatile boolean failWrite;
        volatile CountDownLatch closeEntered, releaseClose;
        final AtomicReference<String> writerThread = new AtomicReference<>();
        @Override public int write(byte[] payload, byte id) {
            writerThread.set(Thread.currentThread().getName());
            return failWrite ? -1 : super.write(payload, id);
        }
        @Override public void close() {
            if (closeEntered != null) {
                closeEntered.countDown();
                try { releaseClose.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            super.close();
        }
    }
    static GatewaySession session(Io io, int capacity) {
        return new GatewaySession(out -> new RailDriverDevice(io, new BoundedDiagnostics(out)), "SYNTHETIC fake HID fixture; no device", capacity);
    }
    static void await(BooleanSupplier predicate) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (!predicate.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(predicate.getAsBoolean(), "Asynchronous condition timed out");
    }
    static GatewayConfig config(DatagramSocket peer, DatagramSocket sink) throws Exception {
        return new GatewayConfig(UdpGatewayTest.freePort(), peer.getLocalPort(), sink.getLocalPort(), 5, true);
    }
    @Test void validatesAndPersistsAllSettingsWithoutPartialMalformedLoad() throws Exception {
        GatewayConfig defaults = GatewayConfig.defaults();
        assertEquals(new GatewayConfig(59982,59981,59983,10,true), defaults);
        for (int port : new int[]{-1,0,65536}) assertThrows(IllegalArgumentException.class, () -> new GatewayConfig(port,2,3,10,true));
        assertThrows(IllegalArgumentException.class, () -> new GatewayConfig(1,1,3,10,true));
        for (int delay : new int[]{0,1001}) assertThrows(IllegalArgumentException.class, () -> new GatewayConfig(1,2,3,delay,true));
        Path path = directory.resolve("settings.properties");
        GatewayConfig custom = new GatewayConfig(15001,15002,15003,25,false); custom.save(path);
        assertEquals(custom, GatewayConfig.load(path));
        Files.writeString(path, "commandPort=15001\npeerPort=nope\n");
        assertThrows(IOException.class, () -> GatewayConfig.load(path));
        assertEquals(25, custom.sendDelayMillis()); // immutable current value survives failed load
        Files.writeString(path, "commandPort=1\npeerPort=2\ndestinationPort=3\nsendDelayMillis=10\ndiagnostics=maybe\n");
        assertThrows(IOException.class, () -> GatewayConfig.load(path));
    }
    @Test void productionSessionCoherentSnapshotUdpLedRemoteStopAndRestart() throws Exception {
        Io io = new Io(); GatewaySession session = session(io, 3);
        try (DatagramSocket sink = new DatagramSocket(0, UdpGatewayTest.LOCAL);
             DatagramSocket peer = new DatagramSocket(0, UdpGatewayTest.LOCAL)) {
            GatewayConfig config = config(peer, sink);
            assertNull(session.view().latest()); assertEquals(GatewaySession.State.STOPPED, session.view().state());
            assertTrue(session.start(config)); assertFalse(session.start(config));
            try {
                await(() -> session.view().state() == GatewaySession.State.RUNNING);
                sink.setSoTimeout(80); assertThrows(SocketTimeoutException.class, () -> sink.receive(new DatagramPacket(new byte[100],100)));
                io.reports.add(raw());
                byte[] expected = {0,10,0,10,0,10,0,0,0,0,0,0,0,0,0,0,8,0,0,72};
                UdpGatewayTest.awaitFrame(sink, expected);
                GatewaySession.Sample snapshot = session.view().latest(); assertNotNull(snapshot);
                assertArrayEquals(raw(), snapshot.raw()); assertArrayEquals(expected, snapshot.packet());
                byte[] changed = snapshot.raw(); changed[0] = 99; assertEquals(0, snapshot.raw()[0]);
                byte[] secondRaw = {(byte)255,(byte)227,(byte)255,(byte)255,0,(byte)151,(byte)151,2,0,0,0,0,0,0};
                byte[] secondPacket = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,5,0,(byte)128,8};
                io.reports.add(secondRaw); UdpGatewayTest.awaitFrame(sink, secondPacket);
                assertArrayEquals(secondRaw, session.view().latest().raw());
                assertArrayEquals(secondPacket, session.view().latest().packet());
                await(() -> session.view().sent() > 0); assertTrue(session.view().rate() > 0);
                io.writes.clear(); assertTrue(session.led(107));
                UdpGatewayTest.LedWrite write = io.writes.poll(2, TimeUnit.SECONDS); assertNotNull(write);
                assertArrayEquals(new byte[]{7,63,6,0,0,0,0}, write.payload()); assertEquals(134, write.reportId() & 255);
                assertEquals("raildriver-hid-owner", io.writerThread.get());
                UdpGatewayTest.send(peer, config.commandPort(), (byte)81,(byte)0,(byte)0);
                assertTrue(session.awaitIdle(2000)); assertTrue(io.closed); assertNull(session.view().latest());
                try (DatagramSocket rebound = new DatagramSocket(config.commandPort(), UdpGatewayTest.LOCAL)) { assertTrue(rebound.isBound()); }
                assertFalse(session.led(12));
                io.closed = false; assertTrue(session.start(config));
                await(() -> session.view().state() == GatewaySession.State.RUNNING);
                assertNull(session.view().latest()); session.stop(); assertTrue(session.awaitIdle(2000)); assertTrue(io.closed);
            } finally { session.stop(); assertTrue(session.awaitIdle(2000)); }
        }
    }
    @Test void boundedCaptureExportAndFileFailure() throws Exception {
        Io io = new Io(); GatewaySession session = session(io, 3);
        assertThrows(ExecutionException.class, () -> session.export(directory.resolve("empty.csv")).get(2, TimeUnit.SECONDS));
        assertFalse(Files.exists(directory.resolve("empty.csv")));
        try (DatagramSocket sink = new DatagramSocket(0, UdpGatewayTest.LOCAL);
             DatagramSocket peer = new DatagramSocket(0, UdpGatewayTest.LOCAL)) {
            session.start(config(peer,sink));
            try {
                for (int i = 0; i < 7; i++) io.reports.add(raw());
                await(() -> session.view().evicted() == 4);
                assertEquals(3,session.view().captured());
                for (int i=0; i<250; i++) session.event("synthetic event " + i);
                assertEquals(200,session.view().events().size()); assertTrue(session.view().eventsEvicted() >= 50);
                Path csv = directory.resolve("capture.csv"); assertEquals(3,session.export(csv).get(2,TimeUnit.SECONDS));
                List<String> rows = Files.readAllLines(csv);
                assertEquals(3,rows.stream().filter(line -> !line.startsWith("#") && !line.startsWith("timestamp")).count());
                String text = Files.readString(csv);
                assertTrue(text.contains("SYNTHETIC fake HID")); assertTrue(text.contains("capture_evicted=4"));
                assertTrue(text.contains(GatewaySession.hex(raw()))); assertTrue(text.contains(GatewaySession.hex(InputMapper.map(RailDriverDevice.normalize(raw())))));
                assertThrows(ExecutionException.class, () -> session.export(directory.resolve("absent/failed.csv")).get(2,TimeUnit.SECONDS));
            } finally { session.stop(); assertTrue(session.awaitIdle(2000)); }
        }
    }
    @Test void stopsUdpBeforeSlowNativeCloseAndPreventsOverlappingRestart() throws Exception {
        Io io = new Io(); io.closeEntered = new CountDownLatch(1); io.releaseClose = new CountDownLatch(1);
        GatewaySession session = session(io, 3);
        try (DatagramSocket sink = new DatagramSocket(0,UdpGatewayTest.LOCAL); DatagramSocket peer = new DatagramSocket(0,UdpGatewayTest.LOCAL)) {
            GatewayConfig config = config(peer,sink); session.start(config); io.reports.add(raw());
            try {
                UdpGatewayTest.awaitFrame(sink,InputMapper.map(RailDriverDevice.normalize(raw())));
                SwingUtilities.invokeAndWait(session::stop);
                assertTrue(io.closeEntered.await(2,TimeUnit.SECONDS));
                assertEquals(GatewaySession.State.STOPPING,session.view().state()); assertFalse(session.start(config));
                try(DatagramSocket rebound = new DatagramSocket(config.commandPort(),UdpGatewayTest.LOCAL)) { assertTrue(rebound.isBound()); }
                sink.setSoTimeout(40);
                try { while(true) sink.receive(new DatagramPacket(new byte[100],100)); } catch(SocketTimeoutException expected) { }
                assertThrows(SocketTimeoutException.class, () -> sink.receive(new DatagramPacket(new byte[100],100)));
            } finally { io.releaseClose.countDown(); session.stop(); assertTrue(session.awaitIdle(2000)); }
        }
    }
    @Test void stopHaltsUdpEvenWhenNativeReadOrLedIsBlockedAndEdtRemainsResponsive() throws Exception {
        for (boolean blockedLed : new boolean[]{false,true}) {
            CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicInteger readCalls = new AtomicInteger(), writeCalls = new AtomicInteger();
            Io io = new Io() {
                private void blockNative() {
                    blocked.countDown();
                    try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
                }
                @Override public int read(byte[] buffer,int timeout) {
                    if (!blockedLed && readCalls.incrementAndGet() > 1) blockNative();
                    return super.read(buffer,timeout);
                }
                @Override public int write(byte[] payload,byte id) {
                    if (blockedLed && writeCalls.incrementAndGet() > 1) blockNative();
                    return super.write(payload,id);
                }
            };
            GatewaySession session = session(io,3);
            try(DatagramSocket sink = new DatagramSocket(0,UdpGatewayTest.LOCAL); DatagramSocket peer = new DatagramSocket(0,UdpGatewayTest.LOCAL)) {
                GatewayConfig config = config(peer,sink); io.reports.add(raw()); session.start(config);
                try {
                    UdpGatewayTest.awaitFrame(sink,InputMapper.map(RailDriverDevice.normalize(raw())));
                    if (blockedLed) assertTrue(session.led(107));
                    assertTrue(blocked.await(2,TimeUnit.SECONDS));
                    SwingUtilities.invokeAndWait(session::stop);
                    AtomicBoolean responsive = new AtomicBoolean();
                    SwingUtilities.invokeAndWait(() -> responsive.set(true)); assertTrue(responsive.get());
                    assertEquals(GatewaySession.State.STOPPING,session.view().state()); assertFalse(session.start(config));
                    // The owner is still blocked; nevertheless Stop releases transport independently.
                    await(() -> {
                        try(DatagramSocket rebound = new DatagramSocket(config.commandPort(),UdpGatewayTest.LOCAL)) { return rebound.isBound(); }
                        catch(SocketException waiting) { return false; }
                    });
                    sink.setSoTimeout(40);
                    try { while(true) sink.receive(new DatagramPacket(new byte[100],100)); } catch(SocketTimeoutException expected) { }
                    assertThrows(SocketTimeoutException.class, () -> sink.receive(new DatagramPacket(new byte[100],100)));
                    assertFalse(io.closed);
                } finally { release.countDown(); session.stop(); assertTrue(session.awaitIdle(2000)); }
                assertTrue(io.closed);
            }
        }
    }
    @Test void missingDevicePortConflictAndInvalidReportAreRecoverableErrors() throws Exception {
        GatewaySession missing = new GatewaySession(out -> { throw new IOException("Synthetic missing device — connect one controller"); }, "synthetic",3);
        missing.start(GatewayConfig.defaults()); assertTrue(missing.awaitIdle(2000));
        assertEquals(GatewaySession.State.ERROR,missing.view().state()); assertTrue(missing.view().message().contains("connect one controller"));
        Io io = new Io(); GatewaySession session = session(io,3);
        try (DatagramSocket sink = new DatagramSocket(0,UdpGatewayTest.LOCAL); DatagramSocket peer = new DatagramSocket(0,UdpGatewayTest.LOCAL);
             DatagramSocket occupied = new DatagramSocket(0,UdpGatewayTest.LOCAL)) {
            session.start(new GatewayConfig(occupied.getLocalPort(),peer.getLocalPort(),sink.getLocalPort(),10,true));
            assertTrue(session.awaitIdle(2000)); assertEquals(GatewaySession.State.ERROR,session.view().state()); assertTrue(io.closed);
            GatewayConfig config = config(peer,sink); session.start(config);
            try {
                io.reports.add(raw()); UdpGatewayTest.awaitFrame(sink,InputMapper.map(RailDriverDevice.normalize(raw())));
                io.reports.add(new byte[7]); assertTrue(session.awaitIdle(2000));
                assertEquals(GatewaySession.State.ERROR,session.view().state()); assertNull(session.view().latest());
                assertTrue(session.view().message().contains("Invalid HID input length=7"));
            } finally { session.stop(); assertTrue(session.awaitIdle(2000)); }
        }
    }
    @Test void stopDuringStartupClosesOpenedDeviceWithoutBindingOrStartingAnotherOwner() throws Exception {
        Io io = new Io(); CountDownLatch opening = new CountDownLatch(1), finishOpen = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        GatewaySession session = new GatewaySession(out -> {
            opens.incrementAndGet(); opening.countDown();
            if (!finishOpen.await(2,TimeUnit.SECONDS)) throw new IOException("Synthetic open timeout");
            return new RailDriverDevice(io, new BoundedDiagnostics(out));
        }, "SYNTHETIC delayed open", 3);
        try(DatagramSocket sink = new DatagramSocket(0,UdpGatewayTest.LOCAL); DatagramSocket peer = new DatagramSocket(0,UdpGatewayTest.LOCAL)) {
            GatewayConfig config = config(peer,sink); session.start(config);
            try {
                assertTrue(opening.await(2,TimeUnit.SECONDS));
                SwingUtilities.invokeAndWait(session::stop);
                assertEquals(GatewaySession.State.STOPPING,session.view().state()); assertFalse(session.start(config));
                assertEquals(1, opens.get());
                try(DatagramSocket available = new DatagramSocket(config.commandPort(),UdpGatewayTest.LOCAL)) { assertTrue(available.isBound()); }
            } finally { finishOpen.countDown(); session.stop(); assertTrue(session.awaitIdle(2000)); }
            assertTrue(io.closed); assertTrue(io.writes.isEmpty()); assertNull(session.view().latest());
            assertEquals(GatewaySession.State.STOPPED,session.view().state());
        }
    }
    @Test void remoteStopAndReadFailureClearLiveStateBeforeSlowCleanup() throws Exception {
        for (boolean readFailure : new boolean[]{false,true}) {
            Io io = new Io(); io.closeEntered = new CountDownLatch(1); io.releaseClose = new CountDownLatch(1);
            GatewaySession session = session(io,3);
            try(DatagramSocket sink = new DatagramSocket(0,UdpGatewayTest.LOCAL); DatagramSocket peer = new DatagramSocket(0,UdpGatewayTest.LOCAL)) {
                GatewayConfig config = config(peer,sink); session.start(config); io.reports.add(raw());
                try {
                    UdpGatewayTest.awaitFrame(sink,InputMapper.map(RailDriverDevice.normalize(raw())));
                    if(readFailure) io.reports.add(new byte[7]);
                    else UdpGatewayTest.send(peer,config.commandPort(),(byte)81,(byte)0,(byte)0);
                    assertTrue(io.closeEntered.await(2,TimeUnit.SECONDS));
                    GatewaySession.View stopping = session.view();
                    assertEquals(GatewaySession.State.STOPPING,stopping.state()); assertNull(stopping.latest());
                    assertFalse(session.led(107)); assertFalse(session.start(config));
                    if(readFailure) assertTrue(stopping.message().contains("Invalid HID input length=7"));
                } finally { io.releaseClose.countDown(); session.stop(); assertTrue(session.awaitIdle(2000)); }
                assertEquals(readFailure ? GatewaySession.State.ERROR : GatewaySession.State.STOPPED,session.view().state());
            }
        }
    }
    @Test void retainedRunCountersSurviveRestartAndExportEvictionAndMultilineEvents() throws Exception {
        Io io = new Io(); AtomicReference<BoundedDiagnostics> diagnostic = new AtomicReference<>();
        AtomicReference<CountDownLatch> blockedSink = new AtomicReference<>();
        GatewaySession session = new GatewaySession(out -> {
            CountDownLatch entered = new CountDownLatch(1); blockedSink.set(entered);
            BoundedDiagnostics log = new BoundedDiagnostics(text -> {
                entered.countDown();
                try { Thread.sleep(5000); } catch(InterruptedException expected) { Thread.currentThread().interrupt(); }
            });
            diagnostic.set(log); return new RailDriverDevice(io,log);
        },"SYNTHETIC run counters",10,1);
        try(DatagramSocket sink = new DatagramSocket(0,UdpGatewayTest.LOCAL); DatagramSocket peer = new DatagramSocket(0,UdpGatewayTest.LOCAL)) {
            GatewayConfig config = config(peer,sink); long previousDrops = 0;
            for(int run=1;run<=3;run++) {
                session.start(config);
                assertTrue(session.view().diagnosticsDropped() >= previousDrops);
                try {
                    io.reports.add(raw()); UdpGatewayTest.awaitFrame(sink,InputMapper.map(RailDriverDevice.normalize(raw())));
                    assertTrue(blockedSink.get().await(2,TimeUnit.SECONDS));
                    diagnostic.get().report("one pending"); diagnostic.get().report("one dropped");
                } finally { session.stop(); assertTrue(session.awaitIdle(2000)); }
                assertTrue(session.view().diagnosticsDropped() > previousDrops); previousDrops = session.view().diagnosticsDropped();
            }
            GatewaySession.View view = session.view();
            assertEquals(1,view.runs().size()); assertEquals(3,view.runs().get(0).run());
            assertTrue(view.runs().get(0).successfulSends() > 0); assertEquals(2,view.runSummariesEvicted());
            assertTrue(view.evictedRunSends() > 0); assertTrue(view.evictedRunDrops() > 0);
            session.event("synthetic line one\nINJECTED LINE\r\nline three");
            Path path = directory.resolve("run-counter.csv"); session.export(path).get(2,TimeUnit.SECONDS);
            String text = Files.readString(path);
            assertTrue(text.contains("# run_summary,3," + view.runs().get(0).successfulSends()));
            assertTrue(text.contains("# run_summaries_evicted=2"));
            assertTrue(text.contains("# event INJECTED LINE\n# event line three"));
            assertEquals(3,Files.readAllLines(path).stream().filter(line -> !line.startsWith("#") && !line.startsWith("timestamp")).count());
        }
    }
    @Test void ledFailureIsSurfacedWithoutClaimingVisualSuccess() throws Exception {
        Io io = new Io(); GatewaySession session = session(io,3);
        try(DatagramSocket sink = new DatagramSocket(0,UdpGatewayTest.LOCAL); DatagramSocket peer = new DatagramSocket(0,UdpGatewayTest.LOCAL)) {
            session.start(config(peer,sink));
            try {
                await(() -> session.view().state() == GatewaySession.State.RUNNING);
                io.failWrite = true; assertTrue(session.led(999));
                await(() -> session.view().events().stream().anyMatch(line -> line.contains("LED 999 write FAILED")));
                assertThrows(IllegalArgumentException.class, () -> session.led(1000));
            } finally { session.stop(); assertTrue(session.awaitIdle(2000)); }
        }
    }
    @Test void diagnosticQueueReportsDroppedMessagesWithoutBlockingProducer() throws Exception {
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
        BoundedDiagnostics diagnostics = new BoundedDiagnostics(text -> {
            writing.countDown();
            try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            diagnostics.report("blocked synthetic sink"); assertTrue(writing.await(2,TimeUnit.SECONDS));
            diagnostics.report("one pending"); diagnostics.report("dropped 1"); diagnostics.report("dropped 2");
            assertEquals(2, diagnostics.dropped());
            diagnostics.close(); assertEquals(3, diagnostics.dropped());
        } finally { release.countDown(); diagnostics.close(); }
    }
    @Test void edtPaintingUsesObservedBitsAndUnavailableStateWithoutUsb() throws Exception {
        AtomicReference<String> detail = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            DevicePanel panel = new DevicePanel(); panel.setSize(900,450); panel.onSelection(detail::set);
            BufferedImage image = new BufferedImage(900,450,BufferedImage.TYPE_INT_ARGB); Graphics2D g = image.createGraphics();
            try {
                panel.paint(g);
                MouseEvent key = new MouseEvent(panel,MouseEvent.MOUSE_MOVED,0,0,50,340,0,false);
                assertTrue(panel.getToolTipText(key).contains("unavailable"));
                GatewaySession.Sample sample = new GatewaySession.Sample(java.time.Instant.now(),1,1,"SYNTHETIC",raw(),InputMapper.map(RailDriverDevice.normalize(raw())));
                panel.setSample(sample); panel.paint(g); assertTrue(panel.getToolTipText(key).contains(": 1"));
                byte[] next = raw(); next[7] = 0;
                panel.setSample(new GatewaySession.Sample(java.time.Instant.now(),2,1,"SYNTHETIC",next,InputMapper.map(RailDriverDevice.normalize(next))));
                panel.paint(g); assertTrue(panel.getToolTipText(key).contains(": 0"));
                panel.setSize(450,225); panel.paint(g); panel.setSample(null); panel.paint(g);
            } finally { g.dispose(); }
        });
    }
}
