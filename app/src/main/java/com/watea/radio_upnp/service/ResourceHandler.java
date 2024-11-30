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

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.candidhttpserver.HttpServer;
import com.watea.radio_upnp.model.Radio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class ResourceHandler implements HttpServer.Handler {
  private static final String LOG_TAG = ResourceHandler.class.getSimpleName();
  private static final int REMOTE_LOGO_SIZE = 300;
  @Nullable
  private Bitmap bitmap = null;
  @Nullable
  private String uri = null;

  // Creates bitmap, returns name of target
  @Nullable
  public String createLogoFile(@NonNull Radio radio) {
    bitmap = Bitmap.createScaledBitmap(radio.getIcon(), REMOTE_LOGO_SIZE, REMOTE_LOGO_SIZE, true);
    return uri = "logo" + radio.getId() + ".jpg";
  }

  @Override
  public void handle(
    @NonNull HttpServer.Request request,
    @NonNull HttpServer.Response response,
    @NonNull OutputStream responseStream) throws IOException {
    final String requestedPath = request.getPath();
    if ((bitmap == null) || !requestedPath.endsWith(uri)) {
      return;
    }
    Log.d(LOG_TAG, "handle: accepted " + requestedPath);
    try (final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
      bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
      final byte[] imageData = byteArrayOutputStream.toByteArray();
      response.addHeader(HttpServer.Response.CONTENT_TYPE, "image/jpeg");
      response.addHeader(HttpServer.Response.CONTENT_LENGTH, String.valueOf(imageData.length));
      response.send();
      responseStream.write(imageData);
    }
  }
}