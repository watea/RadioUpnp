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
import android.media.AudioManager;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.HttpServer;
import com.watea.radio_upnp.service.RadioHandler;

import java.util.List;
import java.util.Vector;

// Abstract player implementation that handles playing music with proper handling of headphones
// and audio focus
// Warning: not threadsafe, execution shall be done in main UI thread
@SuppressWarnings("WeakerAccess")
public abstract class PlayerAdapter implements RadioHandler.Listener {
  private static final String LOG_TAG = PlayerAdapter.class.getName();
  private static final float MEDIA_VOLUME_DEFAULT = 1.0f;
  private static final float MEDIA_VOLUME_DUCK = 0.2f;
  private static final IntentFilter AUDIO_NOISY_INTENT_FILTER =
    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
  @NonNull
  protected final Context context;
  @NonNull
  protected final HttpServer httpServer;
  // Current tag, always set before playing
  @NonNull
  protected final String lockKey;
  @NonNull
  protected final Radio radio;
  @NonNull
  private final AudioManager audioManager;
  @NonNull
  private final AudioFocusHelper audioFocusHelper = new AudioFocusHelper();
  @NonNull
  private final Listener listener;
  protected int state = PlaybackStateCompat.STATE_NONE;
  private boolean playOnAudioFocus = false;
  private boolean audioNoisyReceiverRegistered = false;
  @NonNull
  private final BroadcastReceiver audioNoisyReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
        if (isPlaying()) {
          pause();
        }
      }
    }
  };
  private boolean isRerunAllowed = false;

  public PlayerAdapter(
    @NonNull Context context,
    @NonNull HttpServer httpServer,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey) {
    this.context = context;
    this.listener = listener;
    this.httpServer = httpServer;
    this.radio = radio;
    this.lockKey = lockKey;
    audioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
    if (audioManager == null) {
      Log.e(LOG_TAG, "AudioManager is null");
    }
  }

  @Override
  public void onNewInformation(
    @NonNull Radio radio, @NonNull String information, @NonNull String lockKey) {
    // We add current radio information to current media data
    listener.onInformationChange(
      radio
        .getMediaMetadataBuilder()
        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, information)
        .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, information)
        .build(),
      lockKey);
  }

  // Try to relaunch, just once till Playing state received
  @Override
  public void onError(@NonNull Radio radio, @NonNull String lockKey) {
    Log.d(LOG_TAG, "RadioHandler error received");
    if (isRerunAllowed && (state == PlaybackStateCompat.STATE_PLAYING)) {
      Log.d(LOG_TAG, "=> Try to relaunch");
      changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING, lockKey);
      isRerunAllowed = false;
      onPrepareFromMediaId();
    } else {
      Log.d(LOG_TAG, "=> Error");
      changeAndNotifyState(PlaybackStateCompat.STATE_ERROR, lockKey);
    }
  }

  @Override
  public List<String> getProtocolInfos() {
    return new Vector<>();
  }

  public boolean isPlaying() {
    return (state == PlaybackStateCompat.STATE_PLAYING);
  }

  // Must be called
  public final void prepareFromMediaId() {
    Log.d(LOG_TAG, "prepareFromMediaId " + radio.getName());
    state = PlaybackStateCompat.STATE_NONE;
    isRerunAllowed = false;
    httpServer.setRadioHandlerListener(this);
    // Audio focus management
    audioFocusHelper.abandonAudioFocus();
    unregisterAudioNoisyReceiver();
    onPrepareFromMediaId();
  }

  public final void play() {
    // Audiofocus management only in AudioFocus mode
    if (isLocal()) {
      if (audioFocusHelper.requestAudioFocus()) {
        registerAudioNoisyReceiver();
      } else {
        return;
      }
    }
    if ((getAvailableActions() & PlaybackStateCompat.ACTION_PLAY) > 0) {
      onPlay();
    }
  }

  public final void pause() {
    if (!playOnAudioFocus) {
      audioFocusHelper.abandonAudioFocus();
    }
    unregisterAudioNoisyReceiver();
    if ((getAvailableActions() & PlaybackStateCompat.ACTION_PAUSE) > 0) {
      onPause();
    }
  }

  public final void stop() {
    // Force playback state immediately on current lock key
    changeAndNotifyState(PlaybackStateCompat.STATE_STOPPED, lockKey);
    // Now we can release
    releaseOwnResources();
    // Stop actual reader and release
    onStop();
    onRelease();
  }

  // Called when resources must be released, no impact on playback state
  public final void release() {
    releaseOwnResources();
    // Release actual reader
    onRelease();
  }

  public void setVolume(float volume) {
  }

  public void adjustVolume(int direction) {
  }

  protected abstract boolean isLocal();

  protected abstract void onPrepareFromMediaId();

  // Called when media is ready to be played and indicates the app has audio focus
  protected abstract void onPlay();

  // Called when media must be paused
  protected abstract void onPause();

  // Called when the media must be stopped. The player should clean up resources at this point
  protected abstract void onStop();

  protected abstract void onRelease();

  // Set the current capabilities available on this session
  protected abstract long getAvailableActions();

  protected void changeAndNotifyState(int newState) {
    changeAndNotifyState(newState, lockKey);
  }

  protected void changeAndNotifyState(int newState, @NonNull String lockKey) {
    Log.d(LOG_TAG, "New state/lock key received: " + newState + "/" + lockKey);
    if (state == newState) {
      Log.d(LOG_TAG, "=> no change");
    } else {
      state = newState;
      // Re-run allowed if "Playing" received
      isRerunAllowed = (state == PlaybackStateCompat.STATE_PLAYING);
      listener.onPlaybackStateChange(
        new PlaybackStateCompat.Builder()
          .setActions(getAvailableActions())
          .setState(state, 0, 1.0f, SystemClock.elapsedRealtime())
          .build(),
        lockKey);
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

  private void releaseOwnResources() {
    // Release audio focus
    audioFocusHelper.abandonAudioFocus();
    unregisterAudioNoisyReceiver();
    // Stop listening for RadioHandler, shall stop RadioHandler
    httpServer.setRadioHandlerListener(null);
  }

  public interface Listener {
    void onPlaybackStateChange(@NonNull PlaybackStateCompat state, String lockKey);

    void onInformationChange(@NonNull MediaMetadataCompat mediaMetadataCompat, String lockKey);
  }

  // Helper class for managing audio focus related tasks
  private final class AudioFocusHelper implements AudioManager.OnAudioFocusChangeListener {
    @Override
    public void onAudioFocusChange(int focusChange) {
      switch (focusChange) {
        case AudioManager.AUDIOFOCUS_GAIN:
          if (playOnAudioFocus && !isPlaying()) {
            play();
          } else if (isPlaying()) {
            setVolume(MEDIA_VOLUME_DEFAULT);
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
          abandonAudioFocus();
          playOnAudioFocus = false;
          stop();
          break;
      }
    }

    private boolean requestAudioFocus() {
      return (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == audioManager.requestAudioFocus(
        this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN));
    }

    private void abandonAudioFocus() {
      audioManager.abandonAudioFocus(this);
    }
  }
}