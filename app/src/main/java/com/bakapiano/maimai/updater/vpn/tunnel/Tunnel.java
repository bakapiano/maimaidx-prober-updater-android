package com.bakapiano.maimai.updater.vpn.tunnel;

import android.annotation.SuppressLint;
import android.util.Log;

import com.bakapiano.maimai.updater.vpn.core.Constant;
import com.bakapiano.maimai.updater.vpn.core.LocalVpnService;
import com.bakapiano.maimai.updater.vpn.core.ProxyConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

public abstract class Tunnel {

    public static long SessionCount;
    protected InetSocketAddress m_DestAddress;
    public SocketChannel m_InnerChannel;
    private ByteBuffer m_SendRemainBuffer;
    private Selector m_Selector;
    public Tunnel m_BrotherTunnel;
    private boolean m_Disposed;
    private InetSocketAddress m_ServerEP;
    public Tunnel(SocketChannel innerChannel, Selector selector) throws IOException {
        this.m_InnerChannel = innerChannel;
        this.m_InnerChannel.socket().setSoTimeout(1000*30);
        this.m_Selector = selector;
        SessionCount++;
    }
    public Tunnel(InetSocketAddress serverAddress, Selector selector) throws IOException {
        SocketChannel innerChannel = SocketChannel.open();
        innerChannel.configureBlocking(false);
        this.m_InnerChannel = innerChannel;
        this.m_InnerChannel.socket().setSoTimeout(1000*30);
        this.m_Selector = selector;
        this.m_ServerEP = serverAddress;
        SessionCount++;
    }

    protected abstract void onConnected(ByteBuffer buffer) throws Exception;

    protected abstract boolean isTunnelEstablished();

    protected abstract void beforeSend(ByteBuffer buffer) throws Exception;

    protected abstract void afterReceived(ByteBuffer buffer) throws Exception;

    protected abstract void onDispose();

    public void setBrotherTunnel(Tunnel brotherTunnel) {
        m_BrotherTunnel = brotherTunnel;
    }

    public void connect(InetSocketAddress destAddress) throws Exception {
        if (LocalVpnService.Instance.protect(m_InnerChannel.socket())) {
            m_DestAddress = destAddress;
            m_InnerChannel.register(m_Selector, SelectionKey.OP_CONNECT, this);
            m_InnerChannel.connect(m_ServerEP);
        } else {
            throw new Exception("VPN protect socket failed.");
        }
    }

    protected void beginReceive() throws Exception {
        if (m_InnerChannel.isBlocking()) {
            m_InnerChannel.configureBlocking(false);
        }
        m_InnerChannel.register(m_Selector, SelectionKey.OP_READ, this);
    }


    protected boolean write(ByteBuffer buffer, boolean copyRemainData) throws Exception {
        int bytesSent;
        while (buffer.hasRemaining()) {
            bytesSent = m_InnerChannel.write(buffer);
            if (bytesSent == 0) {
                break;
            }
        }

        if (buffer.hasRemaining()) {
            if (copyRemainData) {
                if (m_SendRemainBuffer == null) {
                    m_SendRemainBuffer = ByteBuffer.allocate(buffer.capacity());
                }
                m_SendRemainBuffer.clear();
                m_SendRemainBuffer.put(buffer);
                m_SendRemainBuffer.flip();
                m_InnerChannel.register(m_Selector, SelectionKey.OP_WRITE, this);
            }
            return false;
        } else {
            return true;
        }
    }

    protected void onTunnelEstablished() throws Exception {
        this.beginReceive();
        m_BrotherTunnel.beginReceive();
    }

    @SuppressLint("DefaultLocale")
    public void onConnectable() {
        try {
            if (m_InnerChannel.finishConnect()) {
                onConnected(ByteBuffer.allocate(2048));
            } else {
                // LocalVpnService.Instance.writeLog("Error: connect to %s failed.", m_ServerEP);
                this.dispose();
            }
        } catch (Exception e) {
            // LocalVpnService.Instance.writeLog("Error: connect to %s failed: %s", m_ServerEP, e);
            this.dispose();
        }
    }

    public void onReadable(SelectionKey key) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(2048);
            buffer.clear();
            int bytesRead = m_InnerChannel.read(buffer);
            if (bytesRead > 0) {
                buffer.flip();
                afterReceived(buffer);
                if (isTunnelEstablished() && buffer.hasRemaining()) {
                    m_BrotherTunnel.beforeSend(buffer);
                    if (!m_BrotherTunnel.write(buffer, true)) {
                        key.cancel();
                        if (ProxyConfig.IS_DEBUG)
                            Log.d(Constant.TAG, m_ServerEP + "can not read more.");
                    }
                }
            } else if (bytesRead < 0) {
                this.dispose();
            }
        } catch (Exception e) {
            e.printStackTrace();
            this.dispose();
        }
    }

    public void onWritable(SelectionKey key) {
        try {
            this.beforeSend(m_SendRemainBuffer);
            if (this.write(m_SendRemainBuffer, false)) {
                key.cancel();
                if (isTunnelEstablished()) {
                    m_BrotherTunnel.beginReceive();
                } else {
                    this.beginReceive();
                }
            }
        } catch (Exception e) {
            this.dispose();
        }
    }

    public void dispose() {
        disposeInternal(true);
    }

    void disposeInternal(boolean disposeBrother) {
        if (!m_Disposed) {
            try {
                m_InnerChannel.close();
            } catch (Exception ignored) { }

            if (m_BrotherTunnel != null && disposeBrother) {
                m_BrotherTunnel.disposeInternal(false);
            }

            m_InnerChannel = null;
            m_SendRemainBuffer = null;
            m_Selector = null;
            m_BrotherTunnel = null;
            m_Disposed = true;
            SessionCount--;

            onDispose();
        }
    }
}
