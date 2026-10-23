package org.raildriver.gateway;

import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback sockets carrying synthetic reports; no USB or simulator involved. */
class UdpGatewayTest {
    static final InetAddress LOCAL;
    static {
        try { LOCAL = InetAddress.getByName("127.0.0.1"); }
        catch (UnknownHostException impossible) { throw new ExceptionInInitializerError(impossible); }
    }
    static int freePort() throws Exception { try (DatagramSocket s = new DatagramSocket(0, LOCAL)) { return s.getLocalPort(); } }
    record LedWrite(byte[] payload, byte reportId) { }
    static class PumpIo implements RailDriverDevice.Io {
        final BlockingQueue<byte[]> reports = new LinkedBlockingQueue<>();
        final BlockingQueue<LedWrite> writes = new LinkedBlockingQueue<>();
        volatile boolean closed;
        public int read(byte[] buffer, int timeoutMillis) {
            try {
                byte[] report = reports.poll(timeoutMillis, TimeUnit.MILLISECONDS);
                if (report == null) return 0;
                System.arraycopy(report, 0, buffer, 0, report.length);
                return report.length;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        public boolean attached() { return true; }
        public int write(byte[] payload, byte reportId) {
            writes.add(new LedWrite(payload.clone(), reportId));
            return payload.length + 1;
        }
        public String error() { return "Synthetic interrupted read"; }
        public void close() { closed = true; }
    }
    static class PumpSession implements AutoCloseable {
        final PumpIo io = new PumpIo();
        final UdpGateway gateway;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Future<?> worker;
        PumpSession(int commandPort, int peerPort, int destinationPort) throws Exception {
            AtomicInteger pendingNumber = new AtomicInteger(-1);
            gateway = new UdpGateway(commandPort, peerPort, destinationPort, pendingNumber::set);
            worker = executor.submit(() -> {
                try (RailDriverDevice device = new RailDriverDevice(io)) {
                    Main.pump(gateway, device, pendingNumber);
                }
                return null;
            });
        }
        public void close() throws Exception {
            gateway.close();
            try { worker.get(2, TimeUnit.SECONDS); }
            finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
                gateway.awaitStopped();
            }
            assertTrue(io.closed);
        }
    }
    static void awaitFrame(DatagramSocket sink, byte[] expected) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        sink.setSoTimeout(100);
        while (System.nanoTime() - deadline < 0) {
            DatagramPacket packet = new DatagramPacket(new byte[100], 100);
            try {
                sink.receive(packet);
                assertEquals(20, packet.getLength());
                if (java.util.Arrays.equals(expected,
                        java.util.Arrays.copyOf(packet.getData(), packet.getLength()))) return;
            } catch (SocketTimeoutException waiting) { }
        }
        fail("Expected complete synthetic report frame was not received");
    }
    @Test void rawReportsReachPumpAndPublishChangedCompleteFrames() throws Exception {
        try (DatagramSocket sink = new DatagramSocket(0, LOCAL);
             DatagramSocket peer = new DatagramSocket(0, LOCAL);
             PumpSession pump = new PumpSession(freePort(), peer.getLocalPort(), sink.getLocalPort())) {
            // Unprefixed 14-byte HID reports, with expected frames calculated independently.
            pump.io.reports.add(new byte[] {0,38,0,0,0,110,(byte)150,0,0,0,0,0,0,0});
            awaitFrame(sink, new byte[] {0,10,0,10,0,10,0,0,0,0,0,0,0,0,0,0,8,0,0,72});
            pump.io.reports.add(new byte[] {(byte)255,(byte)227,(byte)255,(byte)255,0,(byte)151,(byte)151,0,0,0,0,0,0,0});
            awaitFrame(sink, new byte[] {0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,5,0,(byte)128,8});
        }
    }
    @Test void shortReportAfterValidStateStopsPumpAndTransport() throws Exception {
        PumpIo io = new PumpIo();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (DatagramSocket sink = new DatagramSocket(0, LOCAL);
             DatagramSocket peer = new DatagramSocket(0, LOCAL);
             UdpGateway gateway = new UdpGateway(freePort(), peer.getLocalPort(), sink.getLocalPort(), n -> {})) {
            Future<?> worker = executor.submit(() -> {
                try (RailDriverDevice device = new RailDriverDevice(io)) {
                    Main.pump(gateway, device, new AtomicInteger(-1));
                }
                return null;
            });
            io.reports.add(new byte[] {0,38,0,0,0,110,(byte)150,0,0,0,0,0,0,0});
            awaitFrame(sink, new byte[] {0,10,0,10,0,10,0,0,0,0,0,0,0,0,0,0,8,0,0,72});
            io.reports.add(new byte[7]);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> worker.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.io.IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("Invalid HID input length=7"));
            assertFalse(gateway.isRunning());
            assertTrue(io.closed);
            gateway.awaitStopped();
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }
    @Test void commandTraversesPendingNumberAndIdlePumpToLedThenShutdown() throws Exception {
        try (DatagramSocket sink = new DatagramSocket(0, LOCAL);
             DatagramSocket peer = new DatagramSocket(0, LOCAL)) {
            int commandPort = freePort();
            try (PumpSession pump = new PumpSession(commandPort, peer.getLocalPort(), sink.getLocalPort())) {
                // No reports queued: fake HID repeatedly waits and returns read timeouts.
                send(peer, commandPort, (byte)83, (byte)1, (byte)7);
                LedWrite write = pump.io.writes.poll(2, TimeUnit.SECONDS);
                assertNotNull(write);
                assertArrayEquals(new byte[] {7,63,6,0,0,0,0}, write.payload());
                assertEquals(134, Byte.toUnsignedInt(write.reportId()));
                assertTrue(pump.gateway.isRunning());
                send(peer, commandPort, (byte)81, (byte)0, (byte)0);
                pump.worker.get(2, TimeUnit.SECONDS);
                assertFalse(pump.gateway.isRunning());
                assertTrue(pump.io.closed);
            }
        }
    }
    static void send(DatagramSocket socket, int port, byte... bytes) throws Exception {
        socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getByName("127.0.0.1"), port));
    }
    @Test void gatingCompleteDatagramsCommandsAndStop() throws Exception {
        AtomicInteger display = new AtomicInteger(-1);
        try (DatagramSocket sink = new DatagramSocket(0, LOCAL);
             DatagramSocket peer = new DatagramSocket(0, LOCAL);
             DatagramSocket outsider = new DatagramSocket(0, LOCAL)) {
            int port = freePort();
            try (UdpGateway gateway = new UdpGateway(port, peer.getLocalPort(), sink.getLocalPort(), display::set)) {
                sink.setSoTimeout(80);
                assertThrows(SocketTimeoutException.class, () -> sink.receive(new DatagramPacket(new byte[100],100)));
                byte[] frame=InputMapper.map(InputMapperTest.report(127,132,70,128,111,151));
                gateway.publish(frame); frame[1]=99;
                sink.setSoTimeout(1000);
                for (int i=0;i<3;i++) {
                    DatagramPacket packet=new DatagramPacket(new byte[100],100); sink.receive(packet);
                    assertEquals(20,packet.getLength()); assertEquals(7,packet.getData()[1]);
                }
                send(outsider,port,(byte)81,(byte)0,(byte)0);
                send(peer,port,(byte)83,(byte)1); // short
                send(peer,port,(byte)83,(byte)1,(byte)2,(byte)3); // long
                send(peer,port,(byte)99,(byte)1,(byte)2); // unknown
                Thread.sleep(60);
                assertTrue(gateway.isRunning()); assertEquals(-1,display.get());
                send(peer,port,(byte)83,(byte)255,(byte)255);
                long deadline=System.nanoTime()+1_000_000_000L;
                while(display.get()==-1 && System.nanoTime()<deadline) Thread.sleep(5);
                assertEquals(25755,display.get());
                send(peer,port,(byte)81,(byte)0,(byte)0);
                assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                    while(gateway.isRunning()) Thread.sleep(5);
                    gateway.awaitStopped();
                });
                // Drain datagrams already queued before closure, then prove no further sends.
                sink.setSoTimeout(50);
                try { while(true) sink.receive(new DatagramPacket(new byte[100],100)); }
                catch(SocketTimeoutException expected) { }
                assertThrows(SocketTimeoutException.class, () -> sink.receive(new DatagramPacket(new byte[100],100)));
            }
        }
    }
    @Test void simulatorMayStartAfterGateway() throws Exception {
        int destination=freePort();
        byte[] frame=InputMapper.map(InputMapperTest.report(127,132,70,128,111,151));
        try(UdpGateway gateway=new UdpGateway(freePort(),freePort(),destination,n -> {})) {
            gateway.publish(frame);
            // Real loopback sends to an unbound destination; several ICMP error cycles can occur.
            Thread.sleep(200);
            assertTrue(gateway.isRunning(), "Absent simulator must not stop the gateway");
            assertNull(gateway.failure());
            try(DatagramSocket sink=new DatagramSocket(destination, LOCAL)) {
                sink.setSoTimeout(1000);
                for(int i=0;i<3;i++) {
                    DatagramPacket packet=new DatagramPacket(new byte[100],100);
                    sink.receive(packet);
                    assertEquals(20,packet.getLength());
                    assertArrayEquals(frame,java.util.Arrays.copyOf(packet.getData(),packet.getLength()));
                }
                assertTrue(gateway.isRunning());
            }
        }
    }
    @Test void exclusiveBindAndIdleShutdown() throws Exception {
        int port=freePort();
        try(UdpGateway first=new UdpGateway(port,freePort(),freePort(),n -> {})) {
            assertThrows(SocketException.class, () -> new UdpGateway(port,1,2,n -> {}));
            first.close();
            assertTimeoutPreemptively(Duration.ofSeconds(2), first::awaitStopped);
        }
    }
    @Test void usbFailureStopsTransportBeforeCleanup() throws Exception {
        RailDriverDeviceTest.FakeIo io=new RailDriverDeviceTest.FakeIo();
        io.result=-1;
        try(UdpGateway gateway=new UdpGateway(freePort(),freePort(),freePort(),n -> {});
            RailDriverDevice device=new RailDriverDevice(io)) {
            gateway.publish(InputMapper.map(InputMapperTest.report(0,38,0,0,0,0)));
            assertThrows(java.io.IOException.class, () -> Main.pump(gateway,device,new AtomicInteger(-1)));
            assertFalse(gateway.isRunning());
            assertFalse(io.closed); // transport stopped before native close
            assertTimeoutPreemptively(Duration.ofSeconds(2), gateway::awaitStopped);
        }
        assertTrue(io.closed);
    }
    @Test void helpNeedsNoHardwareOrPorts() { assertEquals(0,Main.run(new String[]{"--help"})); }
}
