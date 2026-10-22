package org.raildriver.gateway;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in software renderer. Paints the real hidden Swing content, never the desktop/other apps. */
public final class WorkbenchRenderHarness {
    private WorkbenchRenderHarness() { }
    private static int freePort() throws Exception { try(DatagramSocket socket = new DatagramSocket(0,InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
    private static final class SyntheticIo implements RailDriverDevice.Io {
        final BlockingQueue<byte[]> reports = new ArrayBlockingQueue<>(4);
        public int read(byte[] buffer, int timeout) {
            try {
                byte[] report = reports.poll(timeout,TimeUnit.MILLISECONDS);
                if (report == null) return 0;
                System.arraycopy(report,0,buffer,0,report.length); return report.length;
            } catch(InterruptedException e) { Thread.currentThread().interrupt(); return -1; }
        }
        public boolean attached() { return true; }
        public int write(byte[] payload,byte reportId) { return payload.length + 1; }
        public String error() { return "SYNTHETIC renderer fixture"; }
        public void close() { }
    }
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Run this opt-in renderer in a desktop Java session; it creates hidden Swing windows, with no OS automation.");
        Path output = Path.of(args.length == 0 ? ".impeccable/review" : args[0]); Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch(Exception e) { throw new IllegalStateException(e); }
        });
        renderSet(new GatewaySession(), output, "stopped");
        SyntheticIo io = new SyntheticIo();
        GatewaySession session = new GatewaySession(out -> new RailDriverDevice(io,new BoundedDiagnostics(out)),
                "SYNTHETIC software render fixture — no USB/hardware evidence",5000);
        try(DatagramSocket sink = new DatagramSocket(0,InetAddress.getByName("127.0.0.1")); DatagramSocket peer = new DatagramSocket(0,InetAddress.getByName("127.0.0.1"))) {
            io.reports.add(new byte[]{(byte)180,(byte)132,95,(byte)200,123,(byte)140,(byte)170,5,18,(byte)129,3,0,0,0});
            session.start(new GatewayConfig(freePort(),peer.getLocalPort(),sink.getLocalPort(),10,true));
            long deadline = System.nanoTime() + 2_000_000_000L;
            while(session.view().latest() == null && System.nanoTime() < deadline) Thread.sleep(5);
            if(session.view().latest() == null) throw new IllegalStateException("Synthetic report not observed: " + session.view().message());
            renderSet(session,output,"synthetic-live");
        } finally { session.stop(); if(!session.awaitIdle(2000)) throw new IllegalStateException("Synthetic renderer cleanup timed out"); }
        Files.writeString(output.resolve("render-provenance.txt"), "Software paints of actual GatewayWindow content at 1100x760 and 740x600 pixels.\n"
                + "Stopped and explicitly SYNTHETIC live fixtures; Device and Settings tabs.\nNo OS UI input, desktop screenshot, physical device or simulator evidence.\n"
                + "Invocation: java -cp RailDriverJava/target/test-classes:RailDriverJava/target/raildriver-gateway.jar org.raildriver.gateway.WorkbenchRenderHarness .impeccable/review\n");
        System.out.println("Software renders: " + output.toAbsolutePath());
    }
    private static void renderSet(GatewaySession session, Path output, String state) throws Exception {
        AtomicReference<GatewayWindow> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> reference.set(new GatewayWindow(session)));
        GatewayWindow window = reference.get();
        try {
            for(int[] size : new int[][]{{1100,760},{740,600}}) for(int tab : new int[]{0,2}) {
                AtomicReference<BufferedImage> painted = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    Container content = window.getContentPane(); content.setSize(size[0],size[1]);
                    selectTab(content,tab); layout(content);
                    BufferedImage image = new BufferedImage(size[0],size[1]+38,BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = image.createGraphics();
                    try {
                        g.setColor(Color.WHITE); g.fillRect(0,0,image.getWidth(),image.getHeight());
                        content.printAll(g);
                        g.setColor(new Color(255,245,210)); g.fillRect(0,size[1],size[0],38);
                        g.setColor(Color.BLACK); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,12));
                        g.drawString("SOFTWARE RENDER • " + state.toUpperCase() + " • actual Swing content • no OS/hardware acceptance",12,size[1]+24);
                    } finally { g.dispose(); }
                    painted.set(image);
                });
                ImageIO.write(painted.get(),"png",output.resolve(state + "-" + (tab == 0 ? "device" : "settings") + "-" + size[0] + "x" + size[1] + ".png").toFile());
            }
        } finally {
            session.stop(); session.awaitIdle(2000);
            SwingUtilities.invokeAndWait(() -> window.dispatchEvent(new WindowEvent(window,WindowEvent.WINDOW_CLOSING)));
        }
    }
    private static void selectTab(Container container,int index) {
        for(Component child : container.getComponents()) {
            if(child instanceof JTabbedPane tabs) tabs.setSelectedIndex(index);
            else if(child instanceof Container nested) selectTab(nested,index);
        }
    }
    private static void layout(Container container) {
        container.doLayout();
        for(Component child : container.getComponents()) if(child instanceof Container nested) layout(nested);
    }
}
