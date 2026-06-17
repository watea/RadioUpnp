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

package com.watea.radio_upnp.service;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;

import com.watea.radio_upnp.model.Radio;

import java.util.function.Consumer;

public abstract class RemoteSessionDevice extends SessionDevice {
  private static final String LOG_TAG = RemoteSessionDevice.class.getSimpleName();
  @NonNull
  protected final Uri radioUri;
  @NonNull
  protected final Uri logoUri;
  @NonNull
  private final Consumer<Radio> onPlayCallback;
  @NonNull
  private final StreamServer streamServer;

  protected RemoteSessionDevice(
    @NonNull Context context,
    @NonNull Mode mode,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull Consumer<Radio> onPlayCallback,
    @NonNull StreamServer streamServer) {
    super(context, mode, listener, radio);
    this.onPlayCallback = onPlayCallback;
    this.streamServer = streamServer;
    radioUri = this.streamServer.getStreamUri(this.radio, lockKey, (this.mode == Mode.PCM));
    logoUri = this.streamServer.getLogoUri(this.radio);
    if (this.mode == Mode.PCM) {
      capturingAudioSink.setCallback(this.streamServer.getPcmCallback());
    }
    this.streamServer.setListener(new StreamServer.Listener() {
      @Override
      public void onDisconnected(@NonNull String lockKey) {
        Log.d(LOG_TAG, "onDisconnected: " + lockKey);
        if (lockKey.equals(RemoteSessionDevice.this.lockKey)) {
          onState(State.ERROR);
        }
      }

      @Override
      public void onConnected(@NonNull String lockKey) {
        Log.d(LOG_TAG, "onConnected: " + lockKey);
        if (lockKey.equals(RemoteSessionDevice.this.lockKey)) {
          allowRewind();
        }
      }

      @Override
      public void onNewInformation(@NonNull String information, @NonNull String lockKey) {
        listener.onNewInformation(information, lockKey);
      }
    });
  }

  @Override
  public void launch() {
    streamServer.launch(lockKey);
    super.launch();
  }

  @Override
  public final boolean isRemote() {
    return true;
  }

  @Override
  public void play() {
    onPlayCallback.accept(radio);
  }

  @Override
  public void pause() {
    onState(State.PAUSED);
    super.stop();
  }
}