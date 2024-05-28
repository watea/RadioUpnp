package com.watea.radio_upnp.upnp;

import androidx.annotation.NonNull;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.SoapFault;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapPrimitive;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;

import java.net.URISyntaxException;
import java.util.List;
import java.util.Map;
import java.util.Vector;

public abstract class UpnpRequest {
  private static final String SOAP_ACTION_SHARP = "#";
  private final Service service;
  private final String action;
  private final Map<String, String> propertyMap;
  private final List<SoapPrimitive> result = new Vector<>();
  private final SoapFault soapFault = new SoapFault();

  public UpnpRequest(
    @NonNull Service service,
    @NonNull String action,
    @NonNull Map<String, String> propertyMap) {
    this.service = service;
    this.action = action;
    this.propertyMap = propertyMap;
    soapFault.faultstring = "Result is null";
  }

  public void call() throws URISyntaxException {
    final String serviceType = service.getServiceType();
    // Build soapObject
    final SoapObject soapObject = new SoapObject(serviceType, action);
    propertyMap.forEach(soapObject::addProperty);
    // Build envelope
    final SoapSerializationEnvelope envelope = new SoapSerializationEnvelope(SoapEnvelope.VER11);
    envelope.setOutputSoapObject(soapObject);
    // Build httpTransportSE
    final HttpTransportSE httpTransportSE =
      new HttpTransportSE(service.getActualControlURI().toString());
    //httpTransportSE.debug = true;
    try {
      httpTransportSE.call(serviceType + SOAP_ACTION_SHARP + action, envelope);
      final Object result = envelope.getResponse();
      if (result == null) {
        onFailure(soapFault);
      } else if (result instanceof SoapPrimitive) {
        this.result.add((SoapPrimitive) result);
      } else if (result instanceof Vector<?>) {
        ((Vector<?>) result)
          .forEach(soapPrimitive -> this.result.add((SoapPrimitive) soapPrimitive));
      }
      onSuccess(this.result);
    } catch (Exception exception) {
      soapFault.faultstring = "Exception occurred: " + exception;
      onFailure(soapFault);
    }
  }

  public abstract void onSuccess(@NonNull List<SoapPrimitive> result);

  public abstract void onFailure(@NonNull SoapFault soapFault);
}