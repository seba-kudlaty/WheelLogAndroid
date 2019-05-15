package com.cooper.wheellog.utils;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

public class HttpClient {
    private static AsyncHttpClient client = new AsyncHttpClient(true, 80, 443);

    public static void get(String url, RequestParams params, JsonHttpResponseHandler responseHandler) {
        client.get(url, params, responseHandler);
    }

    public static void post(String url, RequestParams requestParams, JsonHttpResponseHandler responseHandler) {
        client.post(url, requestParams, responseHandler);
    }

}
