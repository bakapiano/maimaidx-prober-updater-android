package com.bakapiano.maimai.updater.crawler;

import android.app.DownloadManager;
import android.util.Log;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class WechatCrawler {
    // Make this true for Fiddler to capture https request
    private static final boolean IGNORE_CERT = false;

    private static final String TAG = "Crawler";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType TEXT = MediaType.parse("text/plain");

    private static OkHttpClient client = null;
    private static final SimpleCookieJar jar = new SimpleCookieJar();

    protected WechatCrawler() {
        buildHttpClient(false);
    }

    public boolean verifyProberAccount(String username, String password) throws IOException {
        String data = String.format(
                "{\"username\" : \"%s\", \"password\" : \"%s\"}",
                username,
                password);
        RequestBody body = RequestBody.create(JSON, data);

        Request request = new Request.Builder()
                .addHeader("Host", "www.diving-fish.com")
                .addHeader("Origin", "https://www.diving-fish.com")
                .addHeader("Referer", "https://www.diving-fish.com/maimaidx/prober/")
                .url("https://www.diving-fish.com/api/maimaidxprober/login")
                .post(body)
                .build();

        Call call = client.newCall(request);
        Response response = call.execute();
        String responseBody = response.body().string().toString();

        Log.d(TAG, "Verify account: " + responseBody + response.toString());
        return !responseBody.contains("errcode");
    }

    protected String getWechatAuthUrl() throws IOException {
        this.buildHttpClient(true);

        Request request = new Request.Builder()
                .addHeader("Host", "tgk-wcaime.wahlap.com")
                .addHeader("Upgrade-Insecure-Requests", "1")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12; IN2010 Build/RKQ1.211119.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/86.0.4240.99 XWEB/4317 MMWEBSDK/20220903 Mobile Safari/537.36 MMWEBID/363 MicroMessenger/8.0.28.2240(0x28001C57) WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/wxpic,image/tpg,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
                .addHeader("X-Requested-With", "com.tencent.mm")
                .addHeader("Sec-Fetch-Site", "none")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-User", "?1")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .url("https://tgk-wcaime.wahlap.com/wc_auth/oauth/authorize/maimai-dx")
                .build();

        Call call = client.newCall(request);
        Response response = call.execute();
        String url = response.request()
                .url()
                .toString()
                .replace("redirect_uri=https", "redirect_uri=http");

        Log.d(TAG, "Auth url:" + url);
        return url;
    }

    protected void fetchData(String username, String password, String wechatAuthUrl) throws IOException {
        if (wechatAuthUrl.startsWith("http"))
            wechatAuthUrl = wechatAuthUrl.replaceFirst("http", "https");

        jar.clearCookieStroe();
        this.loginWechat(wechatAuthUrl);

        this.fetchMaimaiData(username, password);
        this.fetchChunithmData(username, password);
    }

    private void loginWechat(String wechatAuthUrl) throws IOException {
        Log.d(TAG, wechatAuthUrl);

        Request request = new Request.Builder()
                .addHeader("Host", "tgk-wcaime.wahlap.com")
                .addHeader("Upgrade-Insecure-Requests", "1")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 12; IN2010 Build/RKQ1.211119.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/86.0.4240.99 XWEB/4317 MMWEBSDK/20220903 Mobile Safari/537.36 MMWEBID/363 MicroMessenger/8.0.28.2240(0x28001C57) WeChat/arm64 Weixin NetType/WIFI Language/zh_CN ABI/arm64")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/wxpic,image/tpg,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9")
                .addHeader("X-Requested-With", "com.tencent.mm")
                .addHeader("Sec-Fetch-Site", "none")
                .addHeader("Sec-Fetch-Mode", "navigate")
                .addHeader("Sec-Fetch-User", "?1")
                .addHeader("Sec-Fetch-Dest", "document")
                .addHeader("Accept-Encoding", "gzip, deflate")
                .addHeader("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .get()
                .url(wechatAuthUrl)
                .build();

        Call call = client.newCall(request);
        Response response = call.execute();

        // Handle redirect manually
        String newUrl = response.headers().get("Location");
        request = new Request.Builder()
                .url(newUrl)
                .get()
                .build();
        call = client.newCall(request);
        response = call.execute();
    }

    private void fetchMaimaiData(String username, String password) throws IOException {
        this.buildHttpClient(false);
        for (int diff = 0; diff < 5; diff++) {
            // Fetch data
            Request request = new Request.Builder()
                    .url("https://maimai.wahlap.com/maimai-mobile/record/musicGenre/search/?genre=99&diff=" + diff)
                    .build();

            Log.d("Cookie", "diff = " + diff + " start");

            Call call = client.newCall(request);
            Response response = call.execute();

            String data = null;
            data = Objects.requireNonNull(response.body()).string();

            Log.d(TAG, response.request().url().toString() + " " + response.code());

            Matcher matcher = null;
            matcher = Pattern.compile("<html.*>([\\s\\S]*)</html>").matcher(data);
            if (matcher.find()) data = matcher.group(1);
            data = Pattern.compile("\\s+").matcher(data).replaceAll(" ");
            data = "<login><u>" + username + "</u><p>" + password + "</p></login>" + data;

            // Upload data to maimai-prober
            request = new Request.Builder()
                    .url("https://www.diving-fish.com/api/pageparser/page")
                    .addHeader("content-type", "text/plain")
                    .post(RequestBody.create(TEXT, data))
                    .build();

            call = client.newCall(request);
            response = call.execute();
            Log.d(TAG, response.body().string());
        }
    }

    private void fetchChunithmData(String username, String password) throws IOException {
        // TODO
    }

    private void buildHttpClient(boolean followRedirect) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        if (IGNORE_CERT) ignoreCertBuilder(builder);

        builder.connectTimeout(30, TimeUnit.SECONDS);
        builder.readTimeout(30, TimeUnit.SECONDS);
        builder.writeTimeout(30, TimeUnit.SECONDS);

        builder.followRedirects(followRedirect);
        builder.followSslRedirects(followRedirect);

        builder.cookieJar(jar);

        // No cache for http request
        builder.cache(null);
        Interceptor noCacheInterceptor = chain -> {
            Request request = chain.request();
            Request.Builder builder1 = request.newBuilder().addHeader("Cache-Control", "no-cache");
            request = builder1.build();
            return chain.proceed(request);
        };
        builder.addInterceptor(noCacheInterceptor);

        client = builder.build();
    }

    private void ignoreCertBuilder(OkHttpClient.Builder builder) {
        try {
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
            };
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });
        } catch (Exception ignored) {

        }
    }
}
