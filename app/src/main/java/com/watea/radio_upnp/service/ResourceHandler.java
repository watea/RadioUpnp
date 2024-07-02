package com.watea.radio_upnp.service;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import fi.iki.elonen.NanoHTTPD;

public class ResourceHandler implements NanoHttpServer.Handler {
  private static final String LOGO_FILE = "logo";
  private static final int REMOTE_LOGO_SIZE = 300;
  @Nullable
  private Bitmap bitmap = null;
  @Nullable
  private String uri = null;

  @Override
  public NanoHTTPD.Response handle(@NonNull NanoHTTPD.IHTTPSession iHTTPSession) {
    final String requestedUri = iHTTPSession.getUri();
    if ((bitmap == null) || (requestedUri == null) || !requestedUri.endsWith(uri)) {
      return null;
    }
    ByteArrayOutputStream stream = new ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream);
    final byte[] imageBytes = stream.toByteArray();
    return newFixedLengthResponse(
      NanoHTTPD.Response.Status.OK,
      "image/jpeg",
      new ByteArrayInputStream(imageBytes),
      imageBytes.length);
  }

  // Creates bitmap, returns name of target
  @Nullable
  public String createLogoFile(@NonNull Radio radio) {
    uri = LOGO_FILE + radio.getId() + ".jpg";
    bitmap = Bitmap.createScaledBitmap(radio.getIcon(), REMOTE_LOGO_SIZE, REMOTE_LOGO_SIZE, true);
    return uri;
  }
}