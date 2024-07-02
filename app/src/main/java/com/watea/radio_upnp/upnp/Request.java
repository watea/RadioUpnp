package com.watea.radio_upnp.upnp;

import android.util.Log;

import androidx.annotation.NonNull;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.SoapFault;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;

import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;

public abstract class Request {
  private static final String SOAP_ACTION_SHARP = "#";
  private final String LOG_TAG = Request.class.getName();
  private final Service service;
  private final String action;
  // List as parameter must follow an order
  private final List<String[]> properties = new Vector<>();

  // properties must have 2 String; not checked here
  public Request(
    @NonNull Service service,
    @NonNull String action,
    @NonNull List<String[]> properties) {
    this.service = service;
    this.action = action;
    this.properties.addAll(properties);
  }

  public void call() {
    final String serviceType = service.getServiceType();
    // Build soapObject
    final SoapObject soapObject = new SoapObject(serviceType, action);
    Log.d(LOG_TAG, "call: action => " + action);
    properties.forEach(property -> {
      final String name = property[0];
      final String value = property[1];
      Log.d(LOG_TAG, "call: action property => " + name + "/" + value);
      soapObject.addProperty(name, value);
    });
    // Build envelope
    final SoapSerializationEnvelope envelope = new SoapSerializationEnvelope(SoapEnvelope.VER11);
    envelope.setOutputSoapObject(soapObject);
    envelope.dotNet = false; // For UPnP
    // Build httpTransportSE
    final String url;
    try {
      url = service.getActualControlURI().toString();
    } catch (URISyntaxException uRISyntaxException) {
      onFailure("Exception", uRISyntaxException.toString());
      return;
    }
    final HttpTransportSE httpTransportSE = new HttpTransportSE(url);
    httpTransportSE.debug = true;
    try {
      httpTransportSE.call(serviceType + SOAP_ACTION_SHARP + action, envelope);
      final Object bodyIn = envelope.bodyIn;
      if (bodyIn instanceof SoapFault) {
        final SoapFault soapFault = (SoapFault) bodyIn;
        onFailure(soapFault.faultcode, soapFault.faultstring);
      } else {
        final Set<PropertyInfo> result = new HashSet<>();
        final SoapObject response = (SoapObject) envelope.bodyIn;
        for (int i = 0; i < response.getPropertyCount(); i++) {
          final PropertyInfo propertyInfo = new PropertyInfo();
          response.getPropertyInfo(i, propertyInfo);
          result.add(propertyInfo);
          final String name = propertyInfo.getName();
          final String value = response.getProperty(i).toString();
          Log.d(LOG_TAG, "call: response item => " + name + ": " + value);
        }
        onSuccess(result);
      }
    } catch (Exception exception) {
      onFailure("SOAP Exception", exception.toString());
    }
  }

  public abstract void onSuccess(@NonNull Set<PropertyInfo> result);

  public abstract void onFailure(@NonNull String faultCode, @NonNull String faultString);
}