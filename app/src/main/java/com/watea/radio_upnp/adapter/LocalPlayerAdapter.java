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

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.HttpServer;
import com.watea.radio_upnp.service.RadioHandler;

public final class LocalPlayerAdapter extends PlayerAdapter {
  private static final String LOG_TAG = LocalPlayerAdapter.class.getName();
  private static final int HTTP_TIMEOUT_RATIO = 10;
  private final Player.EventListener playerEventListener = new Player.EventListener() {
    @Override
    public void onPlaybackStateChanged(int playbackState) {
      Log.i(LOG_TAG, "ExoPlayer: onPlayerStateChanged, State=" + playbackState);
      switch (playbackState) {
        case ExoPlayer.STATE_IDLE:
          // Nothing to do
          break;
        case ExoPlayer.STATE_BUFFERING:
          changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
          break;
        case ExoPlayer.STATE_READY:
          changeAndNotifyState(getPlayingPausedState(simpleExoPlayer.getPlayWhenReady()));
          break;
        case ExoPlayer.STATE_ENDED:
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
          break;
        // Should not happen
        default:
          Log.e(LOG_TAG, "onPlayerStateChanged: onPlayerStateChanged bad state " + playbackState);
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
      }
    }

    @Override
    public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
      changeAndNotifyState(getPlayingPausedState(playWhenReady));
    }

    private int getPlayingPausedState(boolean playWhenReady) {
      return playWhenReady ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED;
    }
  };
  @Nullable
  private SimpleExoPlayer simpleExoPlayer = null;

  public LocalPlayerAdapter(
    @NonNull Context context,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey) {
    super(context, listener, radio, lockKey);
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
  public long getAvailableActions() {
    long actions =
      PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
        PlaybackStateCompat.ACTION_STOP |
        PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
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

  @Override
  protected boolean isLocal() {
    return true;
  }

  @Override
  protected void onPrepareFromMediaId() {
    DefaultHttpDataSource.Factory defaultHttpDataSourceFactory =
      new DefaultHttpDataSource.Factory();
    defaultHttpDataSourceFactory.setReadTimeoutMs(
      DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS * HTTP_TIMEOUT_RATIO);
    simpleExoPlayer = new SimpleExoPlayer
      .Builder(context)
      .setMediaSourceFactory(
        new DefaultMediaSourceFactory(
          new DefaultDataSourceFactory(context, defaultHttpDataSourceFactory)))
      .build();
    simpleExoPlayer.setMediaItem(MediaItem.fromUri(
      RadioHandler.getHandledUri(HttpServer.getLoopbackUri(), radio, lockKey)));
    simpleExoPlayer.setPlayWhenReady(true);
    simpleExoPlayer.addListener(playerEventListener);
    simpleExoPlayer.prepare();
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
}