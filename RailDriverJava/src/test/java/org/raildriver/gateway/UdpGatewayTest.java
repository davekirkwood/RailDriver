package org.raildriver.gateway;

import java.net.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback sockets carrying synthetic reports; no USB or simulator involved. */
class UdpGatewayTest {
    static final InetAddress LOCAL = InetAddress.getLoopbackAddress();
    static int freePort() throws Exception { try (DatagramSocket s = new DatagramSocket(0, LOCAL)) { return s.getLocalPort(); } }
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
