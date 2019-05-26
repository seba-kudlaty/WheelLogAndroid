package com.cooper.wheellog;

import android.webkit.JavascriptInterface;

public class WheelLogJSInterface {

    private static WheelLogJSInterface instance;
    private String appLatestVersionName;
    private int appLatestVersionCode;
    private String appLatestDownloadUrl;

    public static WheelLogJSInterface getInstance() {
        if (instance == null)
            instance = new WheelLogJSInterface();
        return instance;
    };

    @JavascriptInterface
    public String getAppVersionName() {
        return BuildConfig.VERSION_NAME;
    }

    @JavascriptInterface
    public int getAppVersionCode() {
        return BuildConfig.VERSION_CODE;
    }

    @JavascriptInterface
    public String getAppLatestVersionName() {
        return appLatestVersionName;
    }

    @JavascriptInterface
    public int getAppLatestVersionCode() {
        return appLatestVersionCode;
    }

    @JavascriptInterface
    public String getAppLatestDownloadUrl() {
        return appLatestDownloadUrl;
    }

    @JavascriptInterface
    public void setAppLatestVersionName(String s) {
        appLatestVersionName = s;
    }

    @JavascriptInterface
    public void setAppLatestVersionCode(int v) {
        appLatestVersionCode = v;
    }

    @JavascriptInterface
    public void setAppLatestDownloadUrl(String s) {
        appLatestDownloadUrl = s;
    }

}
