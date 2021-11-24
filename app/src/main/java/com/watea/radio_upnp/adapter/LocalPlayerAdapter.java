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
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.HttpServer;
import com.watea.radio_upnp.service.RadioHandler;

public final class LocalPlayerAdapter extends PlayerAdapter {
  private static final String LOG_TAG = LocalPlayerAdapter.class.getName();
  private static final int READ_TIMEOUT = DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS * 10;
  @Nullable
  private ExoPlayer exoPlayer = null;
  private final Player.Listener playerListener = new Player.Listener() {
    @Override
    public void onPlaybackStateChanged(int playbackState) {
      Log.i(LOG_TAG, "ExoPlayer: onPlayerStateChanged, State=" + playbackState);
      switch (playbackState) {
        case ExoPlayer.STATE_BUFFERING:
          changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
          break;
        case ExoPlayer.STATE_READY:
          changeAndNotifyState((exoPlayer == null) ?
            PlaybackStateCompat.STATE_ERROR :
            getPlayingPausedState(exoPlayer.getPlayWhenReady()));
          break;
        case ExoPlayer.STATE_IDLE:
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

  public LocalPlayerAdapter(
    @NonNull Context context,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey) {
    super(context, listener, radio, lockKey);
  }

  @Override
  public void setVolume(float volume) {
    if (exoPlayer == null) {
      Log.i(LOG_TAG, "setVolume on null ExoPlayer");
    } else {
      exoPlayer.setVolume(volume);
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
      case PlaybackStateCompat.STATE_ERROR:
        actions |= PlaybackStateCompat.ACTION_REWIND;
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
    exoPlayer = new ExoPlayer.Builder(context)
      .setMediaSourceFactory(new DefaultMediaSourceFactory(new DefaultDataSource.Factory(
        context,
        new DefaultHttpDataSource.Factory()
          .setReadTimeoutMs(READ_TIMEOUT)
          .setAllowCrossProtocolRedirects(true))))
      .build();
    exoPlayer.setMediaItem(MediaItem.fromUri(
      RadioHandler.getHandledUri(HttpServer.getLoopbackUri(), radio, lockKey)));
    exoPlayer.setPlayWhenReady(true);
    exoPlayer.addListener(playerListener);
    exoPlayer.prepare();
  }

  @Override
  protected void onPlay() {
    if (exoPlayer == null) {
      Log.i(LOG_TAG, "onPlay on null ExoPlayer");
    } else {
      exoPlayer.setPlayWhenReady(true);
    }
  }

  @Override
  protected void onPause() {
    if (exoPlayer == null) {
      Log.i(LOG_TAG, "onPause on null ExoPlayer");
    } else {
      exoPlayer.setPlayWhenReady(false);
    }
  }

  @Override
  protected void onStop() {
    if (exoPlayer == null) {
      Log.i(LOG_TAG, "onStop on null ExoPlayer");
    } else {
      exoPlayer.stop();
    }
  }

  @Override
  protected void onRelease() {
    if (exoPlayer == null) {
      Log.i(LOG_TAG, "onRelease on null ExoPlayer");
    } else {
      exoPlayer.removeListener(playerListener);
      exoPlayer.release();
      exoPlayer = null;
    }
  }
}