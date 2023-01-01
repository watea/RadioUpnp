/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.watea.radio_upnp.cling;

import static com.google.common.net.HttpHeaders.CONTENT_LENGTH;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;

import android.util.Log;

import androidx.annotation.NonNull;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.fourthline.cling.model.message.StreamRequestMessage;
import org.fourthline.cling.model.message.StreamResponseMessage;
import org.fourthline.cling.model.message.UpnpHeaders;
import org.fourthline.cling.model.message.UpnpMessage;
import org.fourthline.cling.model.message.UpnpRequest;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.header.ContentTypeHeader;
import org.fourthline.cling.transport.spi.AbstractStreamClient;
import org.fourthline.cling.transport.spi.InitializationException;
import org.seamless.util.MimeType;

import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Implementation based on Jetty 9 client API.
 * <p>
 * This implementation works on Android, dependencies are the <code>jetty-client</code>
 * Maven module.
 * </p>
 *
 * @author Christian Bauer
 */
public class StreamClient
  extends AbstractStreamClient<StreamClientConfiguration, StreamClient.HttpContentExchange> {
  private static final String LOG_TAG = StreamClient.class.getName();
  @NonNull
  protected final StreamClientConfiguration configuration;
  @NonNull
  protected final HttpClient client;

  public StreamClient(@NonNull StreamClientConfiguration configuration)
    throws InitializationException {
    this.configuration = configuration;
    Log.i(LOG_TAG, "Starting Jetty HttpClient...");
    client = new HttpClient();
    client.setConnectTimeout((configuration.getTimeoutSeconds() + 5) * 1000L);
    try {
      client.start();
    } catch (Exception exception) {
      throw new InitializationException("Could not start Jetty HttpClient", exception);
    }
  }

  @Override
  public void stop() {
    try {
      client.stop();
    } catch (Exception exception) {
      Log.i(LOG_TAG, "Error stopping Jetty HttpClient", exception);
    }
  }

  @NonNull
  @Override
  public StreamClientConfiguration getConfiguration() {
    return configuration;
  }

  @NonNull
  @Override
  protected HttpContentExchange createRequest(StreamRequestMessage requestMessage) {
    return new HttpContentExchange(client, requestMessage);
  }

  @NonNull
  @Override
  protected Callable<StreamResponseMessage> createCallable(
    final StreamRequestMessage requestMessage, final HttpContentExchange exchange) {
    return new Callable<StreamResponseMessage>() {
      public StreamResponseMessage call() throws Exception {
        Log.d(LOG_TAG, "Sending HTTP request: " + requestMessage);
        final StreamResponseMessage[] streamResponseMessage = new StreamResponseMessage[1];
        streamResponseMessage[0] = null;
        exchange.getRequest().send(new BufferingResponseListener() {
          @Override
          public void onComplete(Result result) {
            synchronized (exchange) {
              exchange.notifyAll();
            }
          }

          @Override
          public void onSuccess(Response response) {
            streamResponseMessage[0] = createStreamResponseMessage(response, getContent());
          }

          @Override
          public void onFailure(Response response, Throwable failure) {
            Log.w(LOG_TAG, "Error reading response: " + requestMessage, failure);
          }
        });
        synchronized (exchange) {
          exchange.wait();
        }
        return streamResponseMessage[0];
      }

      private StreamResponseMessage createStreamResponseMessage(Response response, byte[] content) {
        // Status
        final int status = response.getStatus();
        final UpnpResponse.Status upnpStatus = UpnpResponse.Status.getByStatusCode(status);
        assert upnpStatus != null;
        final UpnpResponse responseOperation = new UpnpResponse(status, upnpStatus.getStatusMsg());
        Log.d(LOG_TAG, "Received response: " + responseOperation);
        final StreamResponseMessage responseMessage = new StreamResponseMessage(responseOperation);
        // -- Headers --
        final UpnpHeaders headers = new UpnpHeaders();
        final HttpFields responseFields = response.getHeaders();
        for (String name : responseFields.getFieldNamesCollection()) {
          for (String value : responseFields.getValuesList(name)) {
            headers.add(name, value);
          }
        }
        responseMessage.setHeaders(headers);
        // -- Body --
        if ((content == null) || (content.length <= 0)) {
          Log.d(LOG_TAG, "Response did not contain entity body");
        } else {
          if (responseMessage.isContentTypeMissingOrText()) {
            Log.d(LOG_TAG,
              "Response contains textual entity body, converting then setting string on message");
            try {
              responseMessage.setBodyCharacters(content);
            } catch (UnsupportedEncodingException unsupportedEncodingException) {
              throw new RuntimeException(
                "Unsupported character encoding: ", unsupportedEncodingException);
            }
          } else {
            Log.d(LOG_TAG, "Response contains binary entity body, setting bytes on message");
            responseMessage.setBody(UpnpMessage.BodyType.BYTES, content);
          }
        }
        Log.d(LOG_TAG, "Response message complete: " + responseMessage);
        return responseMessage;
      }
    };
  }

  @Override
  protected void abort(HttpContentExchange exchange) {
    exchange.getRequest().abort(new Throwable("Abort UPnP request"));
  }

  @Override
  protected boolean logExecutionException(Throwable t) {
    return false;
  }

  public static class HttpContentExchange {
    @NonNull
    final protected Request request;

    public HttpContentExchange(
      @NonNull HttpClient client, @NonNull StreamRequestMessage requestMessage) {
      final UpnpRequest requestOperation = requestMessage.getOperation();
      request = client.newRequest(requestOperation.getURI());
      // -- Method --
      final String method = requestOperation.getHttpMethodName();
      Log.d(LOG_TAG, "Preparing HTTP request message with method '"
        + method + "': " + requestMessage);
      request.method(method);
      // -- Headers --
      final UpnpHeaders headers = requestMessage.getHeaders();
      Log.d(LOG_TAG, "Writing headers on HttpContentExchange: " + headers.size());
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        for (String v : entry.getValue()) {
          final String headerName = entry.getKey();
          Log.d(LOG_TAG, "Setting header '" + headerName + "': " + v);
          request.header(headerName, v);
        }
      }
      // -- Body --
      if (requestMessage.hasBody()) {
        byte[] buffer;
        if (requestMessage.getBodyType() == UpnpMessage.BodyType.STRING) {
          Log.d(LOG_TAG, "Writing textual request body: " + requestMessage);
          final MimeType contentType = (requestMessage.getContentTypeHeader() == null) ?
            ContentTypeHeader.DEFAULT_CONTENT_TYPE_UTF8 :
            requestMessage.getContentTypeHeader().getValue();
          final String charset = (requestMessage.getContentTypeCharset() == null) ?
            "UTF-8" :
            requestMessage.getContentTypeCharset();
          request.header(CONTENT_TYPE, contentType.toString());
          try {
            buffer = requestMessage.getBodyString().getBytes(charset);
          } catch (UnsupportedEncodingException exception) {
            throw new RuntimeException("Unsupported character encoding: " + charset, exception);
          }
        } else {
          Log.d(LOG_TAG, "Writing binary request body: " + requestMessage);
          if (requestMessage.getContentTypeHeader() == null)
            throw new RuntimeException(
              "Missing content type header in request message: " + requestMessage);
          final MimeType contentType = requestMessage.getContentTypeHeader().getValue();
          request.header(CONTENT_TYPE, contentType.toString());
          buffer = requestMessage.getBodyBytes();
          request.header(CONTENT_LENGTH, String.valueOf(buffer.length));
        }
        request.content(new BytesContentProvider(buffer));
      }
    }

    @NonNull
    public Request getRequest() {
      return request;
    }
  }
}