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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.metadata.MetadataRenderer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.metadata.icy.IcyInfo;

import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

@OptIn(markerClass = UnstableApi.class)
public abstract class SessionDevice {
  // VLC UA is universally accepted by Icecast/Shoutcast CDNs; the app name alone gets filtered
  public static final String STREAMING_USER_AGENT = "Mozilla/5.0 (compatible; RadioUpnp)";
  private static final String LOG_TAG = SessionDevice.class.getSimpleName();
  private static final int CONNECTION_TIMEOUT_S = 10;
  @NonNull
  protected final Context context;
  @NonNull
  protected final Radio radio;
  @NonNull
  protected final String lockKey; // Current tag
  @NonNull
  protected final Listener listener;
  @NonNull
  protected final ExoPlayer exoPlayer;
  @NonNull
  protected final Mode mode;
  @NonNull
  protected final CapturingAudioSink capturingAudioSink;
  @NonNull
  private final Player.Listener playerListener;
  @Nullable
  protected Radio.ConnectionSet connectionSet = null;
  protected volatile boolean isReleased = false;
  private boolean isAllowedToRewind = false;

  protected SessionDevice(
    @NonNull Context context,
    @NonNull Mode mode,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey) {
    this.context = context;
    this.mode = mode;
    this.listener = listener;
    this.radio = radio;
    this.lockKey = lockKey;
    this.capturingAudioSink = new CapturingAudioSink(new DefaultAudioSink.Builder(this.context).build(), this.lockKey);
    this.playerListener = getPlayerListener();
    this.exoPlayer = getExoPlayer();
    Log.d(LOG_TAG, "mode => " + mode);
  }

  public abstract boolean isRemote();

  public abstract void adjustVolume(int direction);

  @NonNull
  public Radio getRadio() {
    return radio;
  }

  public void play() {
    if (isExoPlayerActive()) {
      exoPlayer.play();
    }
  }

  public void pause() {
    if (isExoPlayerActive()) {
      exoPlayer.pause();
    }
  }

  public void stop() {
    if (isExoPlayerActive()) {
      exoPlayer.stop();
    }
  }

  // Must be called in its own thread.
  // Fires ERROR if upstream connection failed.
  public boolean prepare() {
    connectionSet = radio.getConnectionSet(STREAMING_USER_AGENT);
    if (connectionSet == null) {
      if (isExoPlayerActive()) {
        // Pre-check failed but ExoPlayer may still connect (e.g. server rejects our probe headers)
        Log.d(LOG_TAG, "prepare: pre-check failed, trying ExoPlayer directly");
        connectionSet = new Radio.ConnectionSet(radio.getURL(), Radio.DEFAULT_MIME, -1);
      } else {
        // connectionSet must be defined at this point, so we fire an error
        Log.d(LOG_TAG, "prepare: unable to connect");
        onState(State.ERROR);
        return false;
      }
    }
    if (isExoPlayerActive()) {
      // Post ExoPlayer calls to the main thread
      new Handler(Looper.getMainLooper()).post(this::startExoPlayer);
    } else {
      listener.onNewBitrate(connectionSet.getBitrate(), connectionSet.getContent(), lockKey);
    }
    return true;
  }

  public void allowRewind() {
    isAllowedToRewind = true;
  }

  public boolean consumeRewind() {
    final boolean result = isAllowedToRewind;
    isAllowedToRewind = false;
    return result;
  }

  public void onState(@NonNull State state) {
    Log.d(LOG_TAG, "onState: " + state.name() + "/" + lockKey);
    listener.onState(state, lockKey);
  }

  public void release() {
    exoPlayer.removeListener(playerListener);
    exoPlayer.release();
    isReleased = true;
  }

  protected abstract void setVolume(float volume);

  protected State getState() {
    return listener.getPlaybackState();
  }

  protected boolean isPlaying() {
    return (getState() == State.PLAYING);
  }

  // Overrides must not reference subclass instance fields to avoid NPE on initialization
  @NonNull
  protected Player.Listener getPlayerListener() {
    return new PlayerListener();
  }

  protected void startExoPlayer() {
    if (connectionSet == null) {
      Log.d(LOG_TAG, "startExoPlayer: internal failure, connectionSet is null!");
      return;
    }
    exoPlayer.addListener(playerListener);
    exoPlayer.setMediaItem(MediaItem.fromUri(connectionSet.getUrl().toString()));
    exoPlayer.prepare();
    exoPlayer.setPlayWhenReady(true);
  }

  protected void restartExoPlayer() {
    exoPlayer.removeListener(playerListener);
    exoPlayer.stop();
    exoPlayer.clearMediaItems();
    startExoPlayer();
  }

  private boolean isExoPlayerActive() {
    return (mode != Mode.MUTE);
  }

  @NonNull
  private ExoPlayer getExoPlayer() {
    final Map<String, String> userAgentProperty = Collections.singletonMap("User-Agent", STREAMING_USER_AGENT);
    DataSource.Factory httpDataSourceFactory;
    try {
      final EasyX509TrustManager easyX509TrustManager = new EasyX509TrustManager();
      httpDataSourceFactory = new OkHttpDataSource.Factory(new OkHttpClient.Builder()
        .sslSocketFactory(EasyX509TrustManager.getSSLSocketFactory(easyX509TrustManager), easyX509TrustManager)
        .connectTimeout(CONNECTION_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(CONNECTION_TIMEOUT_S, TimeUnit.SECONDS)
        .build())
        .setDefaultRequestProperties(userAgentProperty);
    } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException exception) {
      Log.e(LOG_TAG, "Internal failure: error handling SSL connection", exception);
      httpDataSourceFactory = new DefaultHttpDataSource.Factory().setDefaultRequestProperties(userAgentProperty);
    }
    return new ExoPlayer.Builder(context)
      .setMediaSourceFactory(new DefaultMediaSourceFactory(httpDataSourceFactory))
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
            capturingAudioSink),
          new MetadataRenderer(metadataOutput, handler.getLooper())
        })
      .build();
  }

  public enum State {
    IDLE, PLAYING, PAUSED, BUFFERING, ERROR, STOPPED
  }

  protected enum Mode {
    LOCAL, PCM, MUTE
  }

  public interface Listener {
    void onState(@NonNull State state, @NonNull String lockKey);

    void onNewInformation(@NonNull String information, @NonNull String lockKey);

    void onNewBitrate(int bitrate, @NonNull String mimeType, @NonNull String lockKey);

    @NonNull
    State getPlaybackState();
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
      onState(State.ERROR);
    }
  }
}