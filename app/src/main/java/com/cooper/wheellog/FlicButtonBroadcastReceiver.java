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

    public static enum FlicAction {
        NONE,
        HORN,
        LIGHT,
        TOGGLE_TRACKING,
        LOCK,
        UNLOCK,
        REQUEST_VOICE_MESSAGE,
        DISMISS_VOICE_MESSAGE
    }

    private void performAction(FlicAction action, Context context) {
        switch (action) {
            case HORN: // Horn
                HornMode horn_mode = SettingsUtil.getFlicHornMode(context);
                if (horn_mode == HornMode.KINGSONG) {
                    context.sendBroadcast(new Intent(Constants.ACTION_REQUEST_KINGSONG_HORN));
                }
                else if (horn_mode == HornMode.SYSTEM) {
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
            case LIGHT: // Light
                context.sendBroadcast(new Intent(Constants.ACTION_REQUEST_LIGHT_TOGGLE));
                break;
            case TOGGLE_TRACKING:
                context.sendBroadcast(new Intent(Constants.ACTION_LIVEMAP_TOGGLE));
                break;
            case LOCK:
                context.sendBroadcast(new Intent(Constants.ACTION_REQUEST_KINGSONG_LOCK));
                break;
            case UNLOCK:
                context.sendBroadcast(new Intent(Constants.ACTION_REQUEST_KINGSONG_UNLOCK));
                break;
            case REQUEST_VOICE_MESSAGE: // Request voice message
                context.sendBroadcast(new Intent(Constants.ACTION_REQUEST_VOICE_REPORT));
                break;
            case DISMISS_VOICE_MESSAGE: // Dismiss voice message
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