package com.cooper.wheellog;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothGattService;
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
import android.util.Log;
import android.widget.Toast;

import com.cooper.wheellog.utils.Constants;
import com.cooper.wheellog.utils.HttpClient;
import com.cooper.wheellog.utils.PermissionsUtil;
import com.cooper.wheellog.utils.SettingsUtil;
import com.loopj.android.http.JsonHttpResponseHandler;
import com.loopj.android.http.RequestParams;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

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
    private static int livemapError = -1;
    private static boolean livemapGPS = false;
    private static double currentDistance;
    private static double currentSpeed;
    private static double topSpeed;
    private static int ridingTime;
    private static int rideTime;
    private static final int NOTIFY_ID = 36901;
    private static final String CHANNEL_ID = "chan_wl_livemap";

    private Timer timer;
    private NotificationManager notificationManager;
    private String updateDateTime = "";
    private String tourKey;
    private long lastUpdated;
    private long lastGPS;
    private Location lastLocation;
    private double lastLatitude;
    private double lastLongitude;
    private long lastLocationTime;
    private Location currentLocation;
    private long tourStartInitiated = 0;
    private LocationManager locationManager;
    private BatteryManager batteryManager;
    private SimpleDateFormat df;
    private int updateTimer = 0;

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
    private int weatherConditionCode;

    public static boolean isInstanceCreated() {
        return instance != null;
    }
    public static LivemapService getInstance() { return instance; }
    public static double getDistance() { return currentDistance / 1000; }
    public static double getAverageSpeed() { return (rideTime > 59) ? getDistance() / ((double)rideTime / 3600) : 0; }
    public static double getAverageRidingSpeed() { return (ridingTime > 59) ? getDistance() / ((double)ridingTime / 3600) : 0; }
    public static double getSpeed() { return (livemapGPS) ? currentSpeed : 0; }
    public static double getTopSpeed() { return topSpeed; }
    public static int getRideTime() { return rideTime; }
    public static int getRidingTime() { return ridingTime; }
    public static String getRideTimeString() {
        long hours = TimeUnit.SECONDS.toHours(rideTime);
        long minutes = TimeUnit.SECONDS.toMinutes(rideTime) - TimeUnit.HOURS.toMinutes(TimeUnit.SECONDS.toHours(rideTime));
        long seconds = TimeUnit.SECONDS.toSeconds(rideTime) - TimeUnit.MINUTES.toSeconds(TimeUnit.SECONDS.toMinutes(rideTime));
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }
    public static String getRidingTimeString() {
        long hours = TimeUnit.SECONDS.toHours(ridingTime);
        long minutes = TimeUnit.SECONDS.toMinutes(ridingTime) - TimeUnit.HOURS.toMinutes(TimeUnit.SECONDS.toHours(ridingTime));
        long seconds = TimeUnit.SECONDS.toSeconds(ridingTime) - TimeUnit.MINUTES.toSeconds(TimeUnit.SECONDS.toMinutes(ridingTime));
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }
    public static LivemapStatus getStatus() { return status; }
    public static String getUrl() { return url; }
    public static boolean getAutoStarted() { return autoStarted; }
    public static void setAutoStarted(boolean b) { autoStarted = b; }
    public static int getLivemapError() { return livemapError; }
    public static boolean getLivemapGPS() { return livemapGPS; }
    public static boolean isConnected() { return status.ordinal() >= LivemapStatus.CONNECTING.ordinal() && status.ordinal() < LivemapStatus.DISCONNECTING.ordinal(); }

    LocationListener locationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            if (!livemapGPS) {
                livemapGPS = true;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                if (lastLocation != null && SettingsUtil.getSpeechGPSBTStatus(getApplicationContext()))
                    say(getString(R.string.livemap_speech_gps_signal_regained), "info", 1);
            }
            lastGPS = SystemClock.elapsedRealtime();
            currentLocation = location;
            currentSpeed = location.getSpeed() * 3.6f;
            if (topSpeed < currentSpeed) topSpeed = currentSpeed;
            lastLocationTime = location.getTime();
            lastLatitude = location.getLatitude();
            lastLongitude = location.getLongitude();
            if (lastLocation != null) {
                if (currentLocation.getSpeed() * 3.6f >= Constants.MIN_RIDING_SPEED || lastLocation.distanceTo(currentLocation) >= location.getAccuracy()) {
                    currentDistance += lastLocation.distanceTo(currentLocation);
                    lastLocation = currentLocation;
                }
            }
            else
                lastLocation = currentLocation;
            sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_LOCATION_UPDATED));
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
            if (action != null) {
                switch (action) {
                    case Constants.ACTION_LIVEMAP_PAUSE:
                        pauseLivemap();
                        break;
                    case Constants.ACTION_LIVEMAP_RESUME:
                        resumeLivemap();
                        break;
                    case Constants.ACTION_LIVEMAP_TOGGLE:
                        if (status == LivemapStatus.STARTED)
                            pauseLivemap();
                        if (status == LivemapStatus.PAUSED)
                            resumeLivemap();
                        if (status == LivemapStatus.DISCONNECTED)
                            startLivemap();
                        break;
                }
            }
        }
    };

    private void startTimer() {
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                long now = SystemClock.elapsedRealtime();
                // Decrement API update timer
                if (updateTimer > 0) --updateTimer;
                // Toggle GPS signal lost
                if (livemapGPS && now - lastGPS > Constants.GPS_DATA_VALIDITY) {
                    livemapGPS = false;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                    if (SettingsUtil.getSpeechGPSBTStatus(getApplicationContext()))
                        say(getString(R.string.livemap_speech_gps_signal_lost), "warning1", 1);
                }
                // If enabled in settings, finish unpaused tour after wheel connection is lost
                if (status != LivemapStatus.DISCONNECTED &&
                        status != LivemapStatus.PAUSED &&
                        SettingsUtil.getLivemapAutoFinish(getApplicationContext()) &&
                        WheelData.getInstance().getDataAge() > SettingsUtil.getLivemapAutoFinishDelay(getApplicationContext()) * 1000 &&
                        tourStartInitiated + SettingsUtil.getLivemapAutoFinishDelay(getApplicationContext()) * 1000 < now)
                    stop();
                // Ride & riding time
                ++rideTime;
                if (livemapGPS & currentSpeed >= Constants.MIN_RIDING_SPEED) ++ridingTime;
            }
        };
        timer = new Timer();
        timer.scheduleAtFixedRate(timerTask, 1000, 1000);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_LIVEMAP_PAUSE);
        intentFilter.addAction(Constants.ACTION_LIVEMAP_RESUME);
        intentFilter.addAction(Constants.ACTION_LIVEMAP_TOGGLE);
        registerReceiver(receiver, intentFilter);
        if (!PermissionsUtil.checkLocationPermission(this)) {
            showToast(R.string.livemap_error_no_location_permission, Toast.LENGTH_LONG);
            stop();
            return START_STICKY;
        }
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if ((locationManager == null) || (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))) {
            locationManager = null;
            showToast(R.string.livemap_error_no_gps_provider, Toast.LENGTH_LONG);
            stop();
            return START_STICKY;
        }
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
        sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_SERVICE_TOGGLED).putExtra(Constants.INTENT_EXTRA_IS_RUNNING, true));
        startLivemap();
        startTimer();
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        startForeground(NOTIFY_ID, getNotification(getString(R.string.notification_livemap_title), getString(R.string.livemap_connecting)));
        instance = this;
        livemapGPS = false;
        livemapError = 0;
        currentDistance = 0;
        currentSpeed = 0;
        topSpeed = 0;
        ridingTime = 0;
        rideTime = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) batteryManager = (BatteryManager)getSystemService(BATTERY_SERVICE);
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
        unregisterReceiver(receiver);
        timer.cancel();
        stopLivemap();
        autoStarted = false;
        if (locationManager != null)
            locationManager.removeUpdates(locationListener);
        livemapGPS = false;
        Intent serviceStartedIntent = new Intent(Constants.ACTION_LIVEMAP_SERVICE_TOGGLED).putExtra(Constants.INTENT_EXTRA_IS_RUNNING, false);
        sendBroadcast(serviceStartedIntent);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.deleteNotificationChannel(CHANNEL_ID);
        }
        super.onDestroy();
        currentSpeed = 0;
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

    private void stop() {
        stopForeground(true);
        stopSelf();
    }

    private void updateLivemap() {
        long now = SystemClock.elapsedRealtime();
        if (isConnected() && updateTimer == 0 && tourKey != null && now - lastUpdated > SettingsUtil.getLivemapUpdateInterval(this) * 1000) {
            updateTimer = Constants.LIVEMAP_UPDATE_TIMEOUT;
            lastUpdated = now;

            final RequestParams requestParams = new RequestParams();
            requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
            requestParams.put("k", tourKey);
            requestParams.put("p", (autoStarted) ? autoStartedPublish : SettingsUtil.getLivemapPublish(this));
            requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
            requestParams.put("dt", df.format(new Date()));
            // Weather timestamp
            requestParams.put("xts", weatherTimestamp);
            // Location data
            requestParams.put("ldt", df.format(new Date(currentLocation.getTime())));
            requestParams.put("llt", String.format(Locale.US, "%.7f", currentLocation.getLatitude()));
            requestParams.put("lln", String.format(Locale.US, "%.7f", currentLocation.getLongitude()));
            requestParams.put("lds", String.format(Locale.US, "%.3f", currentDistance / 1000.0));
            requestParams.put("lsa", String.format(Locale.US, "%.1f", getAverageSpeed()));
            requestParams.put("lsr", String.format(Locale.US, "%.1f", getAverageRidingSpeed()));
            requestParams.put("lst", String.format(Locale.US, "%.1f", getTopSpeed()));
            requestParams.put("lrt", String.format(Locale.US, "%d", getRideTime()));
            requestParams.put("lrr", String.format(Locale.US, "%d", getRidingTime()));
            requestParams.put("lsp", String.format(Locale.US, "%.1f", currentLocation.getSpeed() * 3.6f));
            requestParams.put("lac", String.format(Locale.US, "%.1f", currentLocation.getAccuracy()));
            requestParams.put("lat", String.format(Locale.US, "%.1f", currentLocation.getAltitude()));
            requestParams.put("lbg", String.format(Locale.US, "%.1f", currentLocation.getBearing()));
            // Device battery
            int deviceBattery = getDeviceBattery();
            if (deviceBattery > -1) requestParams.put("dbl", String.format(Locale.US, "%d", deviceBattery));
            // Wheel data
            if (WheelData.getInstance().isConnected()) {
                requestParams.put("was", String.format(Locale.US, "%.1f", WheelData.getInstance().getAverageSpeedDouble()));
                requestParams.put("wbl", String.format(Locale.US, "%.1f", WheelData.getInstance().getAverageBatteryLevelDouble()));
                requestParams.put("wcu", String.format(Locale.US, "%.1f", WheelData.getInstance().getAverageCurrentDouble()));
                requestParams.put("wds", String.format(Locale.US, "%.3f", WheelData.getInstance().getDistanceDouble()));
                requestParams.put("wpw", String.format(Locale.US, "%.1f", WheelData.getInstance().getAveragePowerDouble()));
                requestParams.put("wsp", String.format(Locale.US, "%.1f", WheelData.getInstance().getSpeedDouble()));
                requestParams.put("wsa", String.format(Locale.US, "%.1f", WheelData.getInstance().getAverageSpeedDouble()));
                requestParams.put("wsr", String.format(Locale.US, "%.1f", WheelData.getInstance().getAverageRidingSpeedDouble()));
                requestParams.put("wst", String.format(Locale.US, "%.1f", WheelData.getInstance().getTopSpeedDouble()));
                requestParams.put("wtm", String.format(Locale.US, "%.1f", WheelData.getInstance().getTemperatureDouble()));
                requestParams.put("wvt", String.format(Locale.US, "%.1f", WheelData.getInstance().getAverageVoltageDouble()));
                requestParams.put("wrt", String.format(Locale.US, "%d", WheelData.getInstance().getRideTime()));
                requestParams.put("wrr", String.format(Locale.US, "%d", WheelData.getInstance().getRidingTime()));
            }
            Log.d("", requestParams.toString());
            HttpClient.post(Constants.getEucWorldUrl() + "/api/tour/update", requestParams, new JsonHttpResponseHandler() {
                @Override
                public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                    Log.d("", response.toString());


                    livemapError = 2;
                    if (status == LivemapStatus.WAITING_FOR_GPS) {
                        say(getString(R.string.livemap_speech_tour_started), "info", 1);
                        status = LivemapStatus.STARTED;
                    }
                    try {
                        int error = response.getInt("error");
                        if (error == 0) {
                            livemapError = 0;
                            updateDateTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                            notificationManager.notify(NOTIFY_ID, getNotification(getString(R.string.notification_livemap_title), getString(R.string.livemap_live)));
                            if (response.has("data") && response.getJSONObject("data").has("xtm")) {
                                weatherTemperature = response.getJSONObject("data").getDouble("xtm");
                                weatherTemperatureFeels = response.getJSONObject("data").getDouble("xtf");
                                weatherWindSpeed = response.getJSONObject("data").getDouble("xws");
                                weatherWindDir = response.getJSONObject("data").getDouble("xwd");
                                weatherHumidity = response.getJSONObject("data").getDouble("xhu");
                                weatherPressure = response.getJSONObject("data").getDouble("xpr");
                                weatherPrecipitation = response.getJSONObject("data").getDouble("xpc");
                                weatherVisibility = response.getJSONObject("data").getDouble("xvi");
                                weatherCloudCoverage = response.getJSONObject("data").getDouble("xcl");
                                weatherConditionCode = response.getJSONObject("data").getInt("xco");
                                weatherTimestamp = response.getJSONObject("data").getInt("xts");
                            }
                        }
                        sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                    }
                    catch (JSONException e) {
                        e.printStackTrace();
                    }
                    updateTimer = 0;
                }
                @Override
                public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                    livemapError = 1;
                    updateTimer = 0;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                }
                @Override
                public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, String responseString, Throwable throwable) {
                    livemapError = 2;
                    updateTimer = 0;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                }
            });
        }
    }

    private void startLivemap() {
        tourStartInitiated = SystemClock.elapsedRealtime();
        status = LivemapStatus.CONNECTING;
        JSONObject info = new JSONObject();
        try {
            /*

                Basic device, EUC & app data collected
                for diagnostic, support and further development

             */
            // Device data
            info.put("manufacturer", Build.MANUFACTURER);
            info.put("brand", Build.BRAND);
            info.put("device", Build.DEVICE);
            info.put("display", Build.DISPLAY);
            info.put("id", Build.ID);
            info.put("model", Build.MODEL);
            info.put("product", Build.PRODUCT);
            info.put("sdk", Build.VERSION.SDK_INT);
            // Application data
            info.put("appVersionName", BuildConfig.VERSION_NAME);
            info.put("appVersionCode", BuildConfig.VERSION_CODE);
            // EUC data
            info.put("eucSerial", WheelData.getInstance().getSerial());
            info.put("eucModel", WheelData.getInstance().getModel());
            info.put("eucType", WheelData.getInstance().getWheelType().toString());
            info.put("eucType", WheelData.getInstance().getWheelType().toString());
            if (BluetoothLeService.isInstanceCreated()) {
                // EUC Bluetooth data
                info.put("eucBluetoothAddress", BluetoothLeService.getInstance().getBluetoothDeviceAddress());
                if (BluetoothLeService.getInstance().getSupportedGattServices() != null) {
                    JSONArray array = new JSONArray();
                    for (BluetoothGattService service: BluetoothLeService.getInstance().getSupportedGattServices())
                        array.put(service.getUuid().toString());
                    info.put("eucBluetoothServices", array);
                }
            }
        }
        catch (Exception e) {}
        String i = info.toString();

        final RequestParams requestParams = new RequestParams();
        requestParams.put("api", Constants.LIVEMAP_API_VERSION);
        requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
        requestParams.put("p", (autoStarted) ? autoStartedPublish : SettingsUtil.getLivemapPublish(this));
        requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
        requestParams.put("m", SettingsUtil.getLivemapStartNewSegment(this));
        requestParams.put("t", TimeZone.getDefault().getID());
        requestParams.put("l", String.valueOf(Locale.getDefault()));
        requestParams.put("ci", i);
        HttpClient.post(Constants.getEucWorldUrl() + "/api/tour/start", requestParams, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                int error = -1;
                try {
                    error = response.getInt("error");
                    switch (error) {
                        case 0:
                            livemapError = 0;
                            status = LivemapStatus.WAITING_FOR_GPS;
                            notificationManager.notify(NOTIFY_ID, getNotification(getString(R.string.notification_livemap_title), getString(R.string.livemap_gps_wait)));
                            showToast(R.string.livemap_api_connected, Toast.LENGTH_LONG);
                            tourKey = response.getJSONObject("data").getString("k");
                            url = Constants.getEucWorldUrl() + "/tour/" + tourKey;
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
                    stop();
                }
                if (error != 0) {
                    livemapError = 2;
                    status = LivemapStatus.DISCONNECTED;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                    stop();
                }
                else
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                livemapError = 2;
                status = LivemapStatus.DISCONNECTED;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                showToast(R.string.livemap_api_error_no_connection, Toast.LENGTH_LONG);
                stop();
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, String responseString, Throwable throwable) {
                livemapError = 2;
                status = LivemapStatus.DISCONNECTED;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                showToast(R.string.livemap_api_error_server, Toast.LENGTH_LONG);
                stop();
            }
        });
    }

    private void stopLivemap() {
        if (status != LivemapStatus.DISCONNECTED) {
            final RequestParams requestParams = new RequestParams();
            requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
            requestParams.put("k", tourKey);
            requestParams.put("p", (autoStarted) ? autoStartedPublish : SettingsUtil.getLivemapPublish(this));
            requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
            HttpClient.post(Constants.getEucWorldUrl() + "/api/tour/finish", requestParams, new JsonHttpResponseHandler() {
                @Override
                public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                    if (status.ordinal() > LivemapStatus.WAITING_FOR_GPS.ordinal())
                        say(getString(R.string.livemap_speech_tour_finished), "info", 1);
                    livemapError = -1;
                    status = LivemapStatus.DISCONNECTED;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                }
                @Override
                public void onFailure(int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                    livemapError = 1;
                    status = LivemapStatus.DISCONNECTED;
                    sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
                }
                @Override
                public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, String responseString, Throwable throwable) {
                    livemapError = 1;
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
        HttpClient.post(Constants.getEucWorldUrl() + "/api/tour/pause", requestParams, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                livemapError = 1;
                try {
                    int error = response.getInt("error");
                    if (error == 0) {
                        livemapError = 0;
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
                livemapError = 1;
                status = LivemapStatus.STARTED;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, String responseString, Throwable throwable) {
                livemapError = 1;
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
        HttpClient.post(Constants.getEucWorldUrl() + "/api/tour/resume", requestParams, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                livemapError = 1;
                try {
                    int error = response.getInt("error");
                    if (error == 0) {
                        livemapError = 0;
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
                livemapError = 1;
                status = LivemapStatus.PAUSED;
                sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS));
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, String responseString, Throwable throwable) {
                livemapError = 1;
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

    public long getWeatherTimestamp() { return weatherTimestamp; }
    public double getWeatherTemperature() { return weatherTemperature; }
    public double getWeatherTemperatureFeels() { return weatherTemperatureFeels; }
    public int getWeatherConditionCode() { return weatherConditionCode; }
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
