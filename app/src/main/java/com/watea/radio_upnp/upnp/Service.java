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
import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings("unused")
// <actionList>
//  <action>
//    <name>actionName</name>
//    <argumentList>
//      <argument>
//        <name>formalParameterName</name>
//        <direction>in xor out</direction>
//        <relatedStateVariable>stateVariableName</relatedStateVariable>
//      </argument>
//      Declarations for other arguments (if any) go here
//    </argumentList>
//  </action>
//  Declarations for other actions (if any) go here
// </actionList>
public class Service extends Asset {
  public static final String XML_TAG = "service";
  public static final String SERVICE_TYPE = "serviceType";
  public static final String SERVICE_ID = "serviceId";
  public static final String SCPDURL = "SCPDURL";
  public static final String CONTROL_URL = "controlURL";
  private static final String LOG_TAG = Service.class.getSimpleName();
  private final AtomicReference<Action> currentAction = new AtomicReference<>();
  private final Device device;
  private final URL baseURL;
  private final String serviceType;
  private final String serviceId;
  private final URI controlURL;
  private final URI descriptionURL;
  private final Set<Action> actions = new HashSet<>();

  // Service does not call setOnError(); isOnError() is always false
  public Service(
    @NonNull Device device,
    @NonNull URL baseURL,
    @NonNull String serviceType,
    @NonNull String serviceId,
    @NonNull URI descriptionURL,
    @NonNull URI controlURL)
    throws IOException, XmlPullParserException, URISyntaxException {
    this.device = device;
    this.baseURL = baseURL;
    this.serviceType = serviceType;
    this.serviceId = serviceId;
    this.controlURL = controlURL;
    this.descriptionURL = descriptionURL;
    // Fetch content
    hydrate(new URLService(baseURL, this.descriptionURL));
  }

  @Override
  public void startAccept(@NonNull URLService urlService, @NonNull String currentTag) {
    // Process Action, if any
    if (currentTag.equals(Action.XML_NAME)) {
      currentAction.set(new Action(this));
    }
    final Action action = currentAction.get();
    if (action != null) {
      action.startAccept(urlService, currentTag);
    }
  }

  @Override
  public void endAccept(@NonNull URLService urlService, @NonNull String currentTag) {
    // Process Action, if any
    final Action action = currentAction.get();
    if (action != null) {
      // Process Action, if any
      action.endAccept(urlService, currentTag);
      // Action complete?
      if (currentTag.equals(Action.XML_NAME)) {
        if (action.isOnError()) {
          // No setOnError() here as we want to tolerate incomplete service
          Log.e(LOG_TAG, "enAccept: try to add an incomplete Action to: " + serviceType);
        } else {
          actions.add(action);
        }
        currentAction.set(null);
      }
    }
  }

  @NonNull
  public Device getDevice() {
    return device;
  }

  @NonNull
  public URL getBaseURL() {
    return baseURL;
  }

  @NonNull
  public String getServiceType() {
    return serviceType;
  }

  @NonNull
  public String getServiceId() {
    return serviceId;
  }

  @NonNull
  public URI getControlURL() {
    return controlURL;
  }

  @NonNull
  public URI getDescriptionURL() {
    return descriptionURL;
  }

  @NonNull
  public URI getActualControlURI() throws URISyntaxException {
    return baseURL.toURI().resolve(controlURL);
  }

  @NonNull
  public Set<Action> getActions() {
    return actions;
  }

  @Nullable
  public Action getAction(@NonNull String actionName) {
    return actions.stream().filter(action -> action.hasName(actionName)).findFirst().orElse(null);
  }
}