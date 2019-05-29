package com.cooper.wheellog;

import android.content.Context;
import android.webkit.JavascriptInterface;

public class WheelLogJSInterface {

    private Context mContext;
    private static String appLatestVersionName;
    private static int appLatestVersionCode;
    private static String appLatestDownloadUrl;

    WheelLogJSInterface(Context context) {
        mContext = context;
    }

    @JavascriptInterface
    public String getResourceString(String name) {
        int id = mContext.getResources().getIdentifier(name, "string", mContext.getPackageName());
        return (id > 0) ? mContext.getString(id) : "";
    }

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
