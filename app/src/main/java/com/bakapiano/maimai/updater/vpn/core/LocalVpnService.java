package com.bakapiano.maimai.updater.vpn.core;

import static com.bakapiano.maimai.updater.vpn.core.Constant.TAG;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.bakapiano.maimai.updater.R;
import com.bakapiano.maimai.updater.ui.MainActivity;
import com.bakapiano.maimai.updater.vpn.core.ProxyConfig.IPAddress;
import com.bakapiano.maimai.updater.vpn.dns.DnsPacket;
import com.bakapiano.maimai.updater.vpn.tcpip.CommonMethods;
import com.bakapiano.maimai.updater.vpn.tcpip.IPHeader;
import com.bakapiano.maimai.updater.vpn.tcpip.TCPHeader;
import com.bakapiano.maimai.updater.vpn.tcpip.UDPHeader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LocalVpnService extends VpnService implements Runnable {

    public static LocalVpnService Instance;
    public static boolean IsRunning = false;

    private final String device = android.os.Build.DEVICE;
    private final String model = android.os.Build.MODEL;
    private final String version = "" + android.os.Build.VERSION.SDK_INT + " (" + android.os.Build.VERSION.RELEASE + ")";

    private static int ID;
    private static int LOCAL_IP;
    private static final ConcurrentHashMap<onStatusChangedListener, Object> m_OnStatusChangedListeners = new ConcurrentHashMap<>();

    private Thread m_VPNThread;
    private ParcelFileDescriptor m_VPNInterface;
    private TcpProxyServer m_TcpProxyServer;
    private DnsProxy m_DnsProxy;
    private FileOutputStream m_VPNOutputStream;

    private final byte[] m_Packet;
    private final IPHeader m_IPHeader;
    private final TCPHeader m_TCPHeader;
    private final UDPHeader m_UDPHeader;
    private final ByteBuffer m_DNSBuffer;
    private final Handler m_Handler;
    private long m_SentBytes;
    private long m_ReceivedBytes;
    private String[] m_Blacklist;

    public LocalVpnService() {
        ID++;
        m_Handler = new Handler();
        m_Packet = new byte[20000];
        m_IPHeader = new IPHeader(m_Packet, 0);
        m_TCPHeader = new TCPHeader(m_Packet, 20);
        m_UDPHeader = new UDPHeader(m_Packet, 20);
        m_DNSBuffer = ((ByteBuffer) ByteBuffer.wrap(m_Packet).position(28)).slice();
        Instance = this;

        Log.d("VpnProxy", "New VPNService" + ID);
    }

    public static void addOnStatusChangedListener(onStatusChangedListener listener) {
        if (!m_OnStatusChangedListeners.containsKey(listener)) {
            m_OnStatusChangedListeners.put(listener, 1);
        }
    }

    public static void removeOnStatusChangedListener(onStatusChangedListener listener) {
        m_OnStatusChangedListeners.remove(listener);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            m_TcpProxyServer = new TcpProxyServer(0);
            m_TcpProxyServer.start();
            writeLog("LocalTcpServer started.");

            m_DnsProxy = new DnsProxy();
            m_DnsProxy.start();
        } catch (Exception e) {
            writeLog("Failed to start TCP/DNS Proxy");
        }

        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        IsRunning = true;
        // Start a new session by creating a new thread.
        m_VPNThread = new Thread(this, "VPNServiceThread");
        m_VPNThread.start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        String action = intent.getAction();
        if (action.equals(VpnService.SERVICE_INTERFACE)) {
            return super.onBind(intent);
        }
        return null;
    }

    private void onStatusChanged(final String status, final boolean isRunning) {
        m_Handler.post(() -> {
            for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                entry.getKey().onStatusChanged(status, isRunning);
            }
        });
    }

    public void writeLog(final String format, Object... args) {
        final String logString = String.format(format, args);
        m_Handler.post(() -> {
            for (Map.Entry<onStatusChangedListener, Object> entry : m_OnStatusChangedListeners.entrySet()) {
                entry.getKey().onLogReceived(logString);
            }
        });
    }

    public void sendUDPPacket(IPHeader ipHeader, UDPHeader udpHeader) {
        try {
            CommonMethods.ComputeUDPChecksum(ipHeader, udpHeader);
            this.m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, ipHeader.getTotalLength());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    String getAppInstallID() {
        SharedPreferences preferences = getSharedPreferences(TAG, MODE_PRIVATE);
        String appInstallID = preferences.getString("AppInstallID", null);
        if (appInstallID == null || appInstallID.isEmpty()) {
            appInstallID = UUID.randomUUID().toString();
            Editor editor = preferences.edit();
            editor.putString("AppInstallID", appInstallID);
            editor.apply();
        }
        return appInstallID;
    }

    String getVersionName() {
        try {
            PackageManager packageManager = getPackageManager();
            PackageInfo packInfo = packageManager.getPackageInfo(getPackageName(), 0);
            String version = packInfo.versionName;
            return version;
        } catch (Exception e) {
            return "0.0";
        }
    }

    public String configDirFor(Context context, String suffix) {
        return new File(context.getFilesDir().getAbsolutePath(), ".lantern" + suffix).getAbsolutePath();
    }

    @Override
    public synchronized void run() {
        try {
            Log.d(TAG, "VPNService work thread is running... " + ID);

            ProxyConfig.AppInstallID = getAppInstallID();
            ProxyConfig.AppVersion = getVersionName();
            writeLog("Android version: %s", Build.VERSION.RELEASE);
            writeLog("App version: %s", ProxyConfig.AppVersion);

            waitUntilPreapred();

            runVPN();

        } catch (InterruptedException e) {
            Log.e(TAG, "Exception", e);
        } catch (Exception e) {
            e.printStackTrace();
            writeLog("Fatal error: %s", e.toString());
        }

        writeLog("VpnProxy terminated.");
        dispose();
    }

    private void runVPN() throws Exception {
        this.m_VPNInterface = establishVPN();
        this.m_VPNOutputStream = new FileOutputStream(m_VPNInterface.getFileDescriptor());
        try (FileInputStream in = new FileInputStream(m_VPNInterface.getFileDescriptor())) {
            while (IsRunning) {
                boolean idle = true;
                int size = in.read(m_Packet);
                if (size > 0) {
                    if (m_DnsProxy.Stopped || m_TcpProxyServer.Stopped) {
                        in.close();
                        throw new Exception("LocalServer stopped.");
                    }
                    try {
                        onIPPacketReceived(m_IPHeader, size);
                        idle = false;
                    } catch (IOException ex) {
                        Log.e(TAG, "IOException when processing IP packet", ex);
                    }
                }
                if (idle) {
                    Thread.sleep(100);
                }
            }
        }
    }

    void onIPPacketReceived(IPHeader ipHeader, int size) throws IOException {
        switch (ipHeader.getProtocol()) {
            case IPHeader.TCP:
                TCPHeader tcpHeader = m_TCPHeader;
                tcpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP) {
                    if (tcpHeader.getSourcePort() == m_TcpProxyServer.Port) {
                        NatSession session = NatSessionManager.getSession(tcpHeader.getDestinationPort());
                        if (session != null) {
                            ipHeader.setSourceIP(ipHeader.getDestinationIP());
                            tcpHeader.setSourcePort(session.RemotePort);
                            ipHeader.setDestinationIP(LOCAL_IP);

                            CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                            m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                            m_ReceivedBytes += size;
                        } else {
                            if (ProxyConfig.IS_DEBUG)
                                Log.d(TAG, "NoSession: " +
                                        ipHeader + " " +
                                        tcpHeader);
                        }
                    } else {
                        int portKey = tcpHeader.getSourcePort();
                        NatSession session = NatSessionManager.getSession(portKey);
                        if (session == null || session.RemoteIP != ipHeader.getDestinationIP() || session.RemotePort != tcpHeader.getDestinationPort()) {
                            session = NatSessionManager.createSession(portKey, ipHeader.getDestinationIP(), tcpHeader.getDestinationPort());
                        }

                        session.LastNanoTime = System.nanoTime();
                        session.PacketSent++;

                        int tcpDataSize = ipHeader.getDataLength() - tcpHeader.getHeaderLength();
                        if (session.PacketSent == 2 && tcpDataSize == 0) {
                            return;
                        }

                        if (session.BytesSent == 0 && tcpDataSize > 10) {
                            int dataOffset = tcpHeader.m_Offset + tcpHeader.getHeaderLength();
                            String host = HttpHostHeaderParser.parseHost(tcpHeader.m_Data, dataOffset, tcpDataSize);
                            if (host != null) {
                                session.RemoteHost = host;
                            }
                        }

                        ipHeader.setSourceIP(ipHeader.getDestinationIP());
                        ipHeader.setDestinationIP(LOCAL_IP);
                        tcpHeader.setDestinationPort(m_TcpProxyServer.Port);

                        CommonMethods.ComputeTCPChecksum(ipHeader, tcpHeader);
                        m_VPNOutputStream.write(ipHeader.m_Data, ipHeader.m_Offset, size);
                        session.BytesSent += tcpDataSize;
                        m_SentBytes += size;
                    }
                }
                break;
            case IPHeader.UDP:
                UDPHeader udpHeader = m_UDPHeader;
                udpHeader.m_Offset = ipHeader.getHeaderLength();
                if (ipHeader.getSourceIP() == LOCAL_IP && udpHeader.getDestinationPort() == 53) {
                    m_DNSBuffer.clear();
                    m_DNSBuffer.limit(ipHeader.getDataLength() - 8);
                    DnsPacket dnsPacket = DnsPacket.FromBytes(m_DNSBuffer);
                    if (dnsPacket != null && dnsPacket.Header.QuestionCount > 0) {
                        m_DnsProxy.onDnsRequestReceived(ipHeader, udpHeader, dnsPacket);
                    }
                }
                break;
        }
    }

    private void waitUntilPreapred() {
        while (prepare(this) != null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // Ignore
            }
        }
    }

    private ParcelFileDescriptor establishVPN() {

        NatSessionManager.clearAllSessions();

        Builder builder = new Builder();
        builder.setMtu(ProxyConfig.Instance.getMTU());

        IPAddress ipAddress = ProxyConfig.Instance.getDefaultLocalIP();
        LOCAL_IP = CommonMethods.ipStringToInt(ipAddress.Address);

        builder.addAddress(ipAddress.Address, ipAddress.PrefixLength);
        if (ProxyConfig.IS_DEBUG)
            Log.d(TAG, String.format("addAddress: %s/%d\n", ipAddress.Address, ipAddress.PrefixLength));

        if (m_Blacklist == null) {
            m_Blacklist = getResources().getStringArray(R.array.black_list);
        }

        ProxyConfig.Instance.resetDomain(m_Blacklist);

        for (String routeAddress : getResources().getStringArray(R.array.bypass_private_route)) {
            String[] addr = routeAddress.split("/");
            builder.addRoute(addr[0], Integer.parseInt(addr[1]));
        }

        builder.addRoute(CommonMethods.ipIntToString(ProxyConfig.FAKE_NETWORK_IP), 16);

        //        PackageManager packageManager = getPackageManager();
//        List<PackageInfo> list = packageManager.getInstalledPackages(0);
//        HashSet<String> packageSet = new HashSet<>();
//
//        for (int i = 0; i < list.size(); i++) {
//            PackageInfo info = list.get(i);
//            Log.d(TAG, info.packageName);
//            if (info.packageName.equals("com.bakapiano.maimai.updater")) {
//                Log.d(TAG, "Found maimai updater");
//            }
//            else {
//                packageSet.add(info.packageName);
//                Log.d(TAG, "fuck" + info.packageName);
//            }
//        }
//
//        packageSet.add("com.tencent.mm");
//
//        for (String name : getResources().getStringArray(R.array.bypass_package_name)) {
//            if (packageSet.contains(name)) {
//                builder.addDisallowedApplication(name);
//            }
//        }

//        builder.addDisallowedApplication("com.wechat.mm");
//        builder.addAllowedApplication("com.tencent.mm");
//        builder.addAllowedApplication("com.bakapiano.maimai.updater");

        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        builder.setConfigureIntent(pendingIntent);

        builder.setSession(ProxyConfig.Instance.getSessionName());

        ParcelFileDescriptor pfdDescriptor = builder.establish();
        onStatusChanged(ProxyConfig.Instance.getSessionName() + " " + getString(R.string.vpn_connected_status), true);
        return pfdDescriptor;
    }

    private synchronized void dispose() {

        onStatusChanged(ProxyConfig.Instance.getSessionName() + " " + getString(R.string.vpn_disconnected_status), false);

        IsRunning = false;

        try {
            if (m_VPNInterface != null) {
                m_VPNInterface.close();
                m_VPNInterface = null;
            }
        } catch (Exception e) {
            // ignore
        }

        try {
            if (m_VPNOutputStream != null) {
                m_VPNOutputStream.close();
                m_VPNOutputStream = null;
            }
        } catch (Exception e) {
            // ignore
        }

//        try {
//            Lantern.RemoveOverrides();
//        } catch (Exception e) {
//            // Ignore
//        }

        if (m_VPNThread != null) {
            m_VPNThread.interrupt();
            m_VPNThread = null;
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "VPNService(%s) destroyed: " + ID);
        if (IsRunning) dispose();
        try {
            // ֹͣTcpServer
            if (m_TcpProxyServer != null) {
                m_TcpProxyServer.stop();
                m_TcpProxyServer = null;
                // writeLog("LocalTcpServer stopped.");
            }
        } catch (Exception e) {
            // ignore
        }

        try {
            // DnsProxy
            if (m_DnsProxy != null) {
                m_DnsProxy.stop();
                m_DnsProxy = null;
                // writeLog("LocalDnsProxy stopped.");
            }
        } catch (Exception e) {
            // ignore
        }
        super.onDestroy();
    }

    public interface onStatusChangedListener {
        void onStatusChanged(String status, Boolean isRunning);
        void onLogReceived(String logString);
    }

}
