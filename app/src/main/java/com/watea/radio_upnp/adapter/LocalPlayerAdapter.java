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
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.watea.radio_upnp.R;
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
import com.watea.radio_upnp.service.HttpServer;

public final class LocalPlayerAdapter extends PlayerAdapter {
  private static final String LOG_TAG = LocalPlayerAdapter.class.getSimpleName();
  private static final int HTTP_TIMEOUT_RATIO = 10;
  @Nullable
  private SimpleExoPlayer mSimpleExoPlayer;
  @Nullable
  private PlayerEventListener mPlayerEventListener;

  public LocalPlayerAdapter(
    @NonNull Context context,
    @NonNull HttpServer httpServer,
    @NonNull Listener listener) {
    super(context, httpServer, listener, true);
    mSimpleExoPlayer = null;
    mPlayerEventListener = null;
  }

  @Override
  public void setVolume(float volume) {
    if (mSimpleExoPlayer == null) {
      Log.i(LOG_TAG, "setVolume on null ExoPlayer");
    } else {
      mSimpleExoPlayer.setVolume(volume);
    }
  }

  @Override
  protected void onPrepareFromMediaId() {
    assert mRadio != null;
    Log.d(LOG_TAG, "onPrepareFromMediaId");
    mSimpleExoPlayer = ExoPlayerFactory.newSimpleInstance(
      new DefaultRenderersFactory(mContext),
      new DefaultTrackSelector(),
      new DefaultLoadControl());
    mSimpleExoPlayer.addListener(mPlayerEventListener = new PlayerEventListener());
    mSimpleExoPlayer.setPlayWhenReady(true);
    mSimpleExoPlayer.prepare(new ExtractorMediaSource.Factory(
      // Better management of bad wifi connection
      new DefaultHttpDataSourceFactory(mContext.getResources().getString(R.string.app_name),
        new DefaultBandwidthMeter(),
        DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
        DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS * HTTP_TIMEOUT_RATIO,
        false))
      .setExtractorsFactory(new DefaultExtractorsFactory())
      .createMediaSource(mRadio.getHandledUri(HttpServer.getLoopbackUri())));
  }

  @Override
  protected void onPlay() {
    if (mSimpleExoPlayer == null) {
      Log.i(LOG_TAG, "onPlay on null ExoPlayer");
    } else {
      mSimpleExoPlayer.setPlayWhenReady(true);
    }
  }

  @Override
  protected void onPause() {
    if (mSimpleExoPlayer == null) {
      Log.i(LOG_TAG, "onPause on null ExoPlayer");
    } else {
      mSimpleExoPlayer.setPlayWhenReady(false);
    }
  }

  @Override
  public void onStop() {
    if (mSimpleExoPlayer == null) {
      Log.i(LOG_TAG, "onStop on null ExoPlayer");
    } else {
      mSimpleExoPlayer.stop();
    }
  }

  @Override
  protected void onRelease() {
    if (mSimpleExoPlayer == null) {
      Log.i(LOG_TAG, "onRelease on null ExoPlayer");
    } else {
      mSimpleExoPlayer.removeListener(mPlayerEventListener);
      mSimpleExoPlayer.release();
      mSimpleExoPlayer = null;
    }
  }

  @Override
  protected long getAvailableActions() {
    long actions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID | PlaybackStateCompat.ACTION_STOP;
    switch (mState) {
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

  private class PlayerEventListener implements Player.EventListener {
    private final Object mLockKey;

    private PlayerEventListener() {
      // Shall not be null
      mLockKey = getLockKey();
    }

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
      Log.i(LOG_TAG,
        "ExoPlayer: onPlayerStateChanged, State=" + playbackState +
          " PlayWhenReady=" + playWhenReady);
      switch (playbackState) {
        case Player.STATE_BUFFERING:
        case Player.STATE_IDLE:
          changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING, mLockKey);
          break;
        case Player.STATE_ENDED:
          changeAndNotifyState(PlaybackStateCompat.STATE_STOPPED, mLockKey);
          break;
        case Player.STATE_READY:
          changeAndNotifyState(playWhenReady ?
              PlaybackStateCompat.STATE_PLAYING :
              PlaybackStateCompat.STATE_PAUSED,
            mLockKey);
          break;
        default: // Should not happen
          Log.e(LOG_TAG, "onPlayerStateChanged: onPlayerStateChanged bad state " + playbackState);
          throw new RuntimeException();
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
      changeAndNotifyState(PlaybackStateCompat.STATE_ERROR, mLockKey);
      release();
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
  }
}