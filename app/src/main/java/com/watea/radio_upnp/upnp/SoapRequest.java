package com.watea.radio_upnp.upnp;

import org.ksoap2.SoapEnvelope;
import org.ksoap2.serialization.PropertyInfo;
import org.ksoap2.serialization.SoapObject;
import org.ksoap2.serialization.SoapPrimitive;
import org.ksoap2.serialization.SoapSerializationEnvelope;
import org.ksoap2.transport.HttpTransportSE;

import java.net.URL;

public class SoapRequest {
  String SOAP_ACTION = "urn:schemas-upnp-org:service:serviceType:v#actionName";
  String NAMESPACE = "http://schemas.xmlsoap.org/soap/envelope/";
  String METHOD_NAME = "methodName";
  String PARAMETER_NAME ="parameterName";
  String VALUE = "value";


  public void call() {
    String URL = "192.168.1.15";

    SoapObject soapObject = new SoapObject(NAMESPACE, METHOD_NAME);

    PropertyInfo propertyInfo = new PropertyInfo();
    propertyInfo.setName(PARAMETER_NAME);
    propertyInfo.setValue(VALUE);
    propertyInfo.setType(String.class);

    soapObject.addProperty(propertyInfo);

    SoapSerializationEnvelope envelope =  new SoapSerializationEnvelope(SoapEnvelope.VER11);
    envelope.setOutputSoapObject(soapObject);

    HttpTransportSE httpTransportSE = new HttpTransportSE(URL);

    try {
      httpTransportSE.call(SOAP_ACTION, envelope);
      String dump = httpTransportSE.requestDump;
      System.out.println(dump);
      SoapPrimitive soapPrimitive = (SoapPrimitive)envelope.getResponse();
      String result = soapPrimitive.toString();
      System.out.println(result);
    } catch (Exception e) {
      String dump = httpTransportSE.requestDump;
      System.out.println(dump);
      System.out.println(e);
    }
  }
}
