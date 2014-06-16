/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.telecomm;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemVibrator;
import android.os.Vibrator;
import android.provider.Settings;
import android.telecomm.CallState;

import java.util.LinkedList;
import java.util.List;

/**
 * Controls the ringtone player.
 */
final class Ringer extends CallsManagerListenerBase {
    private static final long[] VIBRATION_PATTERN = new long[] {
        0, // No delay before starting
        1000, // How long to vibrate
        1000, // How long to wait before vibrating again
    };

    /** Indicate that we want the pattern to repeat at the step which turns on vibration. */
    private static final int VIBRATION_PATTERN_REPEAT = 1;

    private final AsyncRingtonePlayer mRingtonePlayer = new AsyncRingtonePlayer();

    /**
     * Used to keep ordering of unanswered incoming calls. There can easily exist multiple incoming
     * calls and explicit ordering is useful for maintaining the proper state of the ringer.
     */
    private final List<Call> mRingingCalls = new LinkedList<>();

    private final CallAudioManager mCallAudioManager;
    private final CallsManager mCallsManager;
    private final InCallTonePlayer.Factory mPlayerFactory;
    private final Context mContext;
    private final Vibrator mVibrator;

    private InCallTonePlayer mCallWaitingPlayer;

    /**
     * Used to track the status of {@link #mVibrator} in the case of simultaneous incoming calls.
     */
    private boolean mIsVibrating = false;

    /** Initializes the Ringer. */
    Ringer(
            CallAudioManager callAudioManager,
            CallsManager callsManager,
            InCallTonePlayer.Factory playerFactory,
            Context context) {

        mCallAudioManager = callAudioManager;
        mCallsManager = callsManager;
        mPlayerFactory = playerFactory;
        mContext = context;
        // We don't rely on getSystemService(Context.VIBRATOR_SERVICE) to make sure this
        // vibrator object will be isolated from others.
        mVibrator = new SystemVibrator(TelecommApp.getInstance());
    }

    @Override
    public void onCallAdded(final Call call) {
        if (call.isIncoming() && call.getState() == CallState.RINGING) {
            if (mRingingCalls.contains(call)) {
                Log.wtf(this, "New ringing call is already in list of unanswered calls");
            }
            mRingingCalls.add(call);
            updateRinging();
        }
    }

    @Override
    public void onCallRemoved(Call call) {
        removeFromUnansweredCall(call);
    }

    @Override
    public void onCallStateChanged(Call call, CallState oldState, CallState newState) {
        if (newState != CallState.RINGING) {
            removeFromUnansweredCall(call);
        }
    }

    @Override
    public void onIncomingCallAnswered(Call call) {
        onRespondedToIncomingCall(call);
    }

    @Override
    public void onIncomingCallRejected(Call call, boolean rejectWithMessage, String textMessage) {
        onRespondedToIncomingCall(call);
    }

    @Override
    public void onForegroundCallChanged(Call oldForegroundCall, Call newForegroundCall) {
        if (mRingingCalls.contains(oldForegroundCall) ||
                mRingingCalls.contains(newForegroundCall)) {
            updateRinging();
        }
    }

    /**
     * Silences the ringer for any actively ringing calls.
     */
    void silence() {
        // Remove all calls from the "ringing" set and then update the ringer.
        mRingingCalls.clear();
        updateRinging();
    }

    private void onRespondedToIncomingCall(Call call) {
        // Only stop the ringer if this call is the top-most incoming call.
        if (getTopMostUnansweredCall() == call) {
            stopRinging();
            stopCallWaiting();
        }

        // We do not remove the call from mRingingCalls until the call state changes from RINGING
        // or the call is removed. see onCallStateChanged or onCallRemoved.
    }

    private Call getTopMostUnansweredCall() {
        return mRingingCalls.isEmpty() ? null : mRingingCalls.get(0);
    }

    /**
     * Removes the specified call from the list of unanswered incoming calls and updates the ringer
     * based on the new state of {@link #mRingingCalls}. Safe to call with a call that is not
     * present in the list of incoming calls.
     */
    private void removeFromUnansweredCall(Call call) {
        mRingingCalls.remove(call);
        updateRinging();
    }

    private void updateRinging() {
        if (mRingingCalls.isEmpty()) {
            stopRinging();
            stopCallWaiting();
        } else {
            startRingingOrCallWaiting();
        }
    }

    private void startRingingOrCallWaiting() {
        Call foregroundCall = mCallsManager.getForegroundCall();
        Log.v(this, "startRingingOrCallWaiting, foregroundCall: %s.", foregroundCall);

        if (mRingingCalls.contains(foregroundCall)) {
            // The foreground call is one of incoming calls so play the ringer out loud.
            stopCallWaiting();

            AudioManager audioManager =
                    (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager.getStreamVolume(AudioManager.STREAM_RING) > 0) {
                Log.v(this, "startRingingOrCallWaiting");
                mCallAudioManager.setIsRinging(true);

                // Only play ringtone if a bluetooth device is not available. When a BT device
                // is available, then we send it a signal to do its own ringtone and we dont need
                // to play the ringtone on the device.
                if (!mCallAudioManager.isBluetoothDeviceAvailable()) {
                    // Because we wait until a contact info query to complete before processing a
                    // call (for the purposes of direct-to-voicemail), the information about custom
                    // ringtones should be available by the time this code executes. We can safely
                    // request the custom ringtone from the call and expect it to be current.
                    mRingtonePlayer.play(foregroundCall.getRingtone());
                }
            } else {
                Log.v(this, "startRingingOrCallWaiting, skipping because volume is 0");
            }

            if (shouldVibrate(TelecommApp.getInstance()) && !mIsVibrating) {
                mVibrator.vibrate(VIBRATION_PATTERN, VIBRATION_PATTERN_REPEAT,
                        AudioManager.STREAM_RING);
                mIsVibrating = true;
            }
        } else {
            Log.v(this, "Playing call-waiting tone.");

            // All incoming calls are in background so play call waiting.
            stopRinging();

            if (mCallWaitingPlayer == null) {
                mCallWaitingPlayer =
                        mPlayerFactory.createPlayer(InCallTonePlayer.TONE_CALL_WAITING);
                mCallWaitingPlayer.startTone();
            }
        }
    }

    private void stopRinging() {
        Log.v(this, "stopRinging");

        mRingtonePlayer.stop();

        if (mIsVibrating) {
            mVibrator.cancel();
            mIsVibrating = false;
        }

        // Even though stop is asynchronous it's ok to update the audio manager. Things like audio
        // focus are voluntary so releasing focus too early is not detrimental.
        mCallAudioManager.setIsRinging(false);
    }

    private void stopCallWaiting() {
        Log.v(this, "stop call waiting.");
        if (mCallWaitingPlayer != null) {
            mCallWaitingPlayer.stopTone();
            mCallWaitingPlayer = null;
        }
    }

    private boolean shouldVibrate(Context context) {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();
        if (getVibrateWhenRinging(context)) {
            return ringerMode != AudioManager.RINGER_MODE_SILENT;
        } else {
            return ringerMode == AudioManager.RINGER_MODE_VIBRATE;
        }
    }

    private boolean getVibrateWhenRinging(Context context) {
        if (!mVibrator.hasVibrator()) {
            return false;
        }
        return Settings.System.getInt(context.getContentResolver(),
                Settings.System.VIBRATE_WHEN_RINGING, 0) != 0;
    }
}