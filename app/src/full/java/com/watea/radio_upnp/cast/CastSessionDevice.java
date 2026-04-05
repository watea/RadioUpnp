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

package com.watea.radio_upnp.cast;

import android.content.Context;
import android.net.Uri;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.SessionDevice;
import com.watea.radio_upnp.service.UpnpStreamServer;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@OptIn(markerClass = UnstableApi.class)
public class CastSessionDevice extends SessionDevice {
  private static final String LOG_TAG = CastSessionDevice.class.getSimpleName();
  private static final double VOLUME_STEP = 0.05; // 5%
  private static final int HEART_BEAT = 60; // s
  @NonNull
  private final CastSession castSession;
  @NonNull
  private final Uri radioUri;
  @Nullable
  private final Uri logoUri;
  @Nullable
  private RemoteMediaClient remoteMediaClient;
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
          onState(PlaybackStateCompat.STATE_PLAYING);
          break;
        case MediaStatus.PLAYER_STATE_PAUSED:
          onState(PlaybackStateCompat.STATE_PAUSED);
          break;
        case MediaStatus.PLAYER_STATE_BUFFERING:
        case MediaStatus.PLAYER_STATE_LOADING:
          onState(PlaybackStateCompat.STATE_BUFFERING);
          break;
        case MediaStatus.PLAYER_STATE_IDLE:
          onState(PlaybackStateCompat.STATE_ERROR);
          break;
        default:
          onState(PlaybackStateCompat.STATE_STOPPED);
      }
    }
  };
  @Nullable
  private ScheduledExecutorService heartbeat;

  public CastSessionDevice(
    @NonNull Context context,
    @NonNull ExoPlayer exoPlayer,
    @NonNull UpnpStreamServer.ConnectionSetSupplier upnpStreamServerConnectionSetSupplier,
    @NonNull Listener listener,
    @NonNull String lockKey,
    @NonNull Radio radio,
    @NonNull Uri radioUri,
    @Nullable Uri logoUri,
    @NonNull CastSession castSession) {
    super(context, exoPlayer, upnpStreamServerConnectionSetSupplier, listener, lockKey, radio);
    this.radioUri = radioUri;
    this.logoUri = logoUri;
    this.castSession = castSession;
  }

  @Override
  public boolean isRemote() {
    return true;
  }

  @Override
  public boolean isUpnp() {
    return false;
  }

  @Override
  public void setVolume(float volume) {
    try {
      castSession.setVolume(volume); // 0.0 to 1.0
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "Failed to set volume", iOException);
    }
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
  public void prepareFromMediaId() {
    super.prepareFromMediaId();
    if (upnpStreamServerConnectionSet == null) {
      Log.e(LOG_TAG, "prepareFromMediaId: unable to connect");
      onState(PlaybackStateCompat.STATE_ERROR);
      return;
    }
    remoteMediaClient = castSession.getRemoteMediaClient();
    if (remoteMediaClient != null) {
      remoteMediaClient.registerCallback(remoteCallback);
      load(remoteMediaClient, radio.getName(), context.getString(R.string.app_name), radioUri.toString(), logoUri);
      // Heartbeat
      heartbeat = Executors.newSingleThreadScheduledExecutor();
      heartbeat.scheduleWithFixedDelay(() -> remoteMediaClient.requestStatus(), HEART_BEAT, HEART_BEAT, TimeUnit.SECONDS);
    }
  }

  @Override
  public void play() {
    super.play();
    if (remoteMediaClient != null) {
      remoteMediaClient.play();
    }
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
      clearCastUi(remoteMediaClient);
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
      remoteMediaClient = null;
    }
  }

  private void load(
    @NonNull RemoteMediaClient remoteMediaClient,
    @NonNull String keyTitle,
    @NonNull String keySubtitle,
    @NonNull String radioUri,
    @Nullable Uri logoUri) {
    final MediaMetadata movieMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK);
    movieMetadata.putString(MediaMetadata.KEY_TITLE, keyTitle);
    movieMetadata.putString(MediaMetadata.KEY_SUBTITLE, keySubtitle);
    if (logoUri != null) {
      movieMetadata.addImage(new WebImage(logoUri));
    }
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