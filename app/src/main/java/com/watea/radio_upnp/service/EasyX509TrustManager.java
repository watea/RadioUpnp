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

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

@SuppressLint("CustomX509TrustManager")
public class EasyX509TrustManager implements X509TrustManager {
  @NonNull
  private final X509TrustManager standardTrustManager;

  public EasyX509TrustManager() throws NoSuchAlgorithmException, KeyStoreException {
    super();
    TrustManagerFactory factory =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    factory.init((KeyStore) null);
    TrustManager[] trustManagers = factory.getTrustManagers();
    if (trustManagers.length == 0) {
      throw new NoSuchAlgorithmException("No trust manager found");
    }
    standardTrustManager = (X509TrustManager) trustManagers[0];
  }

  @Override
  public void checkClientTrusted(X509Certificate[] certificates, String authType)
    throws java.security.cert.CertificateException {
    standardTrustManager.checkClientTrusted(certificates, authType);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] certificates, String authType)
    throws CertificateException {
    try {
      standardTrustManager.checkServerTrusted(certificates, authType);
    } catch (CertificateException certificateException) {
      if ((certificates != null) && (certificates.length == 1)) {
        // Simply accept valid certificate
        certificates[0].checkValidity();
      } else {
        throw certificateException;
      }
    }
  }

  @NonNull
  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return standardTrustManager.getAcceptedIssuers();
  }
}