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

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.extractor.metadata.icy.IcyInfo;

@OptIn(markerClass = UnstableApi.class)
public abstract class SessionDevice {
  protected static final long DEFAULT_AVAILABLE_ACTIONS =
    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
      PlaybackStateCompat.ACTION_STOP |
      PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
      PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
  @NonNull
  protected final Context context;
  @NonNull
  protected final ExoPlayer exoPlayer;
  @NonNull
  protected final String lockKey; // Current tag
  @NonNull
  protected final Radio radio;
  @NonNull
  protected final Listener listener;
  @NonNull
  private final Player.Listener playerListener;
  private int state = PlaybackStateCompat.STATE_NONE;

  public SessionDevice(
    @NonNull Context context,
    @NonNull ExoPlayer exoplayer,
    @NonNull Listener listener,
    @NonNull String lockKey,
    @NonNull Radio radio) {
    this.context = context;
    this.exoPlayer = exoplayer;
    this.playerListener = getPlayerListener();
    this.listener = listener;
    this.lockKey = lockKey;
    this.radio = radio;
  }

  public int getState() {
    return state;
  }

  @NonNull
  public Radio getRadio() {
    return radio;
  }

  @NonNull
  public String getLockKey() {
    return lockKey;
  }

  @SuppressWarnings("unused")
  public void onNewInformation(@NonNull String information) {
  }

  public boolean isPaused() {
    return (state == PlaybackStateCompat.STATE_PAUSED);
  }

  public boolean isError() {
    return (state == PlaybackStateCompat.STATE_ERROR);
  }

  public void play() {
    state = PlaybackStateCompat.STATE_PLAYING;
    exoPlayer.play();
  }

  public void pause() {
    state = PlaybackStateCompat.STATE_PAUSED;
    if (isRemote()) {
      exoPlayer.stop();
    } else {
      exoPlayer.pause();
    }
  }

  public void stop() {
    state = PlaybackStateCompat.STATE_STOPPED;
    exoPlayer.stop();
  }

  public void prepareFromMediaId() {
    state = PlaybackStateCompat.STATE_BUFFERING;
    exoPlayer.addListener(playerListener);
    exoPlayer.setMediaItem(MediaItem.fromUri(radio.getUri()));
    exoPlayer.prepare();
    exoPlayer.setPlayWhenReady(true);
  }

  public abstract boolean isRemote();

  public void release() {
    exoPlayer.removeListener(playerListener);
    exoPlayer.release();
  }

  public abstract void setVolume(float volume);

  public abstract void adjustVolume(int direction);

  // Set the current capabilities available on this session
  public abstract long getAvailableActions();

  protected void onMetadata(@NonNull Metadata metadata) {
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

  protected void onTracksChanged(@NonNull Tracks tracks) {
    for (final Tracks.Group group : tracks.getGroups()) {
      for (int i = 0; i < group.length; i++) {
        final Format format = group.getTrackFormat(i);
        if (format.bitrate != Format.NO_VALUE) {
          listener.onNewBitrate(format.bitrate / 1000, lockKey);
        }
      }
    }
  }

  protected void onState(int state) {
    this.state = state;
    listener.onNewState(this.state, lockKey);
  }

  @NonNull
  protected abstract Player.Listener getPlayerListener();

  public interface Listener {
    void onNewState(int state, @NonNull String lockKey);

    void onNewInformation(@NonNull String information, @NonNull String lockKey);

    void onNewBitrate(int bitrate, @NonNull String lockKey);
  }
}