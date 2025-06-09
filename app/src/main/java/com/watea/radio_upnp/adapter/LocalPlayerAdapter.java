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
import android.net.Uri;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import com.watea.radio_upnp.model.Radio;

public class LocalPlayerAdapter extends PlayerAdapter {
  private static final String LOG_TAG = LocalPlayerAdapter.class.getSimpleName();
  @NonNull
  private final ExoPlayer exoPlayer;
  private final Player.Listener playerListener = new Player.Listener() {
    @Override
    public void onPlaybackStateChanged(int playbackState) {
      Log.d(LOG_TAG, "onPlaybackStateChanged: State=" + playbackState);
      switch (playbackState) {
        case ExoPlayer.STATE_BUFFERING:
          changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
          break;
        case ExoPlayer.STATE_READY:
          changeAndNotifyState(PlaybackStateCompat.STATE_PLAYING);
          break;
        case ExoPlayer.STATE_IDLE:
          if (isPaused) {
            changeAndNotifyState(PlaybackStateCompat.STATE_PAUSED);
            break;
          }
        case ExoPlayer.STATE_ENDED:
          // Do nothing if we are already stopped
          if (state != PlaybackStateCompat.STATE_STOPPED) {
            changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
          }
          break;
        // Should not happen
        default:
          Log.e(LOG_TAG, "onPlaybackStateChanged: bad State=" + playbackState);
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
      }
    }
  };

  public LocalPlayerAdapter(
    @NonNull Context context,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey,
    @NonNull Uri radioUri) {
    super(context, listener, radio, lockKey, radioUri);
    exoPlayer = new ExoPlayer.Builder(this.context).build();
  }

  @Override
  public void setVolume(float volume) {
    exoPlayer.setVolume(volume);
  }

  @Override
  public long getAvailableActions() {
    long actions = super.getAvailableActions();
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
  protected boolean isRemote() {
    return false;
  }

  // Note: as the MediaItem is the local server in the current architecture,
  // m3u8 support is not possible.
  // Some attempt to use direct radio connection and m3u8 option are not
  // successful till now, deeper search on exoplayer API is necessary.
  @Override
  protected void onPrepareFromMediaId() {
    exoPlayer.setMediaItem(MediaItem.fromUri(radioUri));
    exoPlayer.addListener(playerListener);
    exoPlayer.prepare();
    exoPlayer.setPlayWhenReady(true);
  }

  @Override
  protected void onPlay() {
    onPrepareFromMediaId();
  }

  @Override
  protected void onPause() {
    exoPlayer.stop();
  }

  @Override
  protected void onStop() {
    exoPlayer.stop();
  }

  @Override
  protected void onRelease() {
    exoPlayer.removeListener(playerListener);
    exoPlayer.release();
  }
}