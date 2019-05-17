package com.cooper.wheellog;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.cooper.wheellog.utils.Constants;
import com.cooper.wheellog.utils.SettingsUtil;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

public class SpeechService extends Service implements TextToSpeech.OnInitListener {

    private int sayCount = 0;
    private int sayPriority = -1;
    private static SpeechService instance = null;
    private TextToSpeech tts;
    private AudioManager am;
    private BatteryManager bm;
    private boolean ttsWheelConnected = false;
    private boolean ttsEnabled = false;
    private long ttsLastWheelData = 0;
    private double ttsLastWheelDistance = -999;
    private long ttsLastDisconnected = 0;
    private long ttsWheelDataAge = 0;
    private HashMap<String, String> ttsMap;
    private Timer timer;

    public static boolean isInstanceCreated() {
        return instance != null;
    }

    private void timerStart() {
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                long now = SystemClock.elapsedRealtime();
                // Wheel connection lost/stale warning
                if (BluetoothLeService.getConnectionState() > BluetoothLeService.STATE_DISCONNECTED &&  (now - ttsLastDisconnected) >= 5000 && (now - ttsWheelDataAge) >= 2000) {
                    if (ttsWheelConnected) {
                        ttsWheelConnected = false;
                        say(getString(R.string.speech_text_connection_lost), "warning1");
                    }
                    else {
                        ttsLastDisconnected = now;
                        say("", "warning1", 0);
                    }
                }
            }
        };
        timer = new Timer();
        timer.scheduleAtFixedRate(timerTask, 0, 100);
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            switch (action) {
                case Constants.ACTION_BLUETOOTH_CONNECTION_STATE:
                    int connectionState = intent.getIntExtra(Constants.INTENT_EXTRA_CONNECTION_STATE, BluetoothLeService.STATE_DISCONNECTED);
                    switch (connectionState) {
                        case BluetoothLeService.STATE_CONNECTED:
                            break;
                        case BluetoothLeService.STATE_DISCONNECTED:
                            ttsLastDisconnected = 0;
                            ttsLastWheelData = 0;
                            ttsWheelDataAge = 0;
                            ttsWheelConnected = false;
                            break;
                        case BluetoothLeService.STATE_CONNECTING:
                            break;
                    }
                    break;
                case Constants.ACTION_WHEEL_DATA_AVAILABLE:
                    ttsWheelDataAge = SystemClock.elapsedRealtime();
                    if (!ttsWheelConnected) {
                        ttsWheelConnected = true;
                        say(getString(R.string.speech_text_connected), "info");
                    }
                    if (WheelData.getInstance().isCurrentAlarmActive())
                        say(getString(R.string.speech_text_current_too_high), "alarm", 5);
                    else
                    if (WheelData.getInstance().isTemperatureAlarmActive())
                        say(getString(R.string.speech_text_temp_too_high), "alarm", 5);
                    else
                    if (WheelData.getInstance().isSpeedAlarm1Active())
                        say(getString(R.string.speech_text_slow_down_3), "warning3", 4);
                    else
                    if (WheelData.getInstance().isSpeedAlarm2Active())
                        say(getString(R.string.speech_text_slow_down_2), "warning2", 3);
                    else
                    if (WheelData.getInstance().isSpeedAlarm3Active())
                        say(getString(R.string.speech_text_slow_down_1), "warning1", 2);
                    sayWheelData();
                    break;
                case Constants.ACTION_SPEECH_SAY:
                    String text = intent.getStringExtra(Constants.INTENT_EXTRA_SPEECH_TEXT);
                    String earcon = intent.getStringExtra(Constants.INTENT_EXTRA_SPEECH_EARCON);
                    int priority = intent.getIntExtra(Constants.INTENT_EXTRA_SPEECH_PRIORITY, 1);
                    say(text, earcon, priority);
                    break;
            }
        }
    };

    private final AudioManager.OnAudioFocusChangeListener afl = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) { }
    };

    @Override
    public void onCreate() {
        ttsLastWheelData = 0;
        ttsLastWheelDistance = -999;
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
        tts = new TextToSpeech(this, this);
        ttsMap = new HashMap<>();
        ttsMap.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "WheelLogSpeech");
        timerStart();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        instance = this;
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.ACTION_WHEEL_DATA_AVAILABLE);
        intentFilter.addAction(Constants.ACTION_BLUETOOTH_CONNECTION_STATE);
        intentFilter.addAction(Constants.ACTION_SPEECH_SAY);
        registerReceiver(receiver, intentFilter);
        Intent serviceStartedIntent = new Intent(Constants.ACTION_SPEECH_SERVICE_TOGGLED).putExtra(Constants.INTENT_EXTRA_IS_RUNNING, true);
        sendBroadcast(serviceStartedIntent);
        return START_STICKY;
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String s) {
                    Log.d("", String.format(Locale.US, "UtteranceProgressListener: onStart(%s), sayCount = %d", s, sayCount));
                }
                @Override
                public void onError(String s) {
                    Log.d("", String.format(Locale.US, "UtteranceProgressListener: onError(%s)", s));
                }
                @Override
                public void onDone(String utterance_id) {
                    --sayCount;
                    if (sayCount <= 0) {
                        sayCount = 0;
                        if (sayPriority > 0) am.abandonAudioFocus(afl);
                        sayPriority = -1;
                    }
                    Log.d("", String.format(Locale.US, "UtteranceProgressListener: onDone(%s), sayCount = %d", utterance_id, sayCount));
                }
            });
            int result = tts.setLanguage(Locale.getDefault());
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.addEarcon("alarm", getPackageName(), R.raw.alarm);
                tts.addEarcon("info", getPackageName(), R.raw.info);
                tts.addEarcon("time", getPackageName(), R.raw.time);
                tts.addEarcon("warning1", getPackageName(), R.raw.warning_1);
                tts.addEarcon("warning2", getPackageName(), R.raw.warning_2);
                tts.addEarcon("warning3", getPackageName(), R.raw.warning_3);
                ttsEnabled = true;

                say(getString(R.string.speech_text_welcome_on_board), "info");
            }
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        timer.cancel();
        unregisterReceiver(receiver);
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
        Intent serviceStartedIntent = new Intent(Constants.ACTION_SPEECH_SERVICE_TOGGLED).putExtra(Constants.INTENT_EXTRA_IS_RUNNING, false);
        sendBroadcast(serviceStartedIntent);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    private void say(String text, String earcon, int priority) {
        if (ttsEnabled && (priority > sayPriority)) {
            setPitch(SettingsUtil.getSpeechPitch(this));
            setRate(SettingsUtil.getSpeechRate(this));
            int res = (priority > 0) ? am.requestAudioFocus(afl, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) : AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                if (priority > 0) sayCount = 0;
                if (!earcon.isEmpty()) {
                    sayCount += 1;
                    tts.playEarcon(earcon, (priority > 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, ttsMap);
                    if (!text.equals("")) {
                        sayCount += 1;
                        tts.speak(text, TextToSpeech.QUEUE_ADD, ttsMap);
                    }
                }
                else {
                    if (!text.equals("")) {
                        sayCount += 1;
                        tts.speak(text, (priority > 0) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, ttsMap);
                    }
                }
                sayPriority = priority;
            }
        }
    }

    private void say(String text) {
        say(text, "", 1);
    }

    private void say(String text, String earcon) {
        say(text, earcon, 1);
    }

    private void sayNow(String text) {
        say(text, "", 2);
    }

    private void sayNow(String text, String earcon) {
        say(text, earcon, 2);
    }

    private void setPitch(int pitch) {
        if (ttsEnabled) {
            if (pitch == 0)
                tts.setPitch(0.75f);
            else if (pitch == 1)
                tts.setPitch(1.0f);
            else if (pitch == 2)
                tts.setPitch(1.5f);
        }
    }

    private void setRate(int rate) {
        if (ttsEnabled) {
            if (rate == 0)
                tts.setSpeechRate(0.75f);
            else if (rate == 1)
                tts.setSpeechRate(1.0f);
            else if (rate == 2)
                tts.setSpeechRate(1.5f);
        }
    }

    @TargetApi(21)
    private int getPhoneBatteryLevel() {
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) && (bm != null)) {
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        else
            return -1;
    }

    private void sayWheelData() {
        long now = SystemClock.elapsedRealtime();
        double dist = (SettingsUtil.isUseMiles(this)) ? WheelData.getInstance().getDistanceDouble() / 1.609 : WheelData.getInstance().getDistanceDouble();

        if (sayPriority != -1) return;  // Wait for other messages to finish playing
        if (SettingsUtil.getSpeechOnlyInMotion(this) && WheelData.getInstance().getSpeedDouble() < 3.0d) return; // If activated in settings, hold messages when speed < 3 km/h
        if (SettingsUtil.getSpeechMsgMode(this) == 0) {
            if (now - ttsLastWheelData < SettingsUtil.getSpeechMsgInterval(this) * 1000) return;
        }
        else {
            double d = (double)SettingsUtil.getSpeechMsgInterval(this) / 60;
            if (dist < ttsLastWheelDistance + d) return;
        }

        String text = "";
        ttsLastWheelData = now;
        ttsLastWheelDistance = dist;
        if (!SettingsUtil.getSpeechOnlyInMotion(this) || WheelData.getInstance().getSpeedDouble() >= 3) {

            // Speed
            if (SettingsUtil.getSpeechMessagesSpeed(this)) {
                if (WheelData.getInstance().getSpeedDouble() >= 3.0d) {
                    text += " " + String.format(Locale.US, getString(R.string.speech_text_speed), formatSpeed(WheelData.getInstance().getSpeedDouble()));
                }
            }

            // Average speed
            if (SettingsUtil.getSpeechMessagesAvgSpeed(this)) {
                if (WheelData.getInstance().getAverageSpeedDouble() >= 3.0d) {
                    text += " " + String.format(Locale.US, getString(R.string.speech_text_avg_speed), formatSpeed(WheelData.getInstance().getAverageSpeedDouble()));
                }
            }

            // Distance
            if (SettingsUtil.getSpeechMessagesDistance(this)) {
                double f = (WheelData.getInstance().isKS18L()) ? 0.82 : 1.0;
                String distance = formatDistance(WheelData.getInstance().getDistanceDouble() * f);
                if (!distance.equals(""))
                    text += " " + String.format(Locale.US, getString(R.string.speech_text_distance), distance);
            }

            // Battery level
            if (SettingsUtil.getSpeechMessagesBattery(this))
                text += " " + String.format(Locale.US, getString(R.string.speech_text_battery), WheelData.getInstance().getAverageBatteryLevelDouble());

            // Phone battery level
            if (SettingsUtil.getSpeechMessagesPhoneBattery(this)) {
                int bl = getPhoneBatteryLevel();
                if (bl >= 0)
                    text += " " + String.format(Locale.US, getString(R.string.speech_phone_battery), bl);
            }
            // Voltage
            if (SettingsUtil.getSpeechMessagesVoltage(this))
                text += " " + String.format(Locale.US, getString(R.string.speech_text_voltage), WheelData.getInstance().getVoltageDouble());

            // Current
            if (SettingsUtil.getSpeechMessagesCurrent(this))
                text += " " + String.format(Locale.US, getString(R.string.speech_text_current), WheelData.getInstance().getCurrentDouble());

            // Power
            if (SettingsUtil.getSpeechMessagesPower(this))
                text += " " + String.format(Locale.US, getString(R.string.speech_text_power), WheelData.getInstance().getPowerDouble());

            // Temperature
            if (SettingsUtil.getSpeechMessagesTemperature(this))
                text += " " + String.format(Locale.US, getString(R.string.speech_text_temperature), formatTemperature(WheelData.getInstance().getTemperature()));

            // Time
            if (SettingsUtil.getSpeechMessagesTime(this)) {
                SimpleDateFormat df = new SimpleDateFormat("HH:mm", Locale.US);
                String time = df.format(new Date());
                text += " " + String.format(Locale.US, getString(R.string.speech_text_time), time);
            }

            // Time from start
            if (SettingsUtil.getSpeechMessagesTimeFromStart(this)) {
                String timefromstart = formatDuration(WheelData.getInstance().getRideTime());
                if (!timefromstart.equals(""))
                    text += " " + String.format(Locale.US, getString(R.string.speech_text_time_from_start), timefromstart);
            }

            // Time in motion
            if (SettingsUtil.getSpeechMessagesTimeInMotion(this)) {
                String timeinmotion = formatDuration(WheelData.getInstance().getRidingTime());
                if (!timeinmotion.equals(""))
                    text += " " + String.format(Locale.US, getString(R.string.speech_text_time_in_motion), timeinmotion);
            }

            // Weather
            if (SettingsUtil.getSpeechMessagesWeather(this) && LivemapService.isInstanceCreated()) {
                if (LivemapService.getInstance().getWeatherAge() < 2000 * SettingsUtil.getLivemapUpdateInterval(this)) {
                    String temp = formatTemperature(LivemapService.getInstance().getWeatherTemperature());
                    String tempf = formatTemperature(LivemapService.getInstance().getWeatherTemperatureFeels());
                    text += " " + getString(R.string.speech_text_weather);
                    if (temp.equals(tempf))
                        text += " " + String.format(Locale.US, getString(R.string.speech_text_weather_temp), temp);
                    else
                        text += " " + String.format(Locale.US, getString(R.string.speech_text_weather_temp_feels), temp, tempf);
                }
            }

        }

        if (!text.equals(""))
            say(text, "info");

        if (SettingsUtil.getSpeechMessagesBatteryLowLevel(this) > 0 && WheelData.getInstance().getAverageBatteryLevelDouble() < SettingsUtil.getSpeechMessagesBatteryLowLevel(this))
            say(getString(R.string.speech_text_low_battery), "warning1");

        if (SettingsUtil.getSpeechMessagesPhoneBatteryLowLevel(this) > 0 && getPhoneBatteryLevel() < SettingsUtil.getSpeechMessagesPhoneBatteryLowLevel(this))
            say(getString(R.string.speech_text_low_phone_battery), "warning1");

    }

    private String formatSpeed(double speed) {
        return (SettingsUtil.isUseMiles(this)) ? String.format(Locale.US, "%.0f mi/h", speed / 1.609) : String.format(Locale.US, "%.0f km/h", speed);
    }

    private String formatTemperature(double temperature) {
        return (SettingsUtil.isUseF(this)) ? String.format(Locale.US, "%.0f °F", temperature * 1.8 + 32) : String.format(Locale.US, "%.0f °C", temperature);
    }

    private String formatDistance(double distance) {
        if (SettingsUtil.isUseMiles(this)) {
            int miles = (int) (distance / 1.609);
            int yds100 = (int) (17.6 * ((distance / 1.609) - miles));
            if (miles > 0 || yds100 > 0) {
                String res = "";
                if (miles == 1)
                    res = "1 mi";
                else
                if (miles > 1)
                    res = String.format(Locale.US, "%d mi", miles);
                if (yds100 > 0) {
                    if (!res.equals(""))
                        res += ", ";
                    int yds = 100 * yds100;
                    res += String.format(Locale.US,"%d yd", yds);
                }
                return res;
            }
            else
                return "";
        }
        else {
            int kms = (int) distance;
            int mtrs100 = (int) (10 * (distance - kms));
            if (kms > 0 || mtrs100 > 0) {
                String res = "";
                if (kms == 1)
                    res = "1 km";
                else if (kms > 1)
                    res = String.format(Locale.US, "%d km", kms);
                if (mtrs100 > 0) {
                    if (!res.equals(""))
                        res += ", ";
                    int mtrs = 100 * mtrs100;
                    res += String.format(Locale.US, "%d m", mtrs);
                }
                return res;
            } else
                return "";
        }
    }

    private String formatDuration(long duration) {
        String text = "";
        if (duration > 0) {
            long hours = TimeUnit.SECONDS.toHours(duration);
            long minutes = TimeUnit.SECONDS.toMinutes(duration) - TimeUnit.HOURS.toMinutes(TimeUnit.SECONDS.toHours(duration));
            if ((hours > 0) || (minutes > 0)) {
                if (hours == 0)
                    text = String.format(Locale.US, getString(R.string.duration_fmt_min), minutes);
                else
                if (minutes == 0)
                    text = String.format(Locale.US, getString(R.string.duration_fmt_hr), hours);
                else
                    text = String.format(Locale.US, getString(R.string.duration_fmt_hr_and_min), hours, minutes);
            }
        }
        return text;
    }

}
