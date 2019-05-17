package com.cooper.wheellog;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.widget.Toast;

import com.cooper.wheellog.utils.Constants;
import com.cooper.wheellog.utils.HttpClient;
import com.cooper.wheellog.utils.PermissionsUtil;
import com.cooper.wheellog.utils.SettingsUtil;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public class LivemapService extends Service {
    public enum LivemapStatus {
        DISCONNECTED,
        CONNECTING,
        WAITING_FOR_GPS,
        STARTED,
        PAUSING,
        PAUSED,
        RESUMING,
        DISCONNECTING
    }

    private static LivemapService instance = null;
    private static LivemapStatus status = LivemapStatus.DISCONNECTED;
    private static String url = "";
    private static boolean autoStarted = false;
    private static int autoStartedPublish = 1;
    private static int livemapStatus = -1;
    private static final int NOTIFY_ID = 36901;
    private static final String CHANNEL_ID = "chan_wl_livemap";

    private NotificationManager notificationManager;
    private String updateDateTime = "";
    private String tourKey;
    private long lastUpdated;
    private Location lastLocation;
    private double lastLatitude;
    private double lastLongitude;
    private long lastLocationTime;
    private Location currentLocation;
    private long wheelUpdated = 0;
    private boolean wheelDisconnected = false;
    private double currentDistance;
    private LocationManager locationManager;
    private BatteryManager batteryManager;
    private SimpleDateFormat df;
    private boolean updating = false;

    private long weatherTimestamp = 0;
    private double weatherTemperature;
    private double weatherTemperatureFeels;
    private double weatherWindSpeed;
    private double weatherWindDir;
    private double weatherHumidity;
    private double weatherPressure;
    private double weatherPrecipitation;
    private double weatherVisibility;
    private double weatherCloudCoverage;
    private int weatherCondition;

    public static boolean isInstanceCreated() {
        return instance != null;
    }
    public static LivemapService getInstance() { return instance; }
    public static LivemapStatus getStatus() { return status; }
    public static String getUrl() { return url; }
    public static boolean getAutoStarted() { return autoStarted; }
    public static void setAutoStarted(boolean b) { autoStarted = b; }
    public static int getLivemapStatus() { return livemapStatus; }

    LocationListener locationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            currentLocation = location;
            lastLocationTime = location.getTime();
            if (lastLocation != null) {
                if ((currentLocation.getSpeed() * 3.6 >= 3.0f) || (lastLocation.distanceTo(currentLocation) >= location.getAccuracy())) {
                    lastLocation = currentLocation;
                    lastLatitude = location.getLatitude();
                    lastLongitude = location.getLongitude();
                    currentDistance += lastLocation.distanceTo(currentLocation);
                }
            }
            else
                lastLocation = currentLocation;
            updateLivemap();
        }
        public void onStatusChanged(String provider, int status, Bundle extras) {}
        public void onProviderEnabled(String provider) {}
        public void onProviderDisabled(String provider) {}
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            switch (action) {
                case Constants.ACTION_WHEEL_DATA_AVAILABLE:
                    wheelDisconnected = false;
                    wheelUpdated = SystemClock.elapsedRealtime();
                    break;
                case Constants.ACTION_LIVEMAP_PAUSE:
                    pauseLivemap();
                    break;
                case Constants.ACTION_LIVEMAP_RESUME:
                    resumeLivemap();
                    break;
            }
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_WHEEL_DATA_AVAILABLE);
        intentFilter.addAction(Constants.ACTION_BLUETOOTH_CONNECTION_STATE);
        intentFilter.addAction(Constants.ACTION_LIVEMAP_PAUSE);
        intentFilter.addAction(Constants.ACTION_LIVEMAP_RESUME);
        registerReceiver(receiver, intentFilter);
        if (!PermissionsUtil.checkLocationPermission(this)) {
            showToast(R.string.livemap_error_no_location_permission, Toast.LENGTH_LONG);
            stopSelf();
            return START_STICKY;
        }
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if ((locationManager == null) || (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))) {
            locationManager = null;
            showToast(R.string.livemap_error_no_gps_provider, Toast.LENGTH_LONG);
            stopSelf();
            return START_STICKY;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
        Intent serviceStartedIntent = new Intent(Constants.ACTION_LIVEMAP_SERVICE_TOGGLED).putExtra(Constants.INTENT_EXTRA_IS_RUNNING, true);
        sendBroadcast(serviceStartedIntent);
        startLivemap();
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        startForeground(NOTIFY_ID, getNotification(getString(R.string.notification_livemap_title), getString(R.string.livemap_connecting)));
        instance = this;
        livemapStatus = 0;
        batteryManager = (BatteryManager)getSystemService(BATTERY_SERVICE);
        df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.app_name);
            NotificationChannel mChannel = new NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW);
            mChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            notificationManager.createNotificationChannel(mChannel);
        }
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        stopLivemap();
        autoStarted = false;
        unregisterReceiver(receiver);
        if (locationManager != null)
            locationManager.removeUpdates(locationListener);
        Intent serviceStartedIntent = new Intent(Constants.ACTION_LIVEMAP_SERVICE_TOGGLED).putExtra(Constants.INTENT_EXTRA_IS_RUNNING, false);
        sendBroadcast(serviceStartedIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(CHANNEL_ID);
        }
        super.onDestroy();
        instance = null;
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    private Notification getNotification(String title, String description) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_action_wheel_orange)
                .setContentTitle(title)
                .setContentText(description)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);
        return builder.build();
    }

    private void updateLivemap() {
        if (SettingsUtil.getLivemapHoldWithoutWheel(this) && wheelDisconnected) return;
        long now = SystemClock.elapsedRealtime();
        if (!updating && (currentLocation != null) && (tourKey != null) && ((now - lastUpdated) > SettingsUtil.getLivemapUpdateInterval(this) * 1000)) {
            updating = true;
            lastUpdated = now;

            final RequestParams requestParams = new RequestParams();
            requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
            requestParams.put("k", tourKey);
            requestParams.put("p", (autoStarted) ? autoStartedPublish : SettingsUtil.getLivemapPublish(this));
            requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
            requestParams.put("dt", df.format(new Date()));
            // Location data
            requestParams.put("ldt", df.format(new Date(currentLocation.getTime())));
            requestParams.put("llt", String.format(Locale.US, "%.7f", currentLocation.getLatitude()));
            requestParams.put("lln", String.format(Locale.US, "%.7f", currentLocation.getLongitude()));
            requestParams.put("lds", String.format(Locale.US, "%.3f", currentDistance / 1000.0));
            if (currentLocation.hasSpeed())     requestParams.put("lsp", String.format(Locale.US, "%.1f", currentLocation.getSpeed() * 3.6));
            if (currentLocation.hasAccuracy())  requestParams.put("lac", String.format(Locale.US, "%.1f", currentLocation.getAccuracy()));
            if (currentLocation.hasAltitude())  requestParams.put("lat", String.format(Locale.US, "%.1f", currentLocation.getAltitude()));
            if (currentLocation.hasBearing())   requestParams.put("lbg", String.format(Locale.US, "%.1f", currentLocation.getBearing()));
            // Device battery
            int deviceBattery = getDeviceBattery();
            if (deviceBattery > -1) requestParams.put("dbl", String.format(Locale.US, "%d", deviceBattery));
            // Wheel data
            if (wheelUpdated + 2000 > now && BluetoothLeService.getConnectionState() == BluetoothLeService.STATE_CONNECTED) {
                wheelDisconnected = false;
                requestParams.put("was", String.format(Locale.US, "%.1f", WheelData.getInstance().getAverageSpeedDouble()));
                requestParams.put("wbl", String.format(Locale.US, "%.1f", WheelData.getInstance().getAverageBatteryLevelDouble()));
                requestParams.put("wcu", String.format(Locale.US, "%.1f", WheelData.getInstance().getCurrentDouble()));
                requestParams.put("wds", String.format(Locale.US, "%.3f", WheelData.getInstance().getDistanceDouble()));
                requestParams.put("wpw", String.format(Locale.US, "%.1f", WheelData.getInstance().getPowerDouble()));
                requestParams.put("wsp", String.format(Locale.US, "%.1f", WheelData.getInstance().getSpeedDouble()));
                requestParams.put("wtm", String.format(Locale.US, "%.1f", WheelData.getInstance().getTemperatureDouble()));
                requestParams.put("wvt", String.format(Locale.US, "%.1f", WheelData.getInstance().getVoltageDouble()));
            }
            else
                wheelDisconnected = true;
            HttpClient.post(Constants.EUCWORLD_URL + "/api/tour/update", requestParams, new JsonHttpResponseHandler() {
                @Override
                public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                    livemapStatus = 2;
                    if (status == LivemapStatus.WAITING_FOR_GPS)
                        say(getString(R.string.livemap_speech_tour_started), "info", 1);
                    status = LivemapStatus.STARTED;
                    try {
                        int error = response.getInt("error");
                        if (error == 0) {
                            livemapStatus = 0;
                            if (response.getJSONObject("data").has("xtm")) {
                                notificationManager.notify(NOTIFY_ID, getNotification(getString(R.string.notification_livemap_title), getString(R.string.livemap_live)));
                                weatherTimestamp = SystemClock.elapsedRealtime();
                                weatherTemperature = response.getJSONObject("data").getDouble("xtm");
                                weatherTemperatureFeels = response.getJSONObject("data").getDouble("xtf");
                                weatherWindSpeed = response.getJSONObject("data").getDouble("xws");
                                weatherWindDir = response.getJSONObject("data").getDouble("xwd");
                                weatherHumidity = response.getJSONObject("data").getDouble("xhu");
                                weatherPressure = response.getJSONObject("data").getDouble("xpr");
                                weatherPrecipitation = response.getJSONObject("data").getDouble("xpc");
                                weatherVisibility = response.getJSONObject("data").getDouble("xvi");
                                weatherCloudCoverage = response.getJSONObject("data").getDouble("xcl");
                                weatherCondition = response.getJSONObject("data").getInt("xco");
                            }
                        }
                        updateDateTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                        sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                    }
                    catch (JSONException e) {
                        e.printStackTrace();
                    }
                    updating = false;
                }
                @Override
                public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                    livemapStatus = 1;
                    updating = false;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                }
                @Override
                public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, String responseString, Throwable throwable) {
                    livemapStatus = 2;
                    updating = false;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                }
            });
        }
    }

    private void startLivemap() {
        status = LivemapStatus.CONNECTING;
        JSONObject info = new JSONObject();
        try {
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("brand", Build.BRAND);
            info.put("device", Build.DEVICE);
            info.put("display", Build.DISPLAY);
            info.put("id", Build.ID);
            info.put("model", Build.MODEL);
            info.put("product", Build.PRODUCT);
            info.put("sdk", Build.VERSION.SDK_INT);
            info.put("appVersionName", BuildConfig.VERSION_NAME);
            info.put("appVersionCode", BuildConfig.VERSION_CODE);
        }
        catch (Exception e) {}
        String i = info.toString();

        final RequestParams requestParams = new RequestParams();
        requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
        requestParams.put("p", (autoStarted) ? autoStartedPublish : SettingsUtil.getLivemapPublish(this));
        requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
        requestParams.put("m", SettingsUtil.getLivemapStartNewSegment(this));
        requestParams.put("t", TimeZone.getDefault().getID());
        requestParams.put("l", String.valueOf(Locale.getDefault()));
        requestParams.put("ci", i);
        HttpClient.post(Constants.EUCWORLD_URL + "/api/tour/start", requestParams, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                int error = -1;
                try {
                    error = response.getInt("error");
                    switch (error) {
                        case 0:
                            livemapStatus = 0;
                            status = LivemapStatus.WAITING_FOR_GPS;
                            notificationManager.notify(NOTIFY_ID, getNotification(getString(R.string.notification_livemap_title), getString(R.string.livemap_gps_wait)));
                            showToast(R.string.livemap_api_connected, Toast.LENGTH_LONG);
                            tourKey = response.getJSONObject("data").getString("k");
                            url = Constants.EUCWORLD_URL + "/tour/" + tourKey;
                            break;
                        case 1:
                            showToast(R.string.livemap_api_error_general, Toast.LENGTH_LONG);
                            break;
                        case 8:
                            showToast(R.string.livemap_api_error_invalid_api_key, Toast.LENGTH_LONG);
                            break;
                        case 9:
                            showToast(R.string.livemap_api_error_accound_needs_activation, Toast.LENGTH_LONG);
                            break;
                        case 403:
                            showToast(R.string.livemap_api_error_forbidden, Toast.LENGTH_LONG);
                            break;
                        default:
                            showToast(R.string.livemap_api_error_unknown, Toast.LENGTH_LONG);
                    }
                }
                catch (JSONException e) {
                    e.printStackTrace();
                    stopSelf();
                }
                if (error != 0) {
                    livemapStatus = 2;
                    status = LivemapStatus.DISCONNECTED;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                    stopSelf();
                }
                else
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                livemapStatus = 2;
                status = LivemapStatus.DISCONNECTED;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                showToast(R.string.livemap_api_error_no_connection, Toast.LENGTH_LONG);
                stopSelf();
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, String responseString, Throwable throwable) {
                livemapStatus = 2;
                status = LivemapStatus.DISCONNECTED;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                showToast(R.string.livemap_api_error_server, Toast.LENGTH_LONG);
                stopSelf();
            }
        });
    }

    private void stopLivemap() {
        if (status != LivemapStatus.DISCONNECTING) {
            final RequestParams requestParams = new RequestParams();
            requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
            requestParams.put("k", tourKey);
            requestParams.put("p", (autoStarted) ? autoStartedPublish : SettingsUtil.getLivemapPublish(this));
            requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
            HttpClient.post(Constants.EUCWORLD_URL + "/api/tour/finish", requestParams, new JsonHttpResponseHandler() {
                @Override
                public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                    livemapStatus = -1;
                    status = LivemapStatus.DISCONNECTED;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                }
                @Override
                public void onFailure(int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                    livemapStatus = 1;
                    status = LivemapStatus.DISCONNECTED;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                }
                @Override
                public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, String responseString, Throwable throwable) {
                    livemapStatus = 1;
                    status = LivemapStatus.DISCONNECTED;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                }
            });
        }
    }

    private void pauseLivemap() {
        status = LivemapStatus.PAUSING;
        final RequestParams requestParams = new RequestParams();
        requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
        requestParams.put("k", tourKey);
        requestParams.put("p", (autoStarted) ? autoStartedPublish : SettingsUtil.getLivemapPublish(this));
        requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
        HttpClient.post(Constants.EUCWORLD_URL + "/api/tour/pause", requestParams, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                livemapStatus = 1;
                try {
                    int error = response.getInt("error");
                    if (error == 0) {
                        livemapStatus = 0;
                        status = LivemapStatus.PAUSED;
                    }
                    else
                        status = LivemapStatus.STARTED;
                }
                catch (JSONException e) { }
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                livemapStatus = 1;
                status = LivemapStatus.STARTED;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, String responseString, Throwable throwable) {
                livemapStatus = 1;
                status = LivemapStatus.STARTED;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
            }
        });
    }

    private void resumeLivemap() {
        status = LivemapStatus.RESUMING;
        final RequestParams requestParams = new RequestParams();
        requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
        requestParams.put("k", tourKey);
        requestParams.put("p", (autoStarted) ? autoStartedPublish : SettingsUtil.getLivemapPublish(this));
        requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
        HttpClient.post(Constants.EUCWORLD_URL + "/api/tour/resume", requestParams, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                livemapStatus = 1;
                try {
                    int error = response.getInt("error");
                    if (error == 0) {
                        livemapStatus = 0;
                        status = LivemapStatus.STARTED;
                    }
                    else
                        status = LivemapStatus.PAUSED;
                }
                catch (JSONException e) { }
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
            }

            @Override
            public void onFailure(int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                livemapStatus = 1;
                status = LivemapStatus.PAUSED;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, String responseString, Throwable throwable) {
                livemapStatus = 1;
                status = LivemapStatus.PAUSED;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
            }
        });
    }

    @TargetApi(21)
    private int getDeviceBattery() {
        if (batteryManager != null) {
            return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        else
            return -1;
    }

    private void showToast(int message_id, int duration) {
        Toast.makeText(this, message_id, duration).show();
    }

    public long getWeatherAge() { return (weatherTimestamp - SystemClock.elapsedRealtime()); }
    public double getWeatherTemperature() { return weatherTemperature; }
    public double getWeatherTemperatureFeels() { return weatherTemperatureFeels; }
    public String getUpdateDateTime() { return updateDateTime; }
    public String getTourKey() { return tourKey; }
    public double getLatitude() { return lastLatitude; }
    public double getLongitude() { return lastLongitude; }
    public long getLocationTime() { return lastLocationTime; }

    private void say(String text, String earcon, int priority) {
        sendBroadcast(new Intent(Constants.ACTION_SPEECH_SAY)
                .putExtra(Constants.INTENT_EXTRA_SPEECH_TEXT, text)
                .putExtra(Constants.INTENT_EXTRA_SPEECH_EARCON, earcon)
                .putExtra(Constants.INTENT_EXTRA_SPEECH_PRIORITY, priority));
    }

}
