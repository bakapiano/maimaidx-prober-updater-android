package com.bakapiano.maimai.updater.vpn.core;

import android.util.Log;

import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import com.bakapiano.maimai.updater.server.HttpRedirectServer;
import com.bakapiano.maimai.updater.server.HttpServer;
import com.bakapiano.maimai.updater.ui.DataContext;
import com.bakapiano.maimai.updater.vpn.tunnel.HttpCapturerTunnel;
import com.bakapiano.maimai.updater.vpn.tunnel.RawTunnel;
import com.bakapiano.maimai.updater.vpn.tunnel.Tunnel;

public class TunnelFactory {
    private final static String TAG = "TunnelFactory";

    public static Tunnel wrap(SocketChannel channel, Selector selector) throws Exception {
        return new RawTunnel(channel, selector);
    }

    public static Tunnel createTunnelByConfig(InetSocketAddress destAddress, Selector selector) throws Exception {
        Log.d(TAG, destAddress.getHostName() + ":" + destAddress.getPort());
        if (destAddress.getHostName().endsWith(DataContext.HookHost)) {
            Log.d(TAG, "Request to" + DataContext.HookHost + " caught");
            return new RawTunnel(
                    new InetSocketAddress("127.0.0.1", HttpServer.Port), selector);
        } else if (destAddress.getHostName().endsWith("wahlap.com") && destAddress.getPort() == 80) {
            Log.d(TAG, "Request for wahlap.com caught");
            return new HttpCapturerTunnel(
                    new InetSocketAddress("127.0.0.1", HttpRedirectServer.Port), selector);
        }
//        else if (destAddress.getHostName().endsWith("wahlap.com") && destAddress.getPort() != 80)
//        {
//            Config config = ProxyConfig.Instance.getDefaultTunnelConfig(destAddress);
//            return new HttpConnectTunnel((HttpConnectConfig) config, selector);
//        }
        else {
            return new RawTunnel(
                    new InetSocketAddress(destAddress.getHostName(), destAddress.getPort()), selector);
        }
    }

}
