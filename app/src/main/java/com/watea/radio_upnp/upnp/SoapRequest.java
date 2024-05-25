package com.watea.radio_upnp.upnp;

import androidx.annotation.NonNull;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapPrimitive;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;

public class SoapRequest {
  String SOAP_ACTION_BASE = "urn:schemas-upnp-org:service:AVTransport:1"; // + actionName
  String NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
  String METHOD_NAME = "methodName";
  String PARAMETER_NAME = "parameterName";
  String VALUE = "value";


  public void call(
    @NonNull String stringURL,
    @NonNull String action
    //@NonNull String parameter,
    //@NonNull String parameterValue
  ) {
    SoapObject soapObject = new SoapObject(SOAP_ACTION_BASE, action);


//    PropertyInfo propertyInfo = new PropertyInfo();
//    propertyInfo.setName(PARAMETER_NAME);
//    propertyInfo.setValue(VALUE);
//    propertyInfo.setType(String.class);
//
//    soapObject.addProperty(propertyInfo);

    SoapSerializationEnvelope envelope = new SoapSerializationEnvelope(SoapEnvelope.VER11);
    envelope.setOutputSoapObject(soapObject);


    HttpTransportSE httpTransportSE = new HttpTransportSE(stringURL);
    httpTransportSE.debug = true;

    try {
      httpTransportSE.call(SOAP_ACTION_BASE + "#" + action, envelope);
      String dump = httpTransportSE.requestDump;
      System.out.println(dump);
      SoapPrimitive soapPrimitive = (SoapPrimitive) envelope.getResponse();
      String result = soapPrimitive.toString();
      System.out.println(result);
    } catch (Exception e) {
      String dump = httpTransportSE.requestDump;
      System.out.println(dump);
      System.out.println(e);
    }
  }
}
