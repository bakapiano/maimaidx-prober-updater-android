package com.bakapiano.maimai.updater.crawler;

import android.util.Log;

import com.bakapiano.maimai.updater.ui.DataContext;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

public class CrawlerCaller {
    private static final String TAG = "CrawlerCaller";

    static public String getWechatAuthUrl() {
        try {
            WechatCrawler crawler = new WechatCrawler();
            return crawler.getWechatAuthUrl();
        } catch (IOException error) {
            return null;
        }
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
