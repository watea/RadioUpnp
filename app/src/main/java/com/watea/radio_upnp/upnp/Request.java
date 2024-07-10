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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public abstract class Request {
  private final String LOG_TAG = Request.class.getName();
  private final String ENVELOPE_NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
  @NonNull
  private final Service service;
  @NonNull
  private final String action;
  // List as parameter must follow an order
  private final List<UpnpAction.Argument> properties = new Vector<>();

  public Request(
    @NonNull Service service,
    @NonNull String action,
    @NonNull List<UpnpAction.Argument> properties) {
    this.service = service;
    this.action = action;
    this.properties.addAll(properties);
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
      Log.d(LOG_TAG, "call: action => " + action);
    } catch (IOException ioException) {
      onFailure("SOAP request exception", ioException.toString());
      return;
    }
    // Read the response
    try (InputStream responseStream = httpURLConnection.getInputStream()) {
      final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true); // Necessary!!
      final DocumentBuilder builder = factory.newDocumentBuilder();
      final Document document = builder.parse(responseStream);
      httpURLConnection.disconnect();
      // Fault?
      final NodeList faultNode = document.getElementsByTagNameNS(ENVELOPE_NAMESPACE, "Fault");
      if (faultNode.getLength() > 0) {
        // Get the first element
        final Element faultElement = (Element) faultNode.item(0);
        // TODO: à tester
        final NodeList detailNodes = faultElement.getElementsByTagName("detail");
        onFailure(
          faultElement.getAttribute("faultcode"),
          faultElement.getAttribute("faultstring") + " [" + getFaultDetail(detailNodes) + "]");
        return;
      }
      // Get the action response element
      final NodeList responseNodes =
        document.getElementsByTagNameNS(serviceType, action + "Response");
      final Map<String, String> responses = new HashMap<>();
      if (responseNodes.getLength() > 0) {
        // Get the first element
        final Node node = responseNodes.item(0);
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
      }
      onSuccess(responses);
    } catch (Exception exception) {
      onFailure("SOAP exception", exception.toString());
    }
  }

  public abstract void onSuccess(@NonNull Map<String, String> responses);

  public abstract void onFailure(@NonNull String faultCode, @NonNull String faultString);

  private @NonNull StringBuilder getSoapBody(@NonNull String serviceType) {
    final StringBuilder soapBody = new StringBuilder();
    soapBody.append(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
          "<s:Envelope xmlns:s=\"" + ENVELOPE_NAMESPACE + "\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">\n" +
          "  <s:Body>\n" +
          "    <u:")
      .append(action)
      .append(" xmlns:u=\"")
      .append(serviceType)
      .append("\">\n");
    for (UpnpAction.Argument upnpActionArgument : properties) {
      soapBody.append("      <")
        .append(upnpActionArgument.getKey())
        .append(">")
        .append(upnpActionArgument.getValue())
        .append("</")
        .append(upnpActionArgument.getKey())
        .append(">\n");
      Log.d(LOG_TAG, "call: action property => " + upnpActionArgument.getKey() + "/" + upnpActionArgument.getValue());
    }
    soapBody
      .append("    </u:").append(action)
      .append(">\n")
      .append("  </s:Body>\n")
      .append("</s:Envelope>");
    return soapBody;
  }

  @NonNull
  private String getFaultDetail(@NonNull NodeList detailNodes) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < detailNodes.getLength(); i++) {
      final Element detailElement = (Element) detailNodes.item(i);
      result
        .append("(")
        .append(detailElement.getTagName())
        .append("/")
        .append(detailElement.getTextContent())
        .append(")");
    }
    return result.toString();
  }
}