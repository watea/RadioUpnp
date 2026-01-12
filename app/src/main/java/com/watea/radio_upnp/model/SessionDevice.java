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
import android.net.Uri;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public abstract class SessionDevice {
  public static final String AUDIO_CONTENT_TYPE = "audio/";
  public static final String DEFAULT_CONTENT_TYPE = AUDIO_CONTENT_TYPE + "mpeg";
  protected static final long DEFAULT_AVAILABLE_ACTIONS =
    PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
      PlaybackStateCompat.ACTION_STOP |
      PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
      PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
  private static final String APPLICATION_CONTENT_TYPE = "application/";
  private static final List<String> AUDIO_CONTENT_PREFIXS = Arrays.asList(AUDIO_CONTENT_TYPE, APPLICATION_CONTENT_TYPE);
  protected final Context context;
  protected final String lockKey; // Current tag
  protected final Radio radio;
  protected final Uri radioUri;
  protected final Consumer<Integer> listener;
  private int state = PlaybackStateCompat.STATE_NONE;

  public SessionDevice(
    @NonNull Context context,
    @NonNull Consumer<Integer> listener,
    @NonNull String lockKey,
    @NonNull Radio radio,
    @NonNull Uri radioUri) {
    this.context = context;
    this.listener = listener;
    this.lockKey = lockKey;
    this.radio = radio;
    this.radioUri = radioUri;
  }

  public static boolean isHandling(@NonNull String protocolInfo) {
    for (final String audioContentPrefix : AUDIO_CONTENT_PREFIXS) {
      if (protocolInfo.contains(audioContentPrefix))
        return true;
    }
    return false;
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

  // Default is unknown
  public String getContentType() {
    return "";
  }

  @SuppressWarnings("unused")
  public void onNewInformation(@NonNull String information) {
  }

  public boolean isPaused() {
    return (state == PlaybackStateCompat.STATE_PAUSED);
  }

  public void play() {
    state = PlaybackStateCompat.STATE_PLAYING;
  }

  public void pause() {
    state = PlaybackStateCompat.STATE_PAUSED;
  }

  public void stop() {
    state = PlaybackStateCompat.STATE_STOPPED;
  }

  public void onState(int state) {
    this.state = state;
    listener.accept(this.state);
  }

  public void prepareFromMediaId() {
    state = PlaybackStateCompat.STATE_BUFFERING;
  }

  public abstract boolean isRemote();

  public abstract void release();

  public abstract void setVolume(float volume);

  public abstract void adjustVolume(int direction);

  // Set the current capabilities available on this session
  public abstract long getAvailableActions();
}