package com.watea.radio_upnp.service;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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
    final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream);
    byte[] imageData = byteArrayOutputStream.toByteArray();
    response.addHeader(HttpServer.Response.CONTENT_TYPE, "image/jpeg");
    response.addHeader(HttpServer.Response.CONTENT_LENGTH, String.valueOf(imageData.length));
    response.send();
    responseStream.write(imageData);
  }
}