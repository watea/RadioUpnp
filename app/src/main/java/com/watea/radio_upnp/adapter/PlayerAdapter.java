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
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.RadioHandler;

import java.util.Arrays;
import java.util.List;

// Abstract player implementation that handles playing music with proper handling of headphones
// and audio focus
// Warning: not threadsafe, execution shall be done in main UI thread
@SuppressWarnings("WeakerAccess")
public abstract class PlayerAdapter implements RadioHandler.Controller {
  protected static final String AUDIO_CONTENT_TYPE = "audio/";
  protected static final String DEFAULT_CONTENT_TYPE = AUDIO_CONTENT_TYPE + "mpeg";
  protected static final String APPLICATION_CONTENT_TYPE = "application/";
  private static final String LOG_TAG = PlayerAdapter.class.getName();
  private static final float MEDIA_VOLUME_DEFAULT = 1.0f;
  private static final float MEDIA_VOLUME_DUCK = 0.2f;
  private static final IntentFilter AUDIO_NOISY_INTENT_FILTER =
    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
  private static final List<String> AUDIO_CONTENT_PREFIXS =
    Arrays.asList(AUDIO_CONTENT_TYPE, APPLICATION_CONTENT_TYPE);
  @NonNull
  protected final Context context;
  // Current tag, always set before playing
  @NonNull
  protected final String lockKey;
  @NonNull
  protected final Radio radio;
  @NonNull
  protected final Uri radioUri;
  private final AudioFocusHelper audioFocusHelper = new AudioFocusHelper();
  @NonNull
  private final AudioManager audioManager;
  @NonNull
  private final Listener listener;
  protected int state = PlaybackStateCompat.STATE_NONE;
  private boolean playOnAudioFocus = false;
  private boolean audioNoisyReceiverRegistered = false;
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
      Log.e(LOG_TAG, "AudioManager is null");
    }
  }

  public static boolean isHandling(@NonNull String protocolInfo) {
    for (String audioContentPrefix : AUDIO_CONTENT_PREFIXS) {
      if (protocolInfo.contains(audioContentPrefix))
        return true;
    }
    return false;
  }

  @Override
  public boolean isPaused() {
    return (state == PlaybackStateCompat.STATE_PAUSED);
  }

  // Must be called
  public final void prepareFromMediaId() {
    Log.d(LOG_TAG, "prepareFromMediaId " + radio.getName());
    // Audio focus management
    audioFocusHelper.abandonAudioFocus();
    unregisterAudioNoisyReceiver();
    onPrepareFromMediaId();
    // Watchdog
    new Thread(() -> {
      try {
        Thread.sleep(8000);
        if (state == PlaybackStateCompat.STATE_NONE) {
          Log.d(LOG_TAG, "onPrepareFromMediaId: watchdog fired");
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
        }
      } catch (InterruptedException interruptedException) {
        Log.e(LOG_TAG, "onPrepareFromMediaId: watchdog error");
      }
    }).start();
  }

  public final void play() {
    // Audiofocus management only in AudioFocus mode
    if (isLocal()) {
      if (audioFocusHelper.requestAudioFocus()) {
        registerAudioNoisyReceiver();
      } else {
        Log.d(LOG_TAG, "AudioFocusHelper error");
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
    // Force playback state immediately
    changeAndNotifyState(PlaybackStateCompat.STATE_STOPPED);
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

  // Set the current capabilities available on this session
  public long getAvailableActions() {
    return PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
      PlaybackStateCompat.ACTION_STOP |
      PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
      PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
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

  protected void changeAndNotifyState(int newState) {
    Log.d(LOG_TAG, "New state/lock key received: " + newState + "/" + lockKey);
    if (state == newState) {
      Log.i(LOG_TAG, "=> no change");
    } else {
      state = newState;
      listener.onPlaybackStateChange(new PlaybackStateCompat.Builder()
        .setState(state, PLAYBACK_POSITION_UNKNOWN, 1.0f, SystemClock.elapsedRealtime())
        .setActions(getAvailableActions()).build(), lockKey);
    }
  }

  private boolean isPlaying() {
    return (state == PlaybackStateCompat.STATE_PLAYING);
  }

  private void registerAudioNoisyReceiver() {
    if (!audioNoisyReceiverRegistered &&
      (context.registerReceiver(audioNoisyReceiver, AUDIO_NOISY_INTENT_FILTER) != null)) {
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
  }

  public interface Listener {
    void onPlaybackStateChange(@NonNull PlaybackStateCompat state, String lockKey);
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