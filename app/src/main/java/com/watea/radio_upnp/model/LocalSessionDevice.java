/*
 * Copyright (c) 2018-2026. Stephane Treuchot
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

package com.watea.radio_upnp.model;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

public class LocalSessionDevice extends SessionDevice implements AudioManager.OnAudioFocusChangeListener {
  private static final String LOG_TAG = LocalSessionDevice.class.getSimpleName();
  private static final Handler HANDLER = new Handler(Looper.getMainLooper());
  private static final float MEDIA_VOLUME_DEFAULT = 1.0f;
  private static final float MEDIA_VOLUME_DUCK = 0.2f;
  private static final IntentFilter AUDIO_NOISY_INTENT_FILTER =
    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
  private static final AudioAttributes PLAYBACK_ATTRIBUTES = new AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build();
  @NonNull
  private final AudioManager audioManager;
  @NonNull
  private final AudioFocusRequest audioFocusRequest;
  private boolean audioNoisyReceiverRegistered = false;
  private boolean playOnAudioFocus = false;
  private final BroadcastReceiver audioNoisyReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
        pause();
      }
    }
  };

  public LocalSessionDevice(
    @NonNull Context context,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey) {
    super(context, Mode.LOCAL, listener, radio, lockKey);
    audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(PLAYBACK_ATTRIBUTES)
      .setOnAudioFocusChangeListener(this, HANDLER)
      .build();
  }

  @Override
  public boolean isRemote() {
    return false;
  }

  @Override
  public void adjustVolume(int direction) {
  }

  @Override
  public void play() {
    restartExoPlayer();
  }

  @Override
  public void pause() {
    if (!isPlaying()) {
      return;
    }
    if (!playOnAudioFocus) {
      releaseAudioFocus();
    }
    onState(State.PAUSED);
    super.pause();
  }

  @Override
  public void stop() {
    releaseAudioFocus();
    unregisterAudioNoisyReceiver();
    super.stop();
  }

  @Override
  public void release() {
    releaseAudioFocus();
    unregisterAudioNoisyReceiver();
    super.release();
  }

  @Override
  public void onAudioFocusChange(int focusChange) {
    Log.d(LOG_TAG, "onAudioFocusChange: " + focusChange);
    switch (focusChange) {
      case AudioManager.AUDIOFOCUS_GAIN:
        if (isPlaying()) {
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
        if (isPlaying()) {
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

  @Override
  protected void setVolume(float volume) {
    exoPlayer.setVolume(volume);
  }

  // Runs on main thread — audio focus requested here for thread safety
  @Override
  protected void startExoPlayer() {
    if (requestAudioFocus()) {
      registerAudioNoisyReceiver();
      super.startExoPlayer();
    } else {
      onState(State.ERROR);
    }
  }

  @NonNull
  @Override
  protected Player.Listener getPlayerListener() {
    return new PlayerListener() {
      @Override
      public void onPlaybackStateChanged(int playbackState) {
        Log.d(LOG_TAG, "onPlaybackStateChanged: state = " + playbackState);
        switch (playbackState) {
          case ExoPlayer.STATE_BUFFERING:
            onState(State.BUFFERING);
            break;
          case ExoPlayer.STATE_READY:
            break;
          case ExoPlayer.STATE_IDLE:
          case ExoPlayer.STATE_ENDED:
            onState(State.ERROR);
            break;
          default:
            Log.e(LOG_TAG, "onPlaybackStateChanged: bad state = " + playbackState);
            onState(State.ERROR);
        }
      }

      @Override
      public void onIsPlayingChanged(boolean isPlaying) {
        if (exoPlayer.getPlaybackState() == ExoPlayer.STATE_READY) {
          onState(isPlaying ? State.PLAYING : State.PAUSED);
        }
      }
    };
  }

  private boolean requestAudioFocus() {
    final boolean granted = (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioManager.requestAudioFocus(audioFocusRequest));
    Log.d(LOG_TAG, "Audio focus request " + (granted ? "succeeded" : "failed"));
    return granted;
  }

  private void releaseAudioFocus() {
    Log.d(LOG_TAG, "Audio focus released");
    audioManager.abandonAudioFocusRequest(audioFocusRequest);
  }

  private void registerAudioNoisyReceiver() {
    if (!audioNoisyReceiverRegistered) {
      context.registerReceiver(audioNoisyReceiver, AUDIO_NOISY_INTENT_FILTER);
      audioNoisyReceiverRegistered = true;
    }
  }

  private void unregisterAudioNoisyReceiver() {
    if (audioNoisyReceiverRegistered) {
      try {
        context.unregisterReceiver(audioNoisyReceiver);
      } catch (IllegalArgumentException ignored) {
      }
      audioNoisyReceiverRegistered = false;
    }
  }
}