/*
 * Copyright (c) 2018-2026. Stephane Treuchot
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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RadioURL {
  private static final String LOG_TAG = RadioURL.class.getSimpleName();
  private static final Pattern ICON_PATTERN = Pattern.compile(
    "https?://[-A-Za-z\\d+&@#%?=~_|!:,.;/]+\\.(png|jpg|jpeg|ico|gif|svg)",
    Pattern.CASE_INSENSITIVE);
  private static final int READ_TIMEOUT = 10000; // ms
  private static final int CONNECTION_TIMEOUT = 5000; // ms
  @NonNull
  private static final OkHttpClient OK_HTTP_CLIENT;

  static {
    OkHttpClient client;
    try {
      final EasyX509TrustManager trustManager = new EasyX509TrustManager();
      client = new OkHttpClient.Builder()
        .sslSocketFactory(EasyX509TrustManager.getSSLSocketFactory(trustManager), trustManager)
        .connectTimeout(CONNECTION_TIMEOUT, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.MILLISECONDS)
        .build();
    } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException exception) {
      Log.e(LOG_TAG, "Internal failure: error handling SSL connection", exception);
      client = new OkHttpClient();
    }
    OK_HTTP_CLIENT = client;
  }

  @Nullable
  private final URL uRL;

  public RadioURL(@Nullable URL uRL) {
    this.uRL = uRL;
  }

  @Nullable
  public static Bitmap iconSearch(@NonNull URL uRL) {
    Bitmap result = null;
    try {
      final Element head = Jsoup.connect(uRL.toString()).get().head();
      final List<String> candidateUrls = new ArrayList<>();
      // Extract link[rel=icon]
      head.select("link[rel~=(?i)icon]").forEach(link -> {
        String href = link.attr("abs:href");
        if (!href.isEmpty()) {
          candidateUrls.add(href);
        }
      });
      // Search icons in all head elements
      for (Element element : head.getAllElements()) {
        if (element == head) {
          continue;
        }
        final String string = element.toString();
        if (string.length() > 4096) {
          continue;
        }
        final Matcher matcher = ICON_PATTERN.matcher(string);
        while (matcher.find()) {
          candidateUrls.add(matcher.group());
        }
      }
      // Download icons one by one, synchronously
      for (final String iconUrl : candidateUrls) {
        try {
          final Bitmap bitmap = new RadioURL(new URL(iconUrl)).getBitmap();
          if ((bitmap != null) && ((result == null) || (bitmap.getByteCount() > result.getByteCount()))) {
            result = bitmap;
          }
        } catch (Exception exception) {
          Log.d(LOG_TAG, "Failed to load icon: " + iconUrl, exception);
        }
      }
    } catch (Exception exception) {
      Log.d(LOG_TAG, "Error performing icon web site search for URL: " + uRL, exception);
    }
    return result;
  }

  // MIME type; does not throw since response code is already available
  @Nullable
  public static String getStreamContentType(@NonNull Response response) {
    String contentType = response.header("Content-Type");
    if (contentType != null) {
      contentType = contentType.split(";")[0];
    }
    Log.d(LOG_TAG, "Connection status/ContentType: " +
      response.code() + "/" +
      ((contentType == null) ? "No ContentType" : contentType));
    return contentType;
  }

  @NonNull
  public Response getActualOkHttpResponse(@NonNull String userAgent) throws IOException {
    return getActualOkHttpResponse(userAgent, Collections.emptyMap());
  }

  // OkHttpClient follows redirects automatically (RFC 7231 compliant).
  // Icy-Metadata is not added here; pass it via requestProperties only when streaming (relay mode).
  @NonNull
  public Response getActualOkHttpResponse(@NonNull String userAgent, @NonNull Map<String, String> requestProperties) throws IOException {
    if (uRL == null) {
      throw new IOException("getActualOkHttpResponse: URL is null");
    }
    Log.d(LOG_TAG, "Try connect to URL: " + uRL);
    final Request.Builder requestBuilder = new Request.Builder()
      .url(uRL.toString())
      .header("User-Agent", userAgent)
      .header("Accept-Encoding", "identity");
    requestProperties.forEach(requestBuilder::header);
    final Response response = OK_HTTP_CLIENT.newCall(requestBuilder.build()).execute();
    Log.d(LOG_TAG, "HTTP " + response.code() + " for URL: " + uRL);
    return response;
  }

  // Redirection will not be handled here
  @Nullable
  public Bitmap getBitmap() {
    if (uRL == null) {
      Log.e(LOG_TAG, "getBitmap: decoding image on null URL");
      return null;
    }
    try {
      return BitmapFactory.decodeStream(uRL.openConnection().getInputStream());
    } catch (Exception exception) {
      Log.d(LOG_TAG, "getBitmap: error decoding image on " + uRL, exception);
      return null;
    }
  }
}