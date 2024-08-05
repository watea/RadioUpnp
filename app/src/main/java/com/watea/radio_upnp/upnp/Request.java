/*
 * Copyright (c) 2024. Stephane Treuchot
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public abstract class Request {
  private final String LOG_TAG = Request.class.getSimpleName();
  @NonNull
  private final Service service;
  @NonNull
  private final String action;
  // List as parameter must follow an order
  private final List<UpnpAction.Argument> properties = new ArrayList<>();

  public Request(
    @NonNull Service service,
    @NonNull String action,
    @NonNull List<UpnpAction.Argument> properties) {
    this.service = service;
    this.action = action;
    this.properties.addAll(properties);
  }

  @NonNull
  private static String getElementValue(Element parent, String tagName) {
    final NodeList nodeList = parent.getElementsByTagName(tagName);
    return (nodeList.getLength() > 0) ? nodeList.item(0).getTextContent() : "";
  }

  public void call() {
    final URL url;
    try {
      url = service.getActualControlURI().toURL();
    } catch (MalformedURLException | URISyntaxException malformedURLException) {
      onFailure("URL exception", malformedURLException.toString());
      return;
    }
    final HttpURLConnection httpURLConnection;
    final String serviceType = service.getServiceType();
    try {
      Log.d(LOG_TAG, "call: " + action + " URL => " + url.toString());
      httpURLConnection = (HttpURLConnection) url.openConnection();
      httpURLConnection.setRequestMethod("POST");
      httpURLConnection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
      httpURLConnection.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");
      httpURLConnection.setDoOutput(true);
    } catch (IOException ioException) {
      onFailure("SOAP connection exception", ioException.toString());
      return;
    }
    try (OutputStreamWriter writer = new OutputStreamWriter(httpURLConnection.getOutputStream())) {
      writer.write(getSoapBody(serviceType).toString());
      writer.flush();
      // Read the response
      final int responseCode = httpURLConnection.getResponseCode();
      // Success/Failure range
      final boolean isFailure = (responseCode < 200) || (responseCode >= 300);
      final InputStream responseStream =
        isFailure ? httpURLConnection.getErrorStream() : httpURLConnection.getInputStream();
      if (responseStream == null) {
        onFailure("SOAP connection error", "No response stream available");
        return;
      }
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true); // Necessary!!
      final DocumentBuilder builder = factory.newDocumentBuilder();
      final Document document = builder.parse(responseStream);
      responseStream.close();
      httpURLConnection.disconnect();
      // Get the action response element
      if (isFailure) {
        final NodeList responseNodes = document.getElementsByTagName("s:Fault");
        // Get the first element
        final Element element =
          (responseNodes.getLength() > 0) ? (Element) responseNodes.item(0) : null;
        if (element == null) {
          onFailure("SOAP response error", "No failure element available");
          return;
        }
        final NodeList detailNodes = element.getElementsByTagName("detail");
        onFailure(
          getElementValue(element, "faultcode"),
          getElementValue(element, "faultstring"),
          getFaultDetail(detailNodes));
        return;
      }
      final NodeList responseNodes =
        document.getElementsByTagNameNS(serviceType, action + "Response");
      // Get the first element
      final Node node = (responseNodes.getLength() > 0) ? responseNodes.item(0) : null;
      if (node == null) {
        onFailure("SOAP response error", "No response element available");
        return;
      }
      final Map<String, String> responses = new HashMap<>();
      // Get the child elements
      final NodeList childNodes = node.getChildNodes();
      for (int i = 0; i < childNodes.getLength(); i++) {
        final Node childNode = childNodes.item(i);
        if (childNode.getNodeType() == Node.ELEMENT_NODE) {
          final String key = ((Element) childNode).getTagName();
          final String value = childNode.getTextContent();
          responses.put(key, value);
          Log.d(LOG_TAG, "call: response item => " + key + ": " + value);
        }
      }
      onSuccess(responses);
    } catch (Exception exception) {
      onFailure("SOAP exception", exception.toString());
    }
  }

  public abstract void onSuccess(@NonNull Map<String, String> responses);

  public abstract void onFailure(
    @NonNull String faultCode, @NonNull String faultString, @NonNull String faultDetail);

  public void onFailure(@NonNull String faultCode, @NonNull String faultString) {
    onFailure(faultCode, faultString, "No");
  }

  private @NonNull StringBuilder getSoapBody(@NonNull String serviceType) {
    final StringBuilder soapBody = new StringBuilder();
    soapBody.append(
        "<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\"?>" +
          "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
          "<s:Body>" +
          "<u:")
      .append(action)
      .append(" xmlns:u=\"")
      .append(serviceType)
      .append("\">");
    for (UpnpAction.Argument upnpActionArgument : properties) {
      soapBody.append("<")
        .append(upnpActionArgument.getKey())
        .append(">")
        .append(upnpActionArgument.getValue())
        .append("</")
        .append(upnpActionArgument.getKey())
        .append(">");
      Log.d(LOG_TAG, "call: action property => " + upnpActionArgument.getKey() + "/" + upnpActionArgument.getValue());
    }
    soapBody
      .append("</u:")
      .append(action)
      .append(">")
      .append("</s:Body>")
      .append("</s:Envelope>");
    return soapBody;
  }

  @NonNull
  private String getFaultDetail(@NonNull NodeList detailNodes) {
    if (detailNodes.getLength() > 0) {
      final Element element = (Element) detailNodes.item(0);
      final String ERROR_CODE = "errorCode";
      final String ERROR_DESCRIPTION = "errorDescription";
      return "(" + ERROR_CODE + ": " + getElementValue(element, ERROR_CODE) + ") (" + ERROR_DESCRIPTION + ": " + getElementValue(element, ERROR_DESCRIPTION) + ")";
    } else {
      return "No details";
    }
  }
}