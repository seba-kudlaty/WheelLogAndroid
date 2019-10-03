package com.cooper.wheellog;

import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;

import com.cooper.wheellog.utils.Constants;
import com.cooper.wheellog.utils.SettingsUtil;

import io.flic.lib.FlicBroadcastReceiver;
import io.flic.lib.FlicButton;
import io.flic.lib.FlicManager;

public class FlicButtonBroadcastReceiver extends FlicBroadcastReceiver {

    private void performAction(int action, Context context) {
        switch (action) {
            case 1: // Horn
                int horn_mode = SettingsUtil.getFlicHornMode(context);
                if (horn_mode == 1) {
                    context.sendBroadcast(new Intent(Constants.ACTION_REQUEST_KINGSONG_HORN));
                }
                else if (horn_mode == 2) {
                    MediaPlayer mp = MediaPlayer.create(context, R.raw.bicycle_bell);
                    mp.start();
                    mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer mp) {
                            mp.release();
                        }
                    });
                }
                break;
            case 2: // Light
                context.sendBroadcast(new Intent(Constants.ACTION_REQUEST_LIGHT_TOGGLE));
                break;
            case 3: // Request voice message
                context.sendBroadcast(new Intent(Constants.ACTION_REQUEST_VOICE_REPORT));
                break;
            case 4: // Dismiss voice message
                context.sendBroadcast(new Intent(Constants.ACTION_REQUEST_VOICE_DISMISS));
                break;
            default:
                break;
        }
    }

    @Override
    protected void onRequestAppCredentials(Context context) {
        FlicManager.setAppCredentials("1aa3a357-feb8-4da2-bbe6-f69d418420c9", "cac07edc-18bf-47d5-9e6e-169c41cb00bf", "WheelLog");
    }

    @Override
    public void onButtonSingleOrDoubleClickOrHold(Context context, FlicButton button, boolean wasQueued, int timeDiff, boolean isSingleClick, boolean isDoubleClick, boolean isHold) {
        if (isSingleClick) {
            performAction(SettingsUtil.getFlicActionSingle(context), context);
        }
        else if (isDoubleClick) {
            performAction(SettingsUtil.getFlicActionDouble(context), context);
        }
        else if (isHold) {
            performAction(SettingsUtil.getFlicActionHold(context), context);
        }
    }

}