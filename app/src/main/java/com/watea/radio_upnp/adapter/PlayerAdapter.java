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

import static android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;

import java.util.Arrays;
import java.util.List;

// Abstract player implementation that handles playing music with proper handling of headphones
// and audio focus
public abstract class PlayerAdapter implements AudioManager.OnAudioFocusChangeListener {
  protected static final String AUDIO_CONTENT_TYPE = "audio/";
  protected static final String DEFAULT_CONTENT_TYPE = AUDIO_CONTENT_TYPE + "mpeg";
  protected static final String APPLICATION_CONTENT_TYPE = "application/";
  private static final String LOG_TAG = PlayerAdapter.class.getSimpleName();
  private static final float MEDIA_VOLUME_DEFAULT = 1.0f;
  private static final float MEDIA_VOLUME_DUCK = 0.2f;
  private static final IntentFilter AUDIO_NOISY_INTENT_FILTER =
    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
  private static final List<String> AUDIO_CONTENT_PREFIXS =
    Arrays.asList(AUDIO_CONTENT_TYPE, APPLICATION_CONTENT_TYPE);
  private static final AudioAttributes PLAYBACK_ATTRIBUTES = new AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build();
  @NonNull
  protected final Context context;
  // Current tag, always set before playing
  @NonNull
  protected final String lockKey;
  @NonNull
  protected final Radio radio;
  @NonNull
  protected final Uri radioUri;
  @NonNull
  private final AudioManager audioManager;
  @NonNull
  private final Listener listener;
  @Nullable
  private final AudioFocusRequest audioFocusRequest;
  protected int state = PlaybackStateCompat.STATE_NONE;
  protected boolean isPaused = false;
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

  public PlayerAdapter(
    @NonNull Context context,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey,
    @NonNull Uri radioUri) {
    this.context = context;
    this.listener = listener;
    this.radio = radio;
    this.lockKey = lockKey;
    this.radioUri = radioUri;
    audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    if (audioManager == null) {
      Log.e(LOG_TAG, "Internal failure: audioManager is null");
    }
    audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(PLAYBACK_ATTRIBUTES)
      .setOnAudioFocusChangeListener(this, new Handler(Looper.getMainLooper()))
      .build();
  }

  @NonNull
  public static PlaybackStateCompat.Builder getPlaybackStateCompatBuilder(int state) {
    return new PlaybackStateCompat.Builder()
      .setState(state, PLAYBACK_POSITION_UNKNOWN, 1.0f, SystemClock.elapsedRealtime());
  }

  public static boolean isHandling(@NonNull String protocolInfo) {
    for (String audioContentPrefix : AUDIO_CONTENT_PREFIXS) {
      if (protocolInfo.contains(audioContentPrefix))
        return true;
    }
    return false;
  }

  // Must be called
  public synchronized final boolean prepareFromMediaId() {
    Log.d(LOG_TAG, "prepareFromMediaId " + radio.getName());
    if (isRemote() || requestAudioFocus()) {
      onPrepareFromMediaId();
      return true;
    }
    return false;
  }

  public synchronized final void play() {
    if (isRemote() || requestAudioFocus()) {
      if (!isRemote()) {
        registerAudioNoisyReceiver();
      }
      if (isAvailableAction(PlaybackStateCompat.ACTION_PLAY)) {
        isPaused = false;
        onPlay();
      }
    }
  }

  public synchronized final void pause() {
    if (!isRemote() && !playOnAudioFocus) {
      releaseAudioFocus();
    }
    if (isAvailableAction(PlaybackStateCompat.ACTION_PAUSE)) {
      isPaused = true;
      onPause();
    }
  }

  public synchronized final void stop() {
    // Stop immediately
    changeAndNotifyState(PlaybackStateCompat.STATE_STOPPED);
    if (!isRemote()) {
      releaseAudioFocus();
      unregisterAudioNoisyReceiver();
    }
    onStop();
    onRelease();
  }

  // Called when resources must be released, no impact on playback state
  public synchronized final void release() {
    if (!isRemote()) {
      releaseAudioFocus();
      unregisterAudioNoisyReceiver();
    }
    onRelease();
  }

  public void setVolume(float volume) {
  }

  public void adjustVolume(int direction) {
  }

  // Set the current capabilities available on this session
  public long getAvailableActions() {
    return PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
      PlaybackStateCompat.ACTION_STOP |
      PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
      PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
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

  // Default is unknown
  @NonNull
  public String getContentType() {
    return "";
  }

  protected abstract boolean isRemote();

  protected abstract void onPrepareFromMediaId();

  // Called when media is ready to be played and indicates the app has audio focus
  protected abstract void onPlay();

  // Called when media must be paused
  protected abstract void onPause();

  // Called when the media must be stopped. The player should clean up resources at this point
  protected abstract void onStop();

  protected abstract void onRelease();

  protected void changeAndNotifyState(int newState) {
    Log.d(LOG_TAG, "New state/lock key received: " + newState + "/" + lockKey);
    if (state == newState) {
      Log.d(LOG_TAG, "=> no change");
    } else {
      state = newState;
      listener.onPlaybackStateChange(
        getPlaybackStateCompatBuilder(state).setActions(getAvailableActions()).build(), lockKey);
    }
  }

  private boolean isPlaying() {
    return (state == PlaybackStateCompat.STATE_PLAYING);
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
    if (audioFocusRequest != null) {
      Log.d(LOG_TAG, "Audio focus released");
      audioManager.abandonAudioFocusRequest(audioFocusRequest);
    }
  }

  private boolean requestAudioFocus() {
    boolean request;
    assert audioFocusRequest != null;
    request = (AudioManager.AUDIOFOCUS_REQUEST_GRANTED ==
      audioManager.requestAudioFocus(audioFocusRequest));
    if (request) {
      Log.d(LOG_TAG, "Audio focus request succeeded");
      return true;
    }
    Log.d(LOG_TAG, "Audio focus request failed");
    return false;
  }

  private boolean isAvailableAction(long action) {
    return ((getAvailableActions() & action) > 0L);
  }

  public interface Listener {
    void onPlaybackStateChange(@NonNull PlaybackStateCompat state, @NonNull String lockKey);
  }
}