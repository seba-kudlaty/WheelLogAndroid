package com.cooper.wheellog;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
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
    private static LivemapService instance = null;

    private String LivemapApiURL = "https://euc.world/api";
    private int status = 0;
    private String updateDateTime = "";
    private String tourKey;
    private long lastUpdated;
    private Location lastLocation;
    private double lastLatitude;
    private double lastLongitude;
    private long lastLocationTime;
    private Location currentLocation;
    private long wheelUpdated = 0;
    private double currentDistance;
    private LocationManager locationManager;
    private BatteryManager batteryManager;
    private SimpleDateFormat df;

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

    LocationListener locationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            currentLocation = location;
            if ((lastLocation != null) && (status != 2)) {
                lastLatitude = location.getLatitude();
                lastLongitude = location.getLongitude();
                lastLocationTime = location.getTime();
                currentDistance += lastLocation.distanceTo(currentLocation);
            }
            updateLivemap();
            lastLocation = currentLocation;
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
        instance = this;
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
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250, 0, locationListener);
        batteryManager = (BatteryManager)getSystemService(BATTERY_SERVICE);
        df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
        Intent serviceStartedIntent = new Intent(Constants.ACTION_LIVEMAP_SERVICE_TOGGLED).putExtra(Constants.INTENT_EXTRA_IS_RUNNING, true);
        sendBroadcast(serviceStartedIntent);
        startLivemap();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopLivemap();
        instance = null;
        unregisterReceiver(receiver);
        if (locationManager != null)
            locationManager.removeUpdates(locationListener);
        Intent serviceStartedIntent = new Intent(Constants.ACTION_LIVEMAP_SERVICE_TOGGLED).putExtra(Constants.INTENT_EXTRA_IS_RUNNING, false);
        sendBroadcast(serviceStartedIntent);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    private void updateLivemap() {
        long now = SystemClock.elapsedRealtime();
        if ((currentLocation != null) && (tourKey != null) && ((now - lastUpdated) > SettingsUtil.getLivemapUpdateInterval(this) * 1000)) {
            lastUpdated = now;

            final RequestParams requestParams = new RequestParams();
            requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
            requestParams.put("k", tourKey);
            requestParams.put("p", SettingsUtil.getLivemapPublish(this));
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
            if (wheelUpdated + 2000 > now) {
                requestParams.put("was", String.format(Locale.US, "%.1f", WheelData.getInstance().getAverageSpeedDouble()));
                requestParams.put("wbl", String.format(Locale.US, "%.1f", WheelData.getInstance().getAverageBatteryLevelDouble()));
                requestParams.put("wcu", String.format(Locale.US, "%.1f", WheelData.getInstance().getCurrentDouble()));
                requestParams.put("wds", String.format(Locale.US, "%.3f", WheelData.getInstance().getDistanceDouble()));
                requestParams.put("wpw", String.format(Locale.US, "%.1f", WheelData.getInstance().getPowerDouble()));
                requestParams.put("wsp", String.format(Locale.US, "%.1f", WheelData.getInstance().getSpeedDouble()));
                requestParams.put("wtm", String.format(Locale.US, "%.1f", WheelData.getInstance().getTemperatureDouble()));
                requestParams.put("wvt", String.format(Locale.US, "%.1f", WheelData.getInstance().getVoltageDouble()));
            }
            HttpClient.post(LivemapApiURL + "/tour/update", requestParams, new JsonHttpResponseHandler() {
                @Override
                public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                    try {
                        int error = response.getInt("error");
                        if ((error == 0) && (response.getJSONObject("data").has("xtm"))) {
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
                        updateDateTime = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                        Intent intent = new Intent(Constants.ACTION_LIVEMAP_STATUS)
                                .putExtra(Constants.INTENT_EXTRA_LIVEMAP_UPDATE, error);
                        sendBroadcast(intent);
                    }
                    catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
                @Override
                public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) { }
            });
        }
    }

    private void startLivemap() {
        final RequestParams requestParams = new RequestParams();
        requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
        requestParams.put("p", SettingsUtil.getLivemapPublish(this));
        requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
        requestParams.put("m", SettingsUtil.getLivemapStartNewSegment(this));
        requestParams.put("t", TimeZone.getDefault().getID());
        requestParams.put("l", String.valueOf(Locale.getDefault()));
        HttpClient.post(LivemapApiURL + "/tour/start", requestParams, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                int error = -1;
                try {
                    error = response.getInt("error");
                    switch (error) {
                        case 0:
                            status = 1;
                            showToast(R.string.livemap_api_connected, Toast.LENGTH_LONG);
                            tourKey = response.getJSONObject("data").getString("k");
                            sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_STATUS).putExtra(Constants.INTENT_EXTRA_LIVEMAP_URL, "https://euc.world/tour/" + tourKey));
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
                Intent intent = new Intent(Constants.ACTION_LIVEMAP_STATUS)
                        .putExtra(Constants.INTENT_EXTRA_LIVEMAP_START, error);
                sendBroadcast(intent);
                if (error != 0) stopSelf();
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                Intent intent = new Intent(Constants.ACTION_LIVEMAP_STATUS)
                        .putExtra(Constants.INTENT_EXTRA_LIVEMAP_START, -1);
                sendBroadcast(intent);
                showToast(R.string.livemap_api_error_no_connection, Toast.LENGTH_LONG);
                stopSelf();
            }
        });
    }

    private void stopLivemap() {
        if (status > 0) {
            final RequestParams requestParams = new RequestParams();
            requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
            requestParams.put("k", tourKey);
            requestParams.put("p", SettingsUtil.getLivemapPublish(this));
            requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
            HttpClient.post(LivemapApiURL + "/tour/finish", requestParams, new JsonHttpResponseHandler() {
                @Override
                public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                    status = 0;
                }

                @Override
                public void onFailure(int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) { }
            });
            Intent intent = new Intent(Constants.ACTION_LIVEMAP_STATUS)
                    .putExtra(Constants.INTENT_EXTRA_LIVEMAP_FINISH, 0);
            sendBroadcast(intent);
        }
    }

    private void pauseLivemap() {
        final RequestParams requestParams = new RequestParams();
        requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
        requestParams.put("k", tourKey);
        requestParams.put("p", SettingsUtil.getLivemapPublish(this));
        requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
        HttpClient.post(LivemapApiURL + "/tour/pause", requestParams, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                int error = -1;
                try {
                    error = response.getInt("error");
                    if (error == 0) status = 2;
                }
                catch (JSONException e) { }
                Intent intent = new Intent(Constants.ACTION_LIVEMAP_STATUS)
                        .putExtra(Constants.INTENT_EXTRA_LIVEMAP_PAUSE, error);
                sendBroadcast(intent);
            }
            @Override
            public void onFailure (int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                Intent intent = new Intent(Constants.ACTION_LIVEMAP_STATUS)
                        .putExtra(Constants.INTENT_EXTRA_LIVEMAP_PAUSE, -1);
                sendBroadcast(intent);
            }
        });
    }

    private void resumeLivemap() {
        final RequestParams requestParams = new RequestParams();
        requestParams.put("a", SettingsUtil.getLivemapApiKey(this));
        requestParams.put("k", tourKey);
        requestParams.put("p", SettingsUtil.getLivemapPublish(this));
        requestParams.put("i", SettingsUtil.getLivemapUpdateInterval(this));
        HttpClient.post(LivemapApiURL + "/tour/resume", requestParams, new JsonHttpResponseHandler() {
            @Override
            public void onSuccess(int statusCode, cz.msebera.android.httpclient.Header[] headers, JSONObject response) {
                int error = -1;
                try {
                    error = response.getInt("error");
                    if (error == 0) status = 1;
                } catch (JSONException e) {
                }
                Intent intent = new Intent(Constants.ACTION_LIVEMAP_STATUS)
                        .putExtra(Constants.INTENT_EXTRA_LIVEMAP_RESUME, error);
                sendBroadcast(intent);
            }

            @Override
            public void onFailure(int statusCode, cz.msebera.android.httpclient.Header[] headers, Throwable throwable, JSONObject errorResponse) {
                Intent intent = new Intent(Constants.ACTION_LIVEMAP_STATUS)
                        .putExtra(Constants.INTENT_EXTRA_LIVEMAP_RESUME, -1);
                sendBroadcast(intent);
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
    public int getStatus() { return status; }
    public String getUpdateDateTime() { return updateDateTime; }
    public String getTourKey() { return tourKey; }
    public double getLatitude() { return lastLatitude; }
    public double getLongitude() { return lastLongitude; }
    public long getLocationTime() { return lastLocationTime; }

}
