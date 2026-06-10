/*
 * Copyright (c) 2026. Stephane Treuchot
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
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;
import com.watea.radio_upnp.R;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class CastSessionDevice extends RemoteSessionDevice {
  private static final String LOG_TAG = CastSessionDevice.class.getSimpleName();
  private static final Handler HANDLER = new Handler(Looper.getMainLooper());
  private static final double VOLUME_STEP = 0.05; // 5%
  private static final int HEART_BEAT = 60; // s
  @NonNull
  private final CastSession castSession;
  @Nullable
  private RemoteMediaClient remoteMediaClient = null;
  private final RemoteMediaClient.Callback remoteCallback = new RemoteMediaClient.Callback() {
    @Override
    public void onStatusUpdated() {
      if ((remoteMediaClient == null) || (remoteMediaClient.getMediaStatus() == null)) {
        return;
      }
      final int playerState = remoteMediaClient.getPlayerState();
      Log.d(LOG_TAG, "onStatusUpdated: state = " + playerState);
      switch (playerState) {
        case MediaStatus.PLAYER_STATE_PLAYING:
          onState(State.PLAYING);
          break;
        case MediaStatus.PLAYER_STATE_PAUSED:
          onState(State.PAUSED);
          break;
        case MediaStatus.PLAYER_STATE_BUFFERING:
        case MediaStatus.PLAYER_STATE_LOADING:
          onState(State.BUFFERING);
          break;
        case MediaStatus.PLAYER_STATE_IDLE:
          if (isPlaying()) {
            onState(State.ERROR);
          }
          break;
        default:
          onState(State.STOPPED);
      }
    }
  };
  @Nullable
  private ScheduledExecutorService heartbeat = null;

  public CastSessionDevice(
    @NonNull Context context,
    @NonNull ServerCallback serverCallback,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey,
    @NonNull CastSession castSession,
    @NonNull Consumer<Radio> onPlayCallback) {
    super(context, Mode.PCM, serverCallback, listener, radio, lockKey, onPlayCallback);
    this.castSession = castSession;
  }

  @Override
  public void adjustVolume(int direction) {
    try {
      final double current = castSession.getVolume();
      if (direction > 0) {
        castSession.setVolume(Math.min(1.0, current + VOLUME_STEP));
      } else if (direction < 0) {
        castSession.setVolume(Math.max(0.0, current - VOLUME_STEP));
      }
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "Failed to adjust volume", iOException);
    }
  }

  @Override
  public boolean prepare() {
    if (super.prepare()) {
      HANDLER.post(() -> {
        remoteMediaClient = castSession.getRemoteMediaClient();
        if (remoteMediaClient == null) {
          Log.e(LOG_TAG, "Failed to get remote media client");
          onState(State.ERROR);
        } else {
          remoteMediaClient.registerCallback(remoteCallback);
          load(remoteMediaClient, radio.getName(), context.getString(R.string.app_name), radioUri.toString(), logoUri);
          // Heartbeat
          heartbeat = Executors.newSingleThreadScheduledExecutor();
          heartbeat.scheduleWithFixedDelay(() -> {
            final RemoteMediaClient client = remoteMediaClient;
            if (client != null) {
              client.requestStatus();
            }
          }, HEART_BEAT, HEART_BEAT, TimeUnit.SECONDS);
        }
      });
      return true;
    }
    return false;
  }

  @Override
  public void pause() {
    super.pause();
    if (remoteMediaClient != null) {
      remoteMediaClient.pause();
    }
  }

  @Override
  public void stop() {
    super.stop();
    if (remoteMediaClient != null) {
      remoteMediaClient.stop();
    }
  }

  @Override
  public void release() {
    super.release();
    if (heartbeat != null) {
      heartbeat.shutdownNow();
      heartbeat = null;
    }
    if (remoteMediaClient != null) {
      remoteMediaClient.unregisterCallback(remoteCallback);
      clearCastUi(remoteMediaClient);
      remoteMediaClient = null;
    }
  }

  @Override
  protected void setVolume(float volume) {
    try {
      castSession.setVolume(volume); // 0.0 to 1.0
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "Failed to set volume", iOException);
    }
  }

  private void load(
    @NonNull RemoteMediaClient remoteMediaClient,
    @NonNull String keyTitle,
    @NonNull String keySubtitle,
    @NonNull String radioUri,
    @NonNull Uri logoUri) {
    final MediaMetadata movieMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
    movieMetadata.putString(MediaMetadata.KEY_TITLE, keyTitle);
    movieMetadata.putString(MediaMetadata.KEY_SUBTITLE, keySubtitle);
    movieMetadata.addImage(new WebImage(logoUri));
    final MediaInfo mediaInfo = new MediaInfo.Builder(radioUri)
      .setStreamType(MediaInfo.STREAM_TYPE_LIVE) // Radio
      .setMetadata(movieMetadata)
      .build();
    final MediaLoadRequestData requestData = new MediaLoadRequestData.Builder()
      .setMediaInfo(mediaInfo)
      .setAutoplay(true)
      .build();
    remoteMediaClient.load(requestData);
  }

  private void clearCastUi(@NonNull RemoteMediaClient remoteMediaClient) {
    final MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC);
    metadata.putString(MediaMetadata.KEY_TITLE, "");
    final MediaInfo mediaInfo = new MediaInfo.Builder("")
      .setStreamType(MediaInfo.STREAM_TYPE_NONE)
      .setMetadata(metadata)
      .build();
    final MediaLoadRequestData requestData = new MediaLoadRequestData.Builder()
      .setMediaInfo(mediaInfo)
      .setAutoplay(false)
      .build();
    remoteMediaClient.load(requestData);
  }
}