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

package com.watea.radio_upnp.service;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

public class RadioURL {
  private static final String LOG_TAG = RadioURL.class.getName();
  private static final int CONNECTION_TRY = 3;
  private static final int CONNECT_TIMEOUT =
    DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS * 2;
  private static final int READ_TIMEOUT = DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS * 2;
  // Create the SSL connection for HTTPS
  private static final SSLSocketFactory sSLSocketFactory;

  static {
    SSLContext sSLContext = null;
    try {
      sSLContext = SSLContext.getInstance("TLS");
      sSLContext.init(
        null, new TrustManager[]{new EasyX509TrustManager()}, new java.security.SecureRandom());
    } catch (KeyManagementException | NoSuchAlgorithmException | KeyStoreException exception) {
      Log.i(LOG_TAG, "Error handling SSL connection", exception);
    }
    sSLSocketFactory = (sSLContext == null) ? null : sSLContext.getSocketFactory();
  }

  @Nullable
  private final URL uRL;

  public RadioURL(@Nullable URL uRL) {
    this.uRL = uRL;
  }

  @Nullable
  public String getStreamContentType() {
    String streamContent = null;
    HttpURLConnection httpURLConnection = null;
    try {
      httpURLConnection = getActualHttpURLConnection();
      streamContent = httpURLConnection.getHeaderField("Content-Type");
      // If we get there, connection has occurred
      Log.d(LOG_TAG, "Connection status/contentType: " +
        httpURLConnection.getResponseCode() + "/" + streamContent);
    } catch (IOException iOException) {
      // Fires also in case of timeout
      Log.i(LOG_TAG, "URL IO exception: " + uRL, iOException);
    } finally {
      if (httpURLConnection != null) {
        httpURLConnection.disconnect();
      }
    }
    return streamContent;
  }

  @NonNull
  public HttpURLConnection getActualHttpURLConnection() throws IOException {
    return getActualHttpURLConnection(null);
  }

  // Handle redirection
  // Consumer sets connection headers
  @NonNull
  public HttpURLConnection getActualHttpURLConnection(
    @Nullable HttpURLConnectionConsumer httpURLConnectionConsumer) throws IOException {
    if (uRL == null) {
      throw new IOException("getActualHttpURLConnection: URL is null");
    }
    HttpURLConnection httpURLConnection;
    int connectionTry = 0;
    URL uRL = this.uRL;
    do {
      // Set headers
      httpURLConnection = (HttpURLConnection) uRL.openConnection();
      httpURLConnection.setConnectTimeout(CONNECT_TIMEOUT);
      httpURLConnection.setReadTimeout(READ_TIMEOUT);
      httpURLConnection.setInstanceFollowRedirects(true);
      if (httpURLConnection instanceof HttpsURLConnection) {
        ((HttpsURLConnection) httpURLConnection).setSSLSocketFactory(sSLSocketFactory);
      }
      if (httpURLConnectionConsumer != null) {
        httpURLConnectionConsumer.accept(httpURLConnection);
      }
      // Get answer
      if (httpURLConnection.getResponseCode() / 100 == 3) {
        uRL = new URL(httpURLConnection.getHeaderField("Location"));
        Log.i(LOG_TAG, "Redirecting to URL: " + uRL);
      } else {
        Log.i(LOG_TAG, "Connection to URL: " + uRL);
        break;
      }
    } while (connectionTry++ < CONNECTION_TRY);
    return httpURLConnection;
  }

  @Nullable
  public Bitmap getBitmap() {
    HttpURLConnection httpURLConnection = null;
    Bitmap bitmap = null;
    try {
      httpURLConnection = getActualHttpURLConnection();
      bitmap = BitmapFactory.decodeStream(httpURLConnection.getInputStream());
    } catch (IOException iOException) {
      Log.i(LOG_TAG, "Error decoding image: " + uRL, iOException);
    } finally {
      if (httpURLConnection != null) {
        httpURLConnection.disconnect();
      }
    }
    return bitmap;
  }

  public interface HttpURLConnectionConsumer {
    void accept(@NonNull HttpURLConnection httpURLConnection) throws IOException;
  }
}