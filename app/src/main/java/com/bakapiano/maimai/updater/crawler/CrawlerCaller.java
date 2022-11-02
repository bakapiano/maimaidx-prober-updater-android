package com.bakapiano.maimai.updater.crawler;

import android.os.Handler;
import android.util.Log;

import com.bakapiano.maimai.updater.ui.DataContext;
import com.bakapiano.maimai.updater.vpn.core.LocalVpnService;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class CrawlerCaller {
    private static final String TAG = "CrawlerCaller";
    private static final Handler m_Handler = new Handler();
    public static LocalVpnService.onStatusChangedListener listener;

    static public String getWechatAuthUrl() {
        try {
            WechatCrawler crawler = new WechatCrawler();
            return crawler.getWechatAuthUrl();
        } catch (IOException error) {
            return null;
        }
    }

    static public void writeLog(String text) {
        m_Handler.post(() -> listener.onLogReceived(text));
    }

    static public void fetchData(String authUrl) {
        new Thread(() -> {
            try {
                WechatCrawler crawler = new WechatCrawler();
                crawler.fetchData(DataContext.Username, DataContext.Password, authUrl);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }).start();
    }

    static public void verifyAccount(String username, String password, Callback callback) {
        new Thread(() -> {
            try {
                WechatCrawler crawler = new WechatCrawler();
                Boolean result = crawler.verifyProberAccount(username, password);
                callback.onResponse(result);
            } catch (IOException error) {
                error.printStackTrace();
                callback.onError(error);
            }
        }).start();
    }
}
