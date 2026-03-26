/*
 * Copyright (c) 2018. Stephane Treuchot
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to
 * do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package com.watea.radio_upnp.adapter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.SessionDevice;

import java.util.function.Consumer;

// Player implementation that handles playing music with proper handling of headphones and audio focus
public class PlayerAdapter implements AudioManager.OnAudioFocusChangeListener {
  private static final String LOG_TAG = PlayerAdapter.class.getSimpleName();
  private static final float MEDIA_VOLUME_DEFAULT = 1.0f;
  private static final float MEDIA_VOLUME_DUCK = 0.2f;
  private static final IntentFilter AUDIO_NOISY_INTENT_FILTER = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
  private static final AudioAttributes PLAYBACK_ATTRIBUTES = new AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build();
  @NonNull
  private final Context context;
  @NonNull
  private final AudioManager audioManager;
  @NonNull
  private final AudioFocusRequest audioFocusRequest;
  @NonNull
  private final Consumer<Radio> onPlayCallback;
  @Nullable
  private SessionDevice sessionDevice = null;
  private boolean playOnAudioFocus = false;
  private final BroadcastReceiver audioNoisyReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
        pause();
      }
    }
  };
  private boolean audioNoisyReceiverRegistered = false;

  public PlayerAdapter(@NonNull Context context, @NonNull Consumer<Radio> onPlayCallback) {
    this.context = context;
    this.onPlayCallback = onPlayCallback;
    audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(PLAYBACK_ATTRIBUTES)
      .setOnAudioFocusChangeListener(this, new Handler(Looper.getMainLooper()))
      .build();
  }

  public boolean hasSessionDevice() {
    return (sessionDevice != null);
  }

  public void setSessionDevice(@NonNull SessionDevice sessionDevice) {
    this.sessionDevice = sessionDevice;
  }

  // Must be called
  public synchronized final boolean prepareFromMediaId() {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on prepareFromMediaId; no session device defined");
    } else {
      Log.d(LOG_TAG, "prepareFromMediaId on radio: " + sessionDevice.getRadio().getName());
      if (isRemote() || requestAudioFocus()) {
        onPrepareFromMediaId();
        return true;
      }
    }
    return false;
  }

  public synchronized final void play() {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on play; no session device defined");
      return;
    }
    if (isAvailableAction(PlaybackStateCompat.ACTION_PLAY)) {
      if (isRemote() || requestAudioFocus()) {
        if (!isRemote()) {
          registerAudioNoisyReceiver();
        }
        if (sessionDevice.isUpnp()) {
          onPlayCallback.accept(getRadio());
        } else {
          onPlay();
        }
      }
    } else {
      Log.e(LOG_TAG, "Internal failure on play; not allowed");
    }
  }

  public synchronized final void pause() {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on pause; no session device defined");
      return;
    }
    if (isAvailableAction(PlaybackStateCompat.ACTION_PAUSE)) {
      if (!isRemote() && !playOnAudioFocus) {
        releaseAudioFocus();
      }
      // Pause immediately
      sessionDevice.onState(PlaybackStateCompat.STATE_PAUSED);
      onPause();
    } else {
      Log.e(LOG_TAG, "Internal failure on pause; not allowed");
    }
  }

  public synchronized final void stop() {
    if (sessionDevice == null) {
      Log.d(LOG_TAG, "stop: no session device defined");
      return;
    }
    // Stop immediately
    sessionDevice.onState(PlaybackStateCompat.STATE_STOPPED);
    if (!isRemote()) {
      releaseAudioFocus();
      unregisterAudioNoisyReceiver();
    }
    onStop();
  }

  // Called when resources must be released, no impact on playback state
  public synchronized final void release() {
    if (!isRemote()) {
      releaseAudioFocus();
      unregisterAudioNoisyReceiver();
    }
    onRelease();
  }

  // Called when resources only must be released
  public synchronized final void clean() {
    onRelease();
  }

  public void setVolume(float volume) {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on setVolume; no session device defined");
    } else {
      sessionDevice.setVolume(volume);
    }
  }

  public void adjustVolume(int direction) {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on adjustVolume; no session device defined");
    } else {
      sessionDevice.adjustVolume(direction);
    }
  }

  @Override
  public void onAudioFocusChange(int focusChange) {
    Log.d(LOG_TAG, "onAudioFocusChange: " + focusChange);
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on onAudioFocusChange; no session device defined");
      return;
    }
    // No effect on remote playback
    if (isRemote()) {
      return;
    }
    switch (focusChange) {
      case AudioManager.AUDIOFOCUS_GAIN:
        if (sessionDevice.isPlaying()) {
          setVolume(MEDIA_VOLUME_DEFAULT);
        } else if (playOnAudioFocus) {
          play();
        }
        playOnAudioFocus = false;
        break;
      case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
        setVolume(MEDIA_VOLUME_DUCK);
        break;
      case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
        if (sessionDevice.isPlaying()) {
          playOnAudioFocus = true;
          pause();
        }
        break;
      case AudioManager.AUDIOFOCUS_LOSS:
        playOnAudioFocus = false;
        stop();
        break;
    }
  }

  @Nullable
  public Radio getRadio() {
    return (sessionDevice == null) ? null : sessionDevice.getRadio();
  }

  public boolean isRemote() {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on isRemote; no session device defined");
      return false;
    } else {
      return sessionDevice.isRemote();
    }
  }

  private void onPrepareFromMediaId() {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on onPrepareFromMediaId; no session device defined");
    } else {
      sessionDevice.prepareFromMediaId();
    }
  }

  // Called when media is ready to be played and indicates the app has audio focus
  private void onPlay() {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on onPlay; no session device defined");
    } else {
      sessionDevice.play();
    }
  }

  // Called when media must be paused
  private void onPause() {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on onPause; no session device defined");
    } else {
      sessionDevice.pause();
    }
  }

  // Called when the media must be stopped. The player should clean up resources at this point
  private void onStop() {
    if (sessionDevice == null) {
      Log.e(LOG_TAG, "Internal failure on onStop; no session device defined");
    } else {
      sessionDevice.stop();
    }
  }

  private void onRelease() {
    if (sessionDevice == null) {
      Log.d(LOG_TAG, "onRelease; no session device defined");
    } else {
      sessionDevice.release();
    }
  }

  private void registerAudioNoisyReceiver() {
    if (!audioNoisyReceiverRegistered) {
      context.registerReceiver(audioNoisyReceiver, AUDIO_NOISY_INTENT_FILTER);
      audioNoisyReceiverRegistered = true;
    }
  }

  private void unregisterAudioNoisyReceiver() {
    if (audioNoisyReceiverRegistered) {
      context.unregisterReceiver(audioNoisyReceiver);
      audioNoisyReceiverRegistered = false;
    }
  }

  private void releaseAudioFocus() {
    Log.d(LOG_TAG, "Audio focus released");
    audioManager.abandonAudioFocusRequest(audioFocusRequest);
  }

  private boolean requestAudioFocus() {
    final boolean request = (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioManager.requestAudioFocus(audioFocusRequest));
    Log.d(LOG_TAG, "Audio focus request " + (request ? "succeeded" : "failed"));
    return request;
  }

  private boolean isAvailableAction(long action) {
    return (sessionDevice != null) && ((sessionDevice.getAvailableActions(sessionDevice.getState()) & action) > 0L);
  }
}