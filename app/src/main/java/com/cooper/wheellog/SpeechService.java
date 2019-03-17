package com.cooper.wheellog;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.cooper.wheellog.utils.Constants;
import com.cooper.wheellog.utils.SettingsUtil;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class SpeechService extends Service implements TextToSpeech.OnInitListener {

    private int sayCount = 0;
    private boolean sayPrivileged = false;
    private static SpeechService instance = null;
    private TextToSpeech tts;
    private AudioManager am;
    private boolean ttsEnabled = false;
    private long ttsLastWheelData = 0;
    private BatteryManager bm;

    public static boolean isInstanceCreated() {
        return instance != null;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            String action = intent.getAction();
            switch (action) {
                case Constants.ACTION_BLUETOOTH_CONNECTION_STATE:
                    int connectionState = intent.getIntExtra(Constants.INTENT_EXTRA_CONNECTION_STATE, BluetoothLeService.STATE_DISCONNECTED);
                    if (connectionState == BluetoothLeService.STATE_CONNECTED) {
                        say("Connected.", "info");
                    }
                    else
                    if (connectionState == BluetoothLeService.STATE_CONNECTING) {
                        if (ttsLastWheelData > 0)
                            say("Connection lost.", "warning1");
                    }
                    break;
                case Constants.ACTION_WHEEL_DATA_AVAILABLE:
                    if (WheelData.getInstance().isCurrentAlarmActive())
                        sayNow("Current! Too high!", "alarm");
                    else
                    if (WheelData.getInstance().isTemperatureAlarmActive())
                        sayNow("Temperature! Too high!", "alarm");
                    else
                    if (WheelData.getInstance().isSpeedAlarm1Active())
                        sayNow("Slow down!", "warning3");
                    else
                    if (WheelData.getInstance().isSpeedAlarm2Active())
                        sayNow("Slow down!", "warning2");
                    else
                    if (WheelData.getInstance().isSpeedAlarm3Active())
                        sayNow("Slow down!", "warning1");
                    sayWheelData();
                    break;
                case Constants.ACTION_SPEECH_SAY:
                    String text = intent.getStringExtra(Constants.INTENT_EXTRA_SPEECH_TEXT);
                    String earcon = intent.getStringExtra(Constants.INTENT_EXTRA_SPEECH_EARCON);
                    boolean now = intent.getBooleanExtra(Constants.INTENT_EXTRA_SPEECH_NOW, false);
                    say(text, earcon, now);
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
        tts = new TextToSpeech(this, this);
        am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
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
                    if (sayCount == 0) {
                        sayPrivileged = false;
                        am.abandonAudioFocus(afl);
                    }
                    Log.d("", String.format(Locale.US, "UtteranceProgressListener: onDone(%s), sayCount = %d", utterance_id, sayCount));
                }
            });
            int result = tts.setLanguage(Locale.US);
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.addEarcon("alarm", getPackageName(), R.raw.alarm);
                tts.addEarcon("info", getPackageName(), R.raw.info);
                tts.addEarcon("time", getPackageName(), R.raw.time);
                tts.addEarcon("warning1", getPackageName(), R.raw.warning_1);
                tts.addEarcon("warning2", getPackageName(), R.raw.warning_2);
                tts.addEarcon("warning3", getPackageName(), R.raw.warning_3);
                ttsEnabled = true;

                say("Welcome on board! " + WheelData.getInstance().getRidingTimeHumanReadable(), "info");
            }
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
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

    private void say(String text, String earcon, boolean privileged) {
        if (ttsEnabled && !sayPrivileged) {
            setPitch(SettingsUtil.getSpeechPitch(this));
            setRate(SettingsUtil.getSpeechRate(this));
            int res = am.requestAudioFocus(afl, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                if (privileged) sayCount = 0;
                if (earcon != "") {
                    res = tts.playEarcon(earcon, (privileged) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "");
                    res += tts.speak(text, TextToSpeech.QUEUE_ADD, null, "");
                }
                else
                    res = tts.speak(text, (privileged) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "");

                if (res == TextToSpeech.SUCCESS) {
                    sayPrivileged = privileged;
                    sayCount++;
                }
            }
        }
    }

    private void say(String text) {
        say(text, "", false);
    }

    private void say(String text, String earcon) {
        say(text, earcon, false);
    }

    private void sayNow(String text) {
        say(text, "", true);
    }

    private void sayNow(String text, String earcon) {
        say(text, earcon, true);
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

    private int getPhoneBatteryLevel() {
        if (bm != null) {
            return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        else
            return -1;
    }

    private void sayWheelData() {
        String text = "";
        long now = SystemClock.elapsedRealtime();
        if (!sayPrivileged && (now - ttsLastWheelData) > SettingsUtil.getSpeechMsgInterval(this) * 1000) {

            ttsLastWheelData = now;

            if (!SettingsUtil.getSpeechOnlyInMotion(this) || WheelData.getInstance().getSpeedDouble() >= 3) {

                // Speed
                if (SettingsUtil.getSpeechMessagesSpeed(this)) {
                    if (WheelData.getInstance().getSpeedDouble() >= 3.0d) {
                        text += String.format(Locale.US, "Speed - %s. ", formatSpeed(WheelData.getInstance().getSpeedDouble()));
                    }
                }

                // Average speed
                if (SettingsUtil.getSpeechMessagesAvgSpeed(this)) {
                    if (WheelData.getInstance().getAverageSpeedDouble() >= 3.0d) {
                        text += String.format(Locale.US, "Average speed - %s. ", formatSpeed(WheelData.getInstance().getAverageSpeedDouble()));
                    }
                }

                // Distance
                if (SettingsUtil.getSpeechMessagesDistance(this)) {
                    String distance = formatDistance(WheelData.getInstance().getDistanceDouble());
                    if (!distance.equals(""))
                        text += String.format(Locale.US, "Distance - %s. ", distance);
                }

                // Battery level
                if (SettingsUtil.getSpeechMessagesBattery(this))
                    text += String.format(Locale.US, "Battery - %.0f%%. ", WheelData.getInstance().getAverageBatteryLevelDouble());

                // Phone battery level
                if (SettingsUtil.getSpeechMessagesPhoneBattery(this)) {
                    int bl = getPhoneBatteryLevel();
                    if (bl > 0)
                        text += String.format(Locale.US, "Phone battery - %d%%. ", bl);
                }
                // Voltage
                if (SettingsUtil.getSpeechMessagesVoltage(this))
                    text += String.format(Locale.US, "Voltage - %.1f volts. ", WheelData.getInstance().getVoltageDouble());

                // Current
                if (SettingsUtil.getSpeechMessagesCurrent(this))
                    text += String.format(Locale.US, "Current - %.0f amperes. ", WheelData.getInstance().getCurrentDouble());

                // Power
                if (SettingsUtil.getSpeechMessagesPower(this))
                    text += String.format(Locale.US, "Power - %.0f watts. ", WheelData.getInstance().getPowerDouble());

                // Temperature
                if (SettingsUtil.getSpeechMessagesTemperature(this))
                    text += String.format(Locale.US, "Temperature - %s. ", formatTemperature(WheelData.getInstance().getTemperature()));

                // Time
                if (SettingsUtil.getSpeechMessagesTime(this)) {
                    SimpleDateFormat df = new SimpleDateFormat("HH:mm", Locale.US);
                    String time = df.format(new Date());
                    text += String.format(Locale.US, "Time - %s. ", time);
                }

                // Time from start
                if (SettingsUtil.getSpeechMessagesTimeFromStart(this)) {
                    String timefromstart = WheelData.getInstance().getRideTimeHumanReadable();
                    if (!timefromstart.equals(""))
                        text += String.format(Locale.US, "%s from start. ", timefromstart);
                }

                // Time in motion
                if (SettingsUtil.getSpeechMessagesTimeInMotion(this)) {
                    String timeinmotion = WheelData.getInstance().getRidingTimeHumanReadable();
                    if (!timeinmotion.equals(""))
                        text += String.format(Locale.US, "%s in motion. ", timeinmotion);
                }

            }

            if (!text.equals(""))
                say(text, "info");

            if (SettingsUtil.getSpeechMessagesBatteryLowLevel(this) > 0 && WheelData.getInstance().getAverageBatteryLevelDouble() < SettingsUtil.getSpeechMessagesBatteryLowLevel(this))
                say("Low battery!", "warning1");

            if (SettingsUtil.getSpeechMessagesPhoneBatteryLowLevel(this) > 0 && getPhoneBatteryLevel() < SettingsUtil.getSpeechMessagesPhoneBatteryLowLevel(this))
                say("Low phone battery!", "warning1");

        }

    }

    private String formatSpeed(double speed) {
        return (SettingsUtil.isUseMiles(this)) ? String.format(Locale.US, "%.0fmi/h", speed / 1.609) : String.format(Locale.US, "%.0fkm/h", speed);
    }

    private String formatTemperature(double temperature) {
        return (SettingsUtil.isUseF(this)) ? String.format(Locale.US, "%.0f°F", temperature * 1.8 + 32) : String.format(Locale.US, "%.0f°C", temperature);
    }

    private String formatDistance(double distance) {
        if (SettingsUtil.isUseMiles(this)) {
            int miles = (int) (distance / 1.609);
            int yds100 = (int) (17.6 * ((distance / 1.609) - miles));
            if (miles > 0 || yds100 > 0) {
                String res = "";
                if (miles == 1)
                    res = "1 mile";
                else
                if (miles > 1)
                    res = String.format(Locale.US, "%d miles", miles);
                if (yds100 > 0) {
                    if (!res.equals(""))
                        res += " and ";
                    int yds = 100 * yds100;
                    res += String.format(Locale.US,"%d yards", yds);
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
                    res = "1 kilometer";
                else if (kms > 1)
                    res = String.format(Locale.US, "%d kilometers", kms);
                if (mtrs100 > 0) {
                    if (!res.equals(""))
                        res += " and ";
                    int mtrs = 100 * mtrs100;
                    res += String.format(Locale.US, "%d meters", mtrs);
                }
                return res;
            } else
                return "";
        }
    }

}
