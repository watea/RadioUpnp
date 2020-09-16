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

import android.content.Context;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.HttpServer;
import com.watea.radio_upnp.service.RadioHandler;

public final class LocalPlayerAdapter extends PlayerAdapter {
  private static final String LOG_TAG = LocalPlayerAdapter.class.getName();
  private static final int HTTP_TIMEOUT_RATIO = 10;
  private final Player.EventListener playerEventListener = new Player.EventListener() {
    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
      Log.i(LOG_TAG, "ExoPlayer: onPlayerStateChanged, State=" + playbackState +
        " PlayWhenReady=" + playWhenReady);
      switch (playbackState) {
        case Player.STATE_BUFFERING:
        case Player.STATE_IDLE:
          changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
          break;
        case Player.STATE_READY:
          changeAndNotifyState(
            playWhenReady ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED);
          break;
        case Player.STATE_ENDED:
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
          break;
        // Should not happen
        default:
          Log.e(LOG_TAG, "onPlayerStateChanged: onPlayerStateChanged bad state " + playbackState);
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
      }
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
    }

    @Override
    public void onShuffleModeEnabledChanged(boolean shuffleModeEnabled) {
    }

    @Override
    public void onPlayerError(ExoPlaybackException exoPlaybackException) {
      Log.d(LOG_TAG, "ExoPlayer: onPlayerError " + exoPlaybackException);
      changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
    }

    @Override
    public void onSeekProcessed() {
    }
  };
  @Nullable
  private SimpleExoPlayer simpleExoPlayer = null;

  public LocalPlayerAdapter(
    @NonNull Context context,
    @NonNull HttpServer httpServer,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey) {
    super(context, httpServer, listener, radio, lockKey);
  }

  @Override
  public void setVolume(float volume) {
    if (simpleExoPlayer == null) {
      Log.i(LOG_TAG, "setVolume on null ExoPlayer");
    } else {
      simpleExoPlayer.setVolume(volume);
    }
  }

  @Override
  protected boolean isLocal() {
    return true;
  }

  @Override
  protected void onPrepareFromMediaId() {
    simpleExoPlayer = ExoPlayerFactory.newSimpleInstance(
      new DefaultRenderersFactory(context),
      new DefaultTrackSelector(),
      new DefaultLoadControl());
    simpleExoPlayer.addListener(playerEventListener);
    simpleExoPlayer.setPlayWhenReady(true);
    simpleExoPlayer.prepare(
      new ExtractorMediaSource.Factory(
        // Better management of bad wifi connection
        new DefaultHttpDataSourceFactory(
          context.getResources().getString(R.string.app_name),
          new DefaultBandwidthMeter(),
          DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
          DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS * HTTP_TIMEOUT_RATIO,
          false))
        .setExtractorsFactory(new DefaultExtractorsFactory())
        .createMediaSource(
          RadioHandler.getHandledUri(HttpServer.getLoopbackUri(), radio, lockKey)));
  }

  @Override
  protected void onPlay() {
    if (simpleExoPlayer == null) {
      Log.i(LOG_TAG, "onPlay on null ExoPlayer");
    } else {
      simpleExoPlayer.setPlayWhenReady(true);
    }
  }

  @Override
  protected void onPause() {
    if (simpleExoPlayer == null) {
      Log.i(LOG_TAG, "onPause on null ExoPlayer");
    } else {
      simpleExoPlayer.setPlayWhenReady(false);
    }
  }

  @Override
  protected void onStop() {
    if (simpleExoPlayer == null) {
      Log.i(LOG_TAG, "onStop on null ExoPlayer");
    } else {
      simpleExoPlayer.stop();
    }
  }

  @Override
  protected void onRelease() {
    if (simpleExoPlayer == null) {
      Log.i(LOG_TAG, "onRelease on null ExoPlayer");
    } else {
      simpleExoPlayer.removeListener(playerEventListener);
      simpleExoPlayer.release();
      simpleExoPlayer = null;
    }
  }

  @Override
  protected long getAvailableActions() {
    long actions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID | PlaybackStateCompat.ACTION_STOP;
    switch (state) {
      case PlaybackStateCompat.STATE_PLAYING:
        actions |= PlaybackStateCompat.ACTION_PAUSE;
        break;
      case PlaybackStateCompat.STATE_PAUSED:
      case PlaybackStateCompat.STATE_BUFFERING:
        actions |= PlaybackStateCompat.ACTION_PLAY;
        break;
      default:
        // Nothing else
    }
    return actions;
  }
}