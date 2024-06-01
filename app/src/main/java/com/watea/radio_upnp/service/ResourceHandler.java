package com.watea.radio_upnp.service;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.model.Radio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import fi.iki.elonen.NanoHTTPD;

public class ResourceHandler implements NanoHttpServer.Handler {
  private static final String LOGO_FILE = "logo";
  private static final int REMOTE_LOGO_SIZE = 300;
  private final Uri uri;
  private Uri logoUri = null;
  private Bitmap bitmap = null;

  public ResourceHandler(@NonNull Uri uri) {
    this.uri = uri;
  }

  @Override
  public NanoHTTPD.Response handle(@NonNull NanoHTTPD.IHTTPSession iHTTPSession) {
    final String requestedUri = iHTTPSession.getUri();
    if ((bitmap == null) || (requestedUri == null) || !requestedUri.equals(logoUri.toString())) {
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

  // Creates bitmap
  @Nullable
  public Uri createLogoFile(@NonNull Radio radio) {
    final String name = LOGO_FILE + radio.hashCode() + ".jpg";
    bitmap = Bitmap.createScaledBitmap(radio.getIcon(), REMOTE_LOGO_SIZE, REMOTE_LOGO_SIZE, true);
    logoUri = uri.buildUpon().appendEncodedPath(name).build();
    return logoUri;
  }
}