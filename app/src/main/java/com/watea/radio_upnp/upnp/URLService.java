/*
 * Copyright (c) 2024-2026. Stephane Treuchot
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

package com.watea.radio_upnp.upnp;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

class URLService {
  private static final int CONNECT_TIMEOUT = 8000; // ms
  private static final int READ_TIMEOUT = 3000; // ms
  private static final int BUFFER_SIZE = 8192;
  private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
  @NonNull
  private final URLConnection uRLConnection;
  private final Map<String, String> tags = new HashMap<>();
  @Nullable
  private String content = null;
  private boolean streamConsumed = false; // Guards against double getInputStream() calls on the same URLConnection

  URLService(@NonNull URL uRL) throws IOException {
    uRLConnection = uRL.openConnection();
    uRLConnection.setConnectTimeout(CONNECT_TIMEOUT);
    uRLConnection.setReadTimeout(READ_TIMEOUT);
  }

  URLService(@NonNull URL uRL, @NonNull URI uRI) throws IOException, URISyntaxException {
    this(uRL.toURI().resolve(uRI).toURL());
  }

  @Nullable
  static Bitmap getBitmap(@NonNull byte[] bytes) {
    final BitmapFactory.Options options = new BitmapFactory.Options();
    options.inPreferredConfig = Bitmap.Config.ARGB_8888; // Enable transparency
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
  }

  static boolean isPng(@NonNull byte[] bytes) {
    return (bytes.length >= PNG_SIGNATURE.length) &&
      Arrays.equals(Arrays.copyOf(bytes, PNG_SIGNATURE.length), PNG_SIGNATURE);
  }

  // Charset from Content-Type parameter (e.g. text/xml; charset="utf-8"), UTF-8 by default.
  // Note: Content-Encoding is a transfer compression (gzip...), not a charset.
  @NonNull
  private static Charset getCharset(@Nullable String contentType) {
    if (contentType != null) {
      for (final String parameter : contentType.split(";")) {
        final String[] keyValue = parameter.trim().split("=", 2);
        if ((keyValue.length == 2) && keyValue[0].trim().equalsIgnoreCase("charset")) {
          try {
            return Charset.forName(keyValue[1].trim().replace("\"", ""));
          } catch (IllegalArgumentException illegalArgumentException) {
            break; // Unknown charset
          }
        }
      }
    }
    return StandardCharsets.UTF_8;
  }

  // Ignore case
  @Nullable
  String getTag(@NonNull String key) {
    return tags.get(key.toLowerCase(Locale.ROOT));
  }

  @NonNull
  URL getURL() {
    return uRLConnection.getURL();
  }

  void clearTags() {
    tags.clear();
  }

  @NonNull
  byte[] fetchBytes() throws IOException {
    try (final InputStream inputStream = getInputStream()) {
      final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
      final byte[] buffer = new byte[BUFFER_SIZE];
      int length;
      while ((length = inputStream.read(buffer)) >= 0) {
        outputStream.write(buffer, 0, length);
      }
      return outputStream.toByteArray();
    }
  }

  @NonNull
  URLService fetchContent() throws IOException {
    final byte[] bytes = fetchBytes(); // Connects, so headers are available below
    content = new String(bytes, getCharset(uRLConnection.getContentType()));
    return this;
  }

  // Calls asset on START_TAG, END_TAG.
  // XML contents may be handled with getTag, clearTags.
  // fetchContent() must be called before parseXml().
  void parseXml(@NonNull Asset asset) throws XmlPullParserException, IOException {
    if (content == null) {
      throw new IllegalStateException("fetchContent() must be called before parseXml()");
    }
    final XmlPullParserFactory xmlPullParserFactory = XmlPullParserFactory.newInstance();
    xmlPullParserFactory.setNamespaceAware(true);
    final XmlPullParser xmlPullParser = xmlPullParserFactory.newPullParser();
    xmlPullParser.setInput(new StringReader(content));
    int eventType = xmlPullParser.getEventType();
    String currentTag = null;
    while (eventType != XmlPullParser.END_DOCUMENT) {
      switch (eventType = xmlPullParser.next()) {
        case XmlPullParser.START_TAG:
          asset.startAccept(this, currentTag = xmlPullParser.getName());
          break;
        case XmlPullParser.TEXT:
          if (currentTag != null) {
            tags.put(currentTag.toLowerCase(Locale.ROOT), xmlPullParser.getText());
            // Tag processed
            currentTag = null;
          }
          break;
        case XmlPullParser.END_TAG:
          asset.endAccept(this, xmlPullParser.getName());
          break;
        default:
          // Nothing to do
      }
    }
    asset.endParseAccept(this);
  }

  @NonNull
  private InputStream getInputStream() throws IOException {
    if (streamConsumed) {
      throw new IllegalStateException("URLConnection stream already consumed; create a new URLService instance");
    }
    streamConsumed = true;
    return uRLConnection.getInputStream();
  }
}