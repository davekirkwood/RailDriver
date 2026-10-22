package org.raildriver.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.net.DatagramSocket;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the exact panel used by GatewayWindow, entirely without a native window/USB/OS input. */
class GatewayContentTest {
    @TempDir Path directory;
    static <T> T edt(Callable<T> task) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { result.set(task.call()); } catch(RuntimeException | Error e) { throw e; } catch(Exception e) { throw new RuntimeException(e); } });
        return result.get();
    }
    static void awaitEdt(Callable<Boolean> predicate) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while(!edt(predicate) && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(edt(predicate));
    }
    static GatewayContent content(GatewaySession session, AtomicInteger closed, List<String> notices) throws Exception {
        return edt(() -> new GatewayContent(session,closed::incrementAndGet,(title,message,kind) -> notices.add(title + ": " + message)));
    }
    static void close(GatewayContent content) throws Exception {
        edt(() -> { content.requestClose(); return null; });
        assertTrue(content.session.awaitIdle(2000));
        edt(() -> { content.refresh(); return null; });
    }
    @Test void realContentBindsChangedReportsDetailsLocksUnavailableAndMalformedLoad() throws Exception {
        GatewayWorkbenchTest.Io io = new GatewayWorkbenchTest.Io(); GatewaySession session = GatewayWorkbenchTest.session(io,10);
        List<String> notices = new CopyOnWriteArrayList<>(); AtomicInteger closed = new AtomicInteger();
        GatewayContent content = content(session,closed,notices);
        try(DatagramSocket sink = new DatagramSocket(0,UdpGatewayTest.LOCAL); DatagramSocket peer = new DatagramSocket(0,UdpGatewayTest.LOCAL)) {
            GatewayConfig config = GatewayWorkbenchTest.config(peer,sink);
            edt(() -> {
                content.setConfig(config); assertTrue(content.start.isEnabled()); assertFalse(content.led.isEnabled());
                content.start.doClick(); assertFalse(content.start.isEnabled()); assertFalse(content.command.isEnabled()); assertFalse(content.load.isEnabled()); return null;
            });
            io.reports.add(GatewayWorkbenchTest.raw());
            byte[] first = {0,10,0,10,0,10,0,0,0,0,0,0,0,0,0,0,8,0,0,72};
            UdpGatewayTest.awaitFrame(sink,first);
            edt(() -> {
                content.refresh(); assertTrue(content.led.isEnabled()); assertFalse(content.command.isEnabled());
                assertTrue(content.packets.getText().contains(GatewaySession.hex(first)));
                assertTrue(content.status.getText().contains("Last report:")); assertTrue(content.status.getText().contains("Timeouts may mean unchanged input"));
                content.device.setSize(900,450); BufferedImage painted = new BufferedImage(900,450,BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = painted.createGraphics(); try { content.device.paint(graphics); } finally { graphics.dispose(); }
                content.device.dispatchEvent(new MouseEvent(content.device,MouseEvent.MOUSE_PRESSED,0,0,250,150,1,false));
                assertTrue(content.detail.getText().contains("raw[1]")); assertTrue(content.detail.getText().contains("value 38")); return null;
            });
            byte[] secondRaw = {(byte)255,(byte)227,(byte)255,(byte)255,0,(byte)151,(byte)151,2,0,0,0,0,0,0};
            byte[] second = {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,5,0,(byte)128,8};
            io.reports.add(secondRaw); UdpGatewayTest.awaitFrame(sink,second);
            edt(() -> {
                content.refresh(); assertTrue(content.detail.getText().contains("value 227"));
                assertTrue(content.packets.getText().contains("raw[01] = 227")); assertTrue(content.packets.getText().contains(GatewaySession.hex(second)));
                content.stop.doClick(); assertFalse(content.led.isEnabled()); assertTrue(content.detail.getText().contains("unavailable")); return null;
            });
            assertTrue(session.awaitIdle(2000));
            edt(() -> {
                content.refresh(); assertTrue(content.start.isEnabled()); assertTrue(content.command.isEnabled());
                assertTrue(content.packets.getText().contains("No current report.")); assertFalse(content.led.isEnabled()); return null;
            });
            Path malformed = directory.resolve("bad.properties"); Files.writeString(malformed,"commandPort=not-a-port\n");
            GatewayConfig before = edt(content::readConfig);
            edt(() -> { content.loadFrom(malformed); assertTrue(content.fileBusy); assertFalse(content.start.isEnabled()); return null; });
            awaitEdt(() -> !content.fileBusy);
            assertEquals(before,edt(content::readConfig)); assertTrue(notices.stream().anyMatch(line -> line.contains("File operation failed")));
            Path valid = directory.resolve("good.properties"); GatewayConfig restored = new GatewayConfig(15001,15002,15003,30,false); restored.save(valid);
            edt(() -> { content.loadFrom(valid); return null; }); awaitEdt(() -> !content.fileBusy); assertEquals(restored,edt(content::readConfig));
        } finally { close(content); }
        assertEquals(1,closed.get());
    }
    @Test void typedLedCommitsEditorAndInvalidTextIsVisibleWithoutQueuingOldValue() throws Exception {
        GatewayWorkbenchTest.Io io = new GatewayWorkbenchTest.Io(); GatewaySession session = GatewayWorkbenchTest.session(io,3);
        List<String> notices = new CopyOnWriteArrayList<>(); GatewayContent content = content(session,new AtomicInteger(),notices);
        try(DatagramSocket sink = new DatagramSocket(0,UdpGatewayTest.LOCAL); DatagramSocket peer = new DatagramSocket(0,UdpGatewayTest.LOCAL)) {
            session.start(GatewayWorkbenchTest.config(peer,sink)); GatewayWorkbenchTest.await(() -> session.view().state() == GatewaySession.State.RUNNING);
            assertNotNull(io.writes.poll(2,TimeUnit.SECONDS)); // startup logo
            edt(() -> {
                content.refresh(); JFormattedTextField editor = ((JSpinner.DefaultEditor)content.number.getEditor()).getTextField();
                editor.setText("321"); assertEquals(107,content.number.getValue()); content.led.doClick(); assertEquals(321,content.number.getValue()); return null;
            });
            UdpGatewayTest.LedWrite write = io.writes.poll(2,TimeUnit.SECONDS); assertNotNull(write); assertArrayEquals(RailDriverDevice.number(321),write.payload());
            for (String invalid : new String[]{"bad", "12x", "1.5", "1000"}) edt(() -> {
                ((JSpinner.DefaultEditor)content.number.getEditor()).getTextField().setText(invalid);
                content.led.doClick(); return null;
            });
            assertEquals(4,notices.stream().filter(line -> line.contains("Invalid LED number")).count());
            assertNull(io.writes.poll(150,TimeUnit.MILLISECONDS));
        } finally { close(content); }
    }
    @Test void closingWaitsForControlledSaveAndExportAndSuppressesCompletionDialogs() throws Exception {
        for(boolean fail : new boolean[]{false,true}) {
            List<String> notices = new CopyOnWriteArrayList<>(); AtomicInteger closed = new AtomicInteger();
            GatewayContent content = content(new GatewaySession(),closed,notices);
            CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1);
            CompletableFuture<Integer> exporting = new CompletableFuture<>(); Path target = directory.resolve("pending-" + fail + ".txt");
            try {
                edt(() -> {
                    content.fileTask(() -> {
                        writing.countDown(); if(!release.await(2,TimeUnit.SECONDS)) throw new java.io.IOException("Synthetic writer timeout");
                        if(fail) throw new java.io.IOException("Synthetic writer failure"); Files.writeString(target,"completed"); return target;
                    }, ignored -> notices.add("save complete"));
                    content.trackExport(exporting,directory.resolve("export.csv")); content.requestClose();
                    assertTrue(content.closing); assertFalse(content.closed); assertEquals(2,content.pendingFiles);
                    assertFalse(content.start.isEnabled()); assertFalse(content.export.isEnabled()); return null;
                });
                assertTrue(writing.await(2,TimeUnit.SECONDS)); assertEquals(0,closed.get());
                release.countDown(); awaitEdt(() -> !content.fileBusy);
                assertEquals(0,closed.get()); assertTrue(notices.isEmpty());
                if(fail) exporting.completeExceptionally(new java.io.IOException("Synthetic export failure")); else exporting.complete(1);
                awaitEdt(() -> content.closed); assertEquals(1,closed.get()); assertTrue(notices.isEmpty());
                assertEquals(!fail,Files.exists(target));
            } finally { release.countDown(); exporting.complete(0); close(content); }
        }
    }
    @Test void unchangedSnapshotRequestsRepaintAndUnavailableKeysDifferFromObservedZero() throws Exception {
        edt(() -> {
            DevicePanel panel = new DevicePanel(); panel.setSize(900,450);
            AtomicInteger repaints = new AtomicInteger(); RepaintManager old = RepaintManager.currentManager(panel);
            RepaintManager tracking = new RepaintManager() {
                @Override public void addDirtyRegion(JComponent component,int x,int y,int width,int height) {
                    if(component == panel) repaints.incrementAndGet(); super.addDirtyRegion(component,x,y,width,height);
                }
            };
            try {
                RepaintManager.setCurrentManager(tracking);
                BufferedImage unavailable = new BufferedImage(900,450,BufferedImage.TYPE_INT_RGB), observed = new BufferedImage(900,450,BufferedImage.TYPE_INT_RGB);
                Graphics2D first = unavailable.createGraphics(); try { panel.paint(first); } finally { first.dispose(); }
                byte[] raw = GatewayWorkbenchTest.raw(); raw[7] = 0;
                GatewaySession.Sample sample = new GatewaySession.Sample(java.time.Instant.now(),System.nanoTime(),1,"SYNTHETIC",raw,InputMapper.map(RailDriverDevice.normalize(raw)));
                panel.setSample(sample); int before = repaints.get(); panel.setSample(sample); assertTrue(repaints.get() > before);
                Graphics2D second = observed.createGraphics(); try { panel.paint(second); } finally { second.dispose(); }
                assertNotEquals(unavailable.getRGB(50,335),observed.getRGB(50,335));
                assertTrue(panel.getToolTipText(new MouseEvent(panel,MouseEvent.MOUSE_MOVED,0,0,50,340,0,false)).contains(": 0"));
            } finally { RepaintManager.setCurrentManager(old); }
            return null;
        });
    }
}
