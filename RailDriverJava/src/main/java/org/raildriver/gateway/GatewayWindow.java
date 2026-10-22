package org.raildriver.gateway;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Native window owner; the same content panel is exercised without a native peer in tests. */
public final class GatewayWindow extends JFrame {
    public GatewayWindow() { this(new GatewaySession()); }
    GatewayWindow(GatewaySession session) {
        super("RailDriver · Device workbench");
        GatewayContent content = new GatewayContent(session, this::dispose, null);
        setContentPane(content); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1100,760); setMinimumSize(new Dimension(740,600)); setLocationByPlatform(true);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { content.requestClose(); } });
    }
}
