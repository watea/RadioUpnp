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

package com.watea.radio_upnp.model;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.util.function.Consumer;

public abstract class RemoteSessionDevice extends SessionDevice {
  @NonNull
  protected final Uri radioUri;
  @NonNull
  protected final Uri logoUri;
  @NonNull
  private final Consumer<Radio> onPlayCallback;

  protected RemoteSessionDevice(
    @NonNull Context context,
    @NonNull Mode mode,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull Consumer<Radio> onPlayCallback,
    @NonNull StreamServerCallback streamServerCallback) {
    super(context, mode, listener, radio);
    this.onPlayCallback = onPlayCallback;
    radioUri = streamServerCallback.getStreamUri(this.radio, lockKey, (this.mode == Mode.PCM));
    logoUri = streamServerCallback.getLogoUri(this.radio);
    if (this.mode == Mode.PCM) {
      capturingAudioSink.setCallback(streamServerCallback.getPcmCallback());
    }
    streamServerCallback.launch(lockKey);
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

  public interface StreamServerCallback {
    @NonNull
    CapturingAudioSink.Callback getPcmCallback();

    @NonNull
    Uri getLogoUri(@NonNull Radio radio);

    @NonNull
    Uri getStreamUri(@NonNull Radio radio, @NonNull String lockKey, boolean isPcm);

    void launch(@NonNull String lockKey);
  }
}