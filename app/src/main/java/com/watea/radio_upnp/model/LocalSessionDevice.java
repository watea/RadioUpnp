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
import androidx.annotation.OptIn;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.watea.radio_upnp.service.UpnpStreamServer;

@OptIn(markerClass = UnstableApi.class)
public class LocalSessionDevice extends SessionDevice {
  private static final String LOG_TAG = LocalSessionDevice.class.getSimpleName();

  public LocalSessionDevice(
    @NonNull Context context,
    @NonNull ExoPlayer exoPlayer,
    @NonNull UpnpStreamServer.ConnectionSetSupplier upnpStreamServerConnectionSetSupplier,
    @NonNull Listener listener,
    @NonNull String lockKey,
    @NonNull Radio radio) {
    super(context, exoPlayer, upnpStreamServerConnectionSetSupplier, listener, lockKey, radio);
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

  @Override
  public long getAvailableActions(int state) {
    long availableActions = super.getAvailableActions(state);
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