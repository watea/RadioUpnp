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

import android.content.Context;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

public class LocalSessionDevice extends SessionDevice {
  private static final String LOG_TAG = LocalSessionDevice.class.getSimpleName();

  public LocalSessionDevice(
    @NonNull Context context,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey) {
    super(context, Mode.LOCAL, null, listener, radio, lockKey);
  }

  @Override
  public boolean isRemote() {
    return false;
  }

  @Override
  public boolean isUpnp() {
    return false;
  }

  @Override
  public void setVolume(float volume) {
    exoPlayer.setVolume(volume);
  }

  // Not supported
  @Override
  public void adjustVolume(int direction) {
  }

  @NonNull
  protected Player.Listener getPlayerListener() {
    return new PlayerListener() {
      @Override
      public void onPlaybackStateChanged(int playbackState) {
        Log.d(LOG_TAG, "onPlaybackStateChanged: state = " + playbackState);
        switch (playbackState) {
          case ExoPlayer.STATE_BUFFERING:
            onState(PlaybackStateCompat.STATE_BUFFERING);
            break;
          case ExoPlayer.STATE_READY:
            // Delegate to onIsPlayingChanged — it fires on both initial play and resume
            break;
          case ExoPlayer.STATE_IDLE:
          case ExoPlayer.STATE_ENDED:
            onState(PlaybackStateCompat.STATE_ERROR);
            break;
          // Should not happen
          default:
            Log.e(LOG_TAG, "onPlaybackStateChanged: bad state = " + playbackState);
            onState(PlaybackStateCompat.STATE_ERROR);
        }
      }

      @Override
      public void onIsPlayingChanged(boolean isPlaying) {
        // Fired on play/pause toggle while STATE_READY stays constant
        if (exoPlayer.getPlaybackState() == ExoPlayer.STATE_READY) {
          onState(isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED);
        }
      }
    };
  }
}