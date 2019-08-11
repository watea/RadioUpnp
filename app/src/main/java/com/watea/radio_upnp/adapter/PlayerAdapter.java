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
import android.support.annotation.Nullable;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.HttpServer;
import com.watea.radio_upnp.service.RadioHandler;

// Abstract player implementation that handles playing music with proper handling of headphones
// and audio focus
@SuppressWarnings("WeakerAccess")
public abstract class PlayerAdapter {
  private static final String LOG_TAG = PlayerAdapter.class.getName();
  private static final float MEDIA_VOLUME_DEFAULT = 1.0f;
  private static final float MEDIA_VOLUME_DUCK = 0.2f;
  private static final IntentFilter AUDIO_NOISY_INTENT_FILTER =
    new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
  @NonNull
  protected final Context mContext;
  @NonNull
  protected final RadioHandler mRadioHandler;
  @NonNull
  protected final HttpServer mHttpServer;
  private final boolean mIsLocal;
  @NonNull
  private final AudioManager mAudioManager;
  @NonNull
  private final AudioFocusHelper mAudioFocusHelper;
  @NonNull
  private final Listener mListener;
  @Nullable
  protected Radio mRadio;
  protected int mState;
  // Only one radio listened at a time
  // Used as process tag by implementations, as a new one is created for each new
  // actual renderer
  @Nullable
  private RadioHandler.Listener mRadioHandlerListener;
  private boolean mPlayOnAudioFocus;
  private boolean mAudioNoisyReceiverRegistered;
  @NonNull
  private final BroadcastReceiver mAudioNoisyReceiver = new BroadcastReceiver() {
    @Override
    public void onReceive(Context context, Intent intent) {
      if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
        if (isPlaying()) {
          pause();
        }
      }
    }
  };
  private boolean mIsRerunTryed;
  @NonNull
  private String mCurrentRadioInformation;

  public PlayerAdapter(
    @NonNull Context context,
    @NonNull HttpServer httpServer,
    @NonNull Listener listener,
    boolean isLocal) {
    mContext = context.getApplicationContext();
    mHttpServer = httpServer;
    mRadioHandler = mHttpServer.getRadioHandler();
    mListener = listener;
    mIsLocal = isLocal;
    AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
    if (audioManager == null) {
      throw new RuntimeException();
    } else {
      mAudioManager = audioManager;
    }
    mAudioFocusHelper = new AudioFocusHelper();
    mRadio = null;
    mRadioHandlerListener = null;
    mCurrentRadioInformation = "";
    mState = PlaybackStateCompat.STATE_NONE;
    mPlayOnAudioFocus = false;
    mAudioNoisyReceiverRegistered = false;
  }

  public boolean isPlaying() {
    return (mState == PlaybackStateCompat.STATE_PLAYING);
  }

  // Must be called
  public synchronized final void prepareFromMediaId(@NonNull Radio radio) {
    Log.d(LOG_TAG, "prepareFromMediaId " + radio.getName());
    mRadio = radio;
    mCurrentRadioInformation = "";
    mState = PlaybackStateCompat.STATE_NONE;
    // Audio focus management
    mAudioFocusHelper.abandonAudioFocus();
    unregisterAudioNoisyReceiver();
    mIsRerunTryed = false;
    // New listener for each actual renderer
    mRadioHandlerListener = new RadioHandler.Listener() {
      @Override
      public void onNewInformation(@NonNull String information) {
        mCurrentRadioInformation = information;
        mListener.onInformationChange(getCurrentMedia());
      }

      // Try to relaunch, just once till Playing state received
      @Override
      public void onError() {
        Log.d(LOG_TAG, "RadioHandler error received");
        if (mIsRerunTryed || (mState != PlaybackStateCompat.STATE_PLAYING)) {
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR, this);
        } else {
          Log.d(LOG_TAG, "Try to relaunch remote reader");
          mIsRerunTryed = true;
          onPrepareFromMediaId();
        }
      }
    };
    // Listen for RadioHandler
    mRadioHandler.setListener(mRadioHandlerListener);
    onPrepareFromMediaId();
  }

  public synchronized final void play() {
    // Audiofocus management only in AudioFocus mode
    if (mIsLocal) {
      if (mAudioFocusHelper.requestAudioFocus()) {
        registerAudioNoisyReceiver();
      } else {
        return;
      }
    }
    if ((getAvailableActions() & PlaybackStateCompat.ACTION_PLAY) > 0) {
      onPlay();
    }
  }

  public synchronized final void pause() {
    if (!mPlayOnAudioFocus) {
      mAudioFocusHelper.abandonAudioFocus();
    }
    unregisterAudioNoisyReceiver();
    if ((getAvailableActions() & PlaybackStateCompat.ACTION_PAUSE) > 0) {
      onPause();
    }
  }

  public synchronized final void stop() {
    // Force playback state
    changeAndNotifyState(PlaybackStateCompat.STATE_STOPPED, mRadioHandlerListener);
    // Now we can release
    releaseOwnResources();
    // Stop actual reader
    onStop();
  }

  // Called when resources must be released, no impact on playback state
  public synchronized final void release() {
    releaseOwnResources();
    // Release actual reader
    onRelease();
  }

  public void setVolume(float volume) {
  }

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

  // Transmit state only for current process, just one time
  protected synchronized void changeAndNotifyState(int newState, @Nullable Object lockKey) {
    Log.d(LOG_TAG, "New state received: " + newState);
    if ((lockKey == mRadioHandlerListener) && (mState != newState)) {
      Log.d(LOG_TAG, "=> new state transmitted to listener");
      // Re-run allowed if "Playing" received
      if (mState == PlaybackStateCompat.STATE_PLAYING) {
        mIsRerunTryed = false;
      }
      mState = newState;
      mListener.onPlaybackStateChange(
        new PlaybackStateCompat.Builder()
          .setActions(getAvailableActions())
          .setState(mState, 0, 1.0f, SystemClock.elapsedRealtime())
          .build(),
        getCurrentMedia());
    }
  }

  @Nullable
  protected Object getLockKey() {
    return mRadioHandlerListener;
  }

  // We add current radio information to current media data
  @Nullable
  private synchronized MediaMetadataCompat getCurrentMedia() {
    return (mRadio == null) ? null : mRadio
      .getMediaMetadataBuilder()
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, mCurrentRadioInformation)
      .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, mCurrentRadioInformation)
      .build();
  }

  private void registerAudioNoisyReceiver() {
    if (!mAudioNoisyReceiverRegistered) {
      mContext.registerReceiver(mAudioNoisyReceiver, AUDIO_NOISY_INTENT_FILTER);
      mAudioNoisyReceiverRegistered = true;
    }
  }

  private void unregisterAudioNoisyReceiver() {
    if (mAudioNoisyReceiverRegistered) {
      mContext.unregisterReceiver(mAudioNoisyReceiver);
      mAudioNoisyReceiverRegistered = false;
    }
  }

  private synchronized void releaseOwnResources() {
    // Release audio focus
    mAudioFocusHelper.abandonAudioFocus();
    unregisterAudioNoisyReceiver();
    // Stop listening for RadioHandler, shall stop RadioHandler
    mRadioHandler.removeListener();
    mRadioHandlerListener = null;
  }

  public interface Listener {
    void onPlaybackStateChange(
      @NonNull PlaybackStateCompat state, @Nullable MediaMetadataCompat mediaMetadataCompat);

    void onInformationChange(@Nullable MediaMetadataCompat mediaMetadataCompat);
  }

  // Helper class for managing audio focus related tasks
  private final class AudioFocusHelper implements AudioManager.OnAudioFocusChangeListener {
    @Override
    public void onAudioFocusChange(int focusChange) {
      switch (focusChange) {
        case AudioManager.AUDIOFOCUS_GAIN:
          if (mPlayOnAudioFocus && !isPlaying()) {
            play();
          } else if (isPlaying()) {
            setVolume(MEDIA_VOLUME_DEFAULT);
          }
          mPlayOnAudioFocus = false;
          break;
        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
          setVolume(MEDIA_VOLUME_DUCK);
          break;
        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
          if (isPlaying()) {
            mPlayOnAudioFocus = true;
            pause();
          }
          break;
        case AudioManager.AUDIOFOCUS_LOSS:
          mAudioManager.abandonAudioFocus(this);
          mPlayOnAudioFocus = false;
          stop();
          break;
      }
    }

    private boolean requestAudioFocus() {
      return
        (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == mAudioManager.requestAudioFocus(
          this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN));
    }

    private void abandonAudioFocus() {
      mAudioManager.abandonAudioFocus(this);
    }
  }
}