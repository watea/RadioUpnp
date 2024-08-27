/*
 * Copyright (c) 2024. Stephane Treuchot
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;

import java.io.IOException;

public class RadioHttpServer extends HttpServer {
  @NonNull
  private final RadioHandler radioHandler;
  @NonNull
  private final ResourceHandler resourceHandler = new ResourceHandler();
  @NonNull
  private final Context context;

  public RadioHttpServer(
    @NonNull Context context,
    @NonNull RadioHandler.Listener radioHandlerListener) throws IOException {
    this.context = context;
    radioHandler = new RadioHandler(this.context, radioHandlerListener);
    addHandler(radioHandler);
    addHandler(resourceHandler);
  }

  @Nullable
  public Uri getUri() {
    return new NetworkProxy(context).getUri(getListeningPort());
  }

  @NonNull
  public Uri getLoopbackUri() {
    return NetworkProxy.getLoopbackUri(getListeningPort());
  }

  public void resetRadioHandlerController() {
    radioHandler.resetController();
  }

  public void setRadioHandlerController(@NonNull RadioHandler.Controller radioHandlerController) {
    radioHandler.setController(radioHandlerController);
  }

  @Nullable
  public Uri createLogoFile(@NonNull Radio radio) {
    final Uri uri = getUri();
    assert uri != null;
    return uri.buildUpon().appendEncodedPath(resourceHandler.createLogoFile(radio)).build();
  }
}