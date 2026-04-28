/*
 * Copyright (c) 2018-2024. Stephane Treuchot
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

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public abstract class Request {
  private static final String LOG_TAG = Request.class.getSimpleName();
  private static final int TIMEOUT = 6000; // ms, for request connection and read
  private static final String SOAP_ENVELOPE_NS = "http://schemas.xmlsoap.org/soap/envelope/";
  private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY;

  static {
    DOCUMENT_BUILDER_FACTORY = DocumentBuilderFactory.newInstance();
    DOCUMENT_BUILDER_FACTORY.setNamespaceAware(true); // Necessary!!
  }

  @NonNull
  private final Action action;
  @NonNull
  private final RequestController requestController;
  // List as parameter must follow an order
  private final List<Argument> arguments = new ArrayList<>();
  private final Map<String, String> responses = new HashMap<>();

  public Request(
    @NonNull Action action,
    @NonNull RequestController requestController) {
    this.action = action;
    this.requestController = requestController;
  }

  public Request(
    @NonNull Action action,
    @NonNull RequestController requestController,
    @NonNull String instanceId) {
    this(action, requestController);
    addArgument("InstanceID", instanceId);
  }

  @NonNull
  public static String escapeXml(@NonNull String value) {
    return value
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;");
  }

  @NonNull
  private static String getElementValue(@NonNull Element parent, @NonNull String tagName) {
    final NodeList nodeList = parent.getElementsByTagName(tagName);
    return (nodeList.getLength() > 0) ? nodeList.item(0).getTextContent() : "";
  }

  @NonNull
  private static String getFaultDetail(@NonNull NodeList detailNodes) {
    if (detailNodes.getLength() > 0) {
      final Element element = (Element) detailNodes.item(0);
      final String ERROR_CODE = "errorCode";
      final String ERROR_DESCRIPTION = "errorDescription";
      return "(" + ERROR_CODE + ": " + getElementValue(element, ERROR_CODE) + ") (" + ERROR_DESCRIPTION + ": " + getElementValue(element, ERROR_DESCRIPTION) + ")";
    } else {
      return "No details";
    }
  }

  @NonNull
  public Request addArgument(@NonNull String name, @NonNull String value) {
    arguments.add(new Argument(name, value));
    return this;
  }

  public void execute() {
    Log.d(LOG_TAG, "execute: " + action.getName() + " on: " + action.getDevice().getDisplayString());
    final Service service = action.getService();
    final String name = action.getName();
    final URL url;
    try {
      url = service.getActualControlURI().toURL();
    } catch (MalformedURLException | URISyntaxException malformedURLException) {
      Log.d(LOG_TAG, "execute: " + name + " => " + malformedURLException);
      onFailure();
      return;
    }
    final HttpURLConnection httpURLConnection;
    final String serviceType = service.getServiceType();
    try {
      Log.d(LOG_TAG, "execute: " + name + " URL => " + url.toString());
      httpURLConnection = (HttpURLConnection) url.openConnection();
      httpURLConnection.setConnectTimeout(TIMEOUT);
      httpURLConnection.setReadTimeout(TIMEOUT);
      httpURLConnection.setRequestMethod("POST");
      httpURLConnection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
      httpURLConnection.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + name + "\"");
      httpURLConnection.setDoOutput(true);
    } catch (IOException ioException) {
      Log.d(LOG_TAG, "execute: " + name + " => " + ioException);
      onFailure();
      return;
    }
    try (final OutputStreamWriter writer =
           new OutputStreamWriter(httpURLConnection.getOutputStream(), StandardCharsets.UTF_8)) {
      writer.write(getSoapBody(serviceType, name).toString());
      writer.flush();
      final int responseCode = httpURLConnection.getResponseCode();
      Log.d(LOG_TAG, "execute: response is " + responseCode);
      final boolean isFailure = (responseCode < 200) || (responseCode >= 300);
      final Document document;
      try (final InputStream responseStream =
             isFailure ? httpURLConnection.getErrorStream() : httpURLConnection.getInputStream()) {
        if (responseStream == null) {
          Log.d(LOG_TAG, "execute: " + name + " => no response available");
          onFailure();
          return;
        }
        final DocumentBuilder builder = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder();
        document = builder.parse(responseStream);
      } catch (Exception exception) {
        Log.d(LOG_TAG, "execute: " + name + " => " + exception);
        onFailure();
        return;
      }
      if (isFailure) {
        final NodeList responseNodes = document.getElementsByTagNameNS(SOAP_ENVELOPE_NS, "Fault");
        final Element element =
          (responseNodes.getLength() > 0) ? (Element) responseNodes.item(0) : null;
        if (element == null) {
          Log.d(LOG_TAG, "execute: " + name + " => no failure element");
          onFailure();
          return;
        }
        final NodeList detailNodes = element.getElementsByTagName("detail");
        Log.d(LOG_TAG, "execute: " + name + " => " +
          getElementValue(element, "faultcode") + "/" +
          getElementValue(element, "faultstring") + "/" +
          getFaultDetail(detailNodes));
        onFailure();
        return;
      }
      final NodeList responseNodes =
        document.getElementsByTagNameNS(serviceType, name + "Response");
      final Node node = (responseNodes.getLength() > 0) ? responseNodes.item(0) : null;
      if (node == null) {
        Log.d(LOG_TAG, "execute: " + name + " => no response element");
        onFailure();
        return;
      }
      final Map<String, String> responseMap = new HashMap<>();
      final NodeList childNodes = node.getChildNodes();
      for (int i = 0; i < childNodes.getLength(); i++) {
        final Node childNode = childNodes.item(i);
        if (childNode.getNodeType() == Node.ELEMENT_NODE) {
          final String key = ((Element) childNode).getTagName();
          final String value = childNode.getTextContent();
          responseMap.put(key, value);
          Log.d(LOG_TAG, "execute: response item => " + key + ": " + value);
        }
      }
      Log.d(LOG_TAG, "execute: " + name + " => success");
      responses.putAll(responseMap);
      onSuccess();
    } catch (IOException ioException) {
      Log.d(LOG_TAG, "execute: " + name + " => " + ioException);
      onFailure();
    } finally {
      httpURLConnection.disconnect();
    }
  }

  public void ownThreadExecute() {
    new Thread(this::execute).start();
  }

  public void schedule() {
    requestController.schedule(this);
  }

  public boolean hasDevice(@NonNull Device device) {
    return action.getDevice().equals(device);
  }

  @Nullable
  protected String getResponse(@NonNull String name) {
    return responses.get(name);
  }

  // Run next by default
  protected void onSuccess() {
    requestController.runNextRequest();
  }

  // Run next by default
  protected void onFailure() {
    requestController.runNextRequest();
  }

  @NonNull
  private StringBuilder getSoapBody(@NonNull String serviceType, @NonNull String name) {
    final StringBuilder soapBody = new StringBuilder();
    soapBody.append(
        "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>" +
          "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
          "<s:Body>" +
          "<u:")
      .append(name)
      .append(" xmlns:u=\"")
      .append(serviceType)
      .append("\">");
    for (final Argument argument : arguments) {
      soapBody.append("<")
        .append(argument.getKey())
        .append(">")
        .append(escapeXml(argument.getValue()))
        .append("</")
        .append(argument.getKey())
        .append(">");
      Log.d(LOG_TAG, "execute: property => " + argument.getKey() + "/" + argument.getValue());
    }
    soapBody
      .append("</u:")
      .append(name)
      .append(">")
      .append("</s:Body>")
      .append("</s:Envelope>");
    return soapBody;
  }

  public static class Argument {
    @NonNull
    private final String key;
    @NonNull
    private final String value;

    public Argument(@NonNull String key, @NonNull String value) {
      this.key = key;
      this.value = value;
    }

    @NonNull
    public String getKey() {
      return key;
    }

    @NonNull
    public String getValue() {
      return value;
    }
  }
}