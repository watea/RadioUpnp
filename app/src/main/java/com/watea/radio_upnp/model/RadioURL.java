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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

public class RadioURL {
  private static final String LOG_TAG = RadioURL.class.getSimpleName();
  private static final String GET = "GET";
  private static final Pattern ICON_PATTERN = Pattern.compile(
    "https?://[-A-Za-z\\d+&@#%?=~_|!:,.;/]+\\.(png|jpg|jpeg|ico|gif|svg)",
    Pattern.CASE_INSENSITIVE);
  private static final int CONNECTION_TRY = 3;
  private static final int READ_TIMEOUT = 10000; // ms
  private static final int CONNECTION_TIMEOUT = 5000; // ms
  // Create the SSL connection for HTTPS
  private static final SSLSocketFactory sSLSocketFactory;

  static {
    SSLContext sSLContext = null;
    try {
      sSLContext = SSLContext.getInstance("TLS");
      sSLContext.init(null, new TrustManager[]{new EasyX509TrustManager()}, new java.security.SecureRandom());
    } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException exception) {
      Log.e(LOG_TAG, "Internal failure: error handling SSL connection", exception);
    }
    sSLSocketFactory = (sSLContext == null) ? null : sSLContext.getSocketFactory();
  }

  @Nullable
  private final URL uRL;

  public RadioURL(@Nullable URL uRL) {
    this.uRL = uRL;
  }

  @Nullable
  public static String getLocation(@NonNull HttpURLConnection httpURLConnection) {
    return httpURLConnection.getHeaderField("Location");
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

  // MIME type
  @Nullable
  public static String getStreamContentType(@NonNull HttpURLConnection httpURLConnection) throws IOException {
    String contentType = httpURLConnection.getContentType();
    // If we get there, connection has occurred.
    // Content-Type first asset is MIME type.
    if (contentType != null) {
      contentType = contentType.split(";")[0];
    }
    Log.d(LOG_TAG, "Connection status/ContentType: " +
      httpURLConnection.getResponseCode() + "/" +
      ((contentType == null) ? "No ContentType" : contentType));
    return contentType;
  }

  @NonNull
  public HttpURLConnection getActualHttpURLConnection(@NonNull String userAgent) throws IOException {
    return getActualHttpURLConnection(userAgent, Collections.emptyMap());
  }

  // Handle redirection
  @NonNull
  public HttpURLConnection getActualHttpURLConnection(@NonNull String userAgent, @NonNull Map<String, String> requestProperties) throws IOException {
    if (uRL == null) {
      throw new IOException("getActualHttpURLConnection: URL is null");
    }
    final URL originalURL = this.uRL;
    URL currentURL = originalURL;
    String pendingMethod = GET; // HTTP method to use on next connection
    final Set<String> visitedURLs = new HashSet<>();
    Log.d(LOG_TAG, "Try connect to URL: " + currentURL);
    for (int connectionTry = 1; connectionTry <= CONNECTION_TRY; connectionTry++) {
      // Guard against redirect loops (A→B→A)
      if (!visitedURLs.add(currentURL.toString())) {
        throw new IOException("getActualHttpURLConnection: redirect loop detected: " + currentURL + " (original: " + originalURL + ")");
      }
      // Open connection
      final URLConnection uRLConnection = currentURL.openConnection();
      if (!(uRLConnection instanceof HttpURLConnection)) {
        throw new IOException("getActualHttpURLConnection: URL is not HTTP: " + currentURL);
      }
      final HttpURLConnection httpURLConnection = (HttpURLConnection) uRLConnection;
      boolean success = false;
      try {
        // Set timeouts
        httpURLConnection.setConnectTimeout(CONNECTION_TIMEOUT);
        httpURLConnection.setReadTimeout(READ_TIMEOUT);
        httpURLConnection.setInstanceFollowRedirects(false);
        // Restore method (relevant for 307/308 chains)
        httpURLConnection.setRequestMethod(pendingMethod);
        // TLS support
        if (httpURLConnection instanceof HttpsURLConnection) {
          ((HttpsURLConnection) httpURLConnection).setSSLSocketFactory(sSLSocketFactory);
        }
        // Parameters
        httpURLConnection.setRequestProperty("User-Agent", userAgent);
        httpURLConnection.setRequestProperty("Icy-Metadata", "1");
        requestProperties.forEach(httpURLConnection::setRequestProperty);
        // Get HTTP response
        final int responseCode;
        try {
          responseCode = httpURLConnection.getResponseCode();
          Log.d(LOG_TAG, "HTTP " + responseCode + " for URL: " + currentURL);
        } catch (IllegalArgumentException illegalArgumentException) {
          throw new IOException("getActualHttpURLConnection: invalid host in URL: " + currentURL, illegalArgumentException);
        }
        // Check for redirect
        final boolean isRedirect =
          (responseCode == HttpURLConnection.HTTP_MULT_CHOICE)     // 300
            || (responseCode == HttpURLConnection.HTTP_MOVED_PERM) // 301
            || (responseCode == HttpURLConnection.HTTP_MOVED_TEMP) // 302
            || (responseCode == HttpURLConnection.HTTP_SEE_OTHER)  // 303
            || (responseCode == 307)                               // 307
            || (responseCode == 308);                              // 308
        if (!isRedirect) {
          success = true; // caller takes ownership, do NOT disconnect in finally
          return httpURLConnection;
        }
        // --- Handle redirect ---
        String location = getLocation(httpURLConnection);
        if ((location == null) || location.trim().isEmpty()) {
          throw new IOException("getActualHttpURLConnection: redirect without Location from: " + currentURL + " (original: " + originalURL + ")");
        }
        location = location.trim();
        final URL nextURL;
        try {
          nextURL = new URL(currentURL, location);
        } catch (MalformedURLException malformedURLException) {
          throw new IOException("getActualHttpURLConnection: malformed Location '" + location + "' from: " + currentURL, malformedURLException);
        }
        // 307/308 → preserve method; 301/302/303 → reset to GET (RFC 7231)
        pendingMethod = (responseCode == 307 || responseCode == 308) ? pendingMethod : GET;
        Log.d(LOG_TAG, "Redirect " + connectionTry + " [" + responseCode + "] [" + pendingMethod + "]: " + currentURL + " → " + nextURL);
        currentURL = nextURL;
        // fall through to finally → disconnect, then loop
      } finally {
        if (!success) {
          httpURLConnection.disconnect(); // release socket on redirect or any exception
        }
      }
    }
    throw new IOException("getActualHttpURLConnection: too many redirects, last URL: " + currentURL + " (original: " + originalURL + ")");
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