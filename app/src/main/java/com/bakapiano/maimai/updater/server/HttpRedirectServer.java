package com.bakapiano.maimai.updater.server;

import android.util.Log;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

public class HttpRedirectServer extends NanoHTTPD {
    public static int Port = 9957;
    private final static String TAG = "HttpRedirectServer";

    protected HttpRedirectServer() throws IOException {
        super(Port);
    }

    @Override
    public void start() throws IOException {
        super.start();
        Log.d(TAG, "Http server running on http://localhost:" + Port);
    }

    @Override
    public Response serve(IHTTPSession session) {
        return newFixedLengthResponse(
                Response.Status.ACCEPTED,
                MIME_HTML,
                "<html></html><script>alert('Perfect!');</script>");
    }
}
