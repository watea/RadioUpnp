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

package com.watea.radio_upnp.model;

import static android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataRenderer;
import androidx.media3.extractor.metadata.icy.IcyInfo;

import com.watea.radio_upnp.service.CapturingAudioSink;

@OptIn(markerClass = UnstableApi.class)
public abstract class SessionDevice {
  private static final String LOG_TAG = SessionDevice.class.getSimpleName();
  @NonNull
  protected final Context context;
  @NonNull
  protected final ExoPlayer exoPlayer;
  @NonNull
  protected final String lockKey; // Current tag
  @NonNull
  protected final Radio radio;
  @NonNull
  protected final Listener listener;
  @NonNull
  private final ConnectionSet.Supplier connectionSetSupplier;
  @NonNull
  private final Player.Listener playerListener;
  @Nullable
  protected ConnectionSet connectionSet = null;

  public SessionDevice(
    @NonNull Context context,
    @Nullable CapturingAudioSink.Callback capturingAudioSinkCallback,
    @NonNull ConnectionSet.Supplier connectionSetSupplier,
    @NonNull Listener listener,
    @NonNull String lockKey,
    @NonNull Radio radio) {
    this.context = context;
    this.connectionSetSupplier = connectionSetSupplier;
    this.playerListener = getPlayerListener();
    this.listener = listener;
    this.lockKey = lockKey;
    this.radio = radio;
    this.exoPlayer = getExoPlayer(this.context, capturingAudioSinkCallback, this.lockKey);
  }

  @NonNull
  public static String getStateName(int state) {
    switch (state) {
      case PlaybackStateCompat.STATE_PLAYING:
        return "PLAYING";
      case PlaybackStateCompat.STATE_PAUSED:
        return "PAUSED";
      case PlaybackStateCompat.STATE_BUFFERING:
        return "BUFFERING";
      case PlaybackStateCompat.STATE_STOPPED:
        return "STOPPED";
      case PlaybackStateCompat.STATE_ERROR:
        return "ERROR";
      default:
        return "UNKNOWN";
    }
  }

  @NonNull
  public static PlaybackStateCompat.Builder getPlaybackStateCompatBuilder(int state) {
    return new PlaybackStateCompat.Builder().setState(state, PLAYBACK_POSITION_UNKNOWN, 1.0f, SystemClock.elapsedRealtime());
  }

  public abstract boolean isRemote();

  public abstract boolean isUpnp();

  public abstract void setVolume(float volume);

  public abstract void adjustVolume(int direction);

  @NonNull
  public Radio getRadio() {
    return radio;
  }

  public int getState() {
    return listener.getPlaybackState();
  }

  public boolean isPlaying() {
    return (getState() == PlaybackStateCompat.STATE_PLAYING);
  }

  public boolean isPaused() {
    return (getState() == PlaybackStateCompat.STATE_PAUSED);
  }

  public void play() {
    exoPlayer.play();
  }

  public void pause() {
    // UPnP session shall be relaunched after pause, so we stop it
    if (isUpnp()) {
      exoPlayer.stop();
    } else {
      exoPlayer.pause();
    }
  }

  public void stop() {
    exoPlayer.stop();
  }

  // Must be called in its own thread.
  // Fires ERROR if upstream connection failed.
  public boolean prepareFromMediaId() {
    connectionSet = connectionSetSupplier.get(radio.getURL(), lockKey);
    if (connectionSet == null) {
      Log.d(LOG_TAG, "prepareFromMediaId: unable to connect");
      onState(PlaybackStateCompat.STATE_ERROR);
      return false;
    }
    // Post ExoPlayer calls to the main thread
    new Handler(Looper.getMainLooper()).post(() -> {
      exoPlayer.addListener(playerListener);
      exoPlayer.setMediaItem(MediaItem.fromUri(connectionSet.getUrl().toString()));
      exoPlayer.prepare();
      exoPlayer.setPlayWhenReady(true);
    });
    return true;
  }

  public void onState(int state) {
    Log.d(LOG_TAG, "onState: " + getStateName(state) + "/" + lockKey);
    listener.onPlaybackStateChange(
      getPlaybackStateCompatBuilder(state).setActions(getAvailableActions(state)).build(),
      lockKey);
  }

  public void release() {
    exoPlayer.removeListener(playerListener);
    exoPlayer.release();
  }

  // Set the current capabilities available on this session
  public long getAvailableActions(int state) {
    long availableActions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
      PlaybackStateCompat.ACTION_STOP |
      PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
      PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
    switch (state) {
      case PlaybackStateCompat.STATE_PLAYING:
        availableActions |= PlaybackStateCompat.ACTION_PAUSE;
        break;
      case PlaybackStateCompat.STATE_PAUSED:
      case PlaybackStateCompat.STATE_BUFFERING:
        availableActions |= PlaybackStateCompat.ACTION_PLAY;
        break;
      case PlaybackStateCompat.STATE_ERROR:
        availableActions |= PlaybackStateCompat.ACTION_REWIND;
        break;
      default:
        // Nothing else
    }
    return availableActions;
  }

  @NonNull
  protected Player.Listener getPlayerListener() {
    return new PlayerListener();
  }

  @NonNull
  protected AudioSink getAudioSink(
    @NonNull Context context,
    @Nullable CapturingAudioSink.Callback capturingAudioSinkCallback,
    @NonNull String lockKey) {
    final CapturingAudioSink capturingAudioSink = new CapturingAudioSink(new DefaultAudioSink.Builder(context).build(), lockKey);
    if (capturingAudioSinkCallback != null) {
      capturingAudioSink.setCallback(capturingAudioSinkCallback);
    }
    return capturingAudioSink;
  }

  @NonNull
  private ExoPlayer getExoPlayer(
    @NonNull Context context,
    @Nullable CapturingAudioSink.Callback capturingAudioSinkCallback,
    @NonNull String lockKey) {
    return new ExoPlayer.Builder(context)
      .setRenderersFactory(
        (handler,
         videoListener,
         audioListener,
         textOutput,
         metadataOutput) -> new Renderer[]{
          new MediaCodecAudioRenderer(
            context,
            MediaCodecSelector.DEFAULT,
            handler,
            audioListener,
            getAudioSink(context, capturingAudioSinkCallback, lockKey)),
          new MetadataRenderer(metadataOutput, handler.getLooper())
        })
      .build();
  }

  public interface Listener {
    void onPlaybackStateChange(@NonNull PlaybackStateCompat state, @NonNull String lockKey);

    void onNewInformation(@NonNull String information, @NonNull String lockKey);

    void onNewBitrate(int bitrate, @NonNull String mimeType, @NonNull String lockKey);

    int getPlaybackState();
  }

  protected class PlayerListener implements Player.Listener {
    @Override
    public void onMetadata(@NonNull Metadata metadata) {
      String title = null;
      for (int i = 0; i < metadata.length(); i++) {
        final Metadata.Entry entry = metadata.get(i);
        if (entry instanceof IcyInfo) {
          final IcyInfo icyInfo = (IcyInfo) entry;
          title = icyInfo.title;
          break;
        }
      }
      listener.onNewInformation((title == null) ? "" : title, lockKey);
    }

    @Override
    public void onTracksChanged(@NonNull Tracks tracks) {
      for (final Tracks.Group group : tracks.getGroups()) {
        for (int i = 0; i < group.length; i++) {
          final Format format = group.getTrackFormat(i);
          final int bitrate = format.bitrate;
          final String mimeType = format.sampleMimeType;
          if (bitrate != Format.NO_VALUE) {
            listener.onNewBitrate(bitrate / 1000, (mimeType == null) ? "" : mimeType, lockKey);
          }
        }
      }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
      Log.e(LOG_TAG, "ExoPlayer transcoder error: " + error.getMessage());
      onState(PlaybackStateCompat.STATE_ERROR);
    }
  }
}