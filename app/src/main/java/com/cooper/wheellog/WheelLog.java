package com.cooper.wheellog;

import android.app.Application;


public class WheelLog extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        //Timber.plant(new FileLoggingTree(getApplicationContext()));
        //Timber.plant(new Timber.DebugTree());
        if (BuildConfig.DEBUG) {
            //Timber.plant(new Timber.DebugTree());
        }
    }

}
