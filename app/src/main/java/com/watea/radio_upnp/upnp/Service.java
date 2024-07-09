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
  private static final String LOG_TAG = Service.class.getName();
  private final AtomicReference<Action> currentAction = new AtomicReference<>();
  private final Device device;
  private final URL baseURL;
  private final String serviceType;
  private final String serviceId;
  private final URI controlURL;
  private final URI descriptionURL;
  private final Set<Action> actions = new HashSet<>();

  public Service(
    @NonNull Device device,
    @NonNull URL baseURL,
    @NonNull String serviceType,
    @NonNull String serviceId,
    @NonNull String descriptionURL,
    @NonNull String controlURL,
    @NonNull Callback callback)
    throws IOException, XmlPullParserException, URISyntaxException {
    super(callback);
    this.device = device;
    this.baseURL = baseURL;
    this.serviceType = serviceType;
    this.serviceId = serviceId;
    this.controlURL = new URI(controlURL);
    this.descriptionURL = new URI(descriptionURL);
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
        if (action.isComplete()) {
          actions.add(action);
        } else {
          Log.e(LOG_TAG, "enAccept: try to add an incomplete Action to: " + serviceType);
        }
        currentAction.set(null);
      }
    }
  }

  // We call call back when parsing is over
  @Override
  public void endParseAccept(@NonNull URLService uRLService) {
    if (isComplete()) {
      callback.onComplete(this);
    }
  }

  @Override
  protected boolean isComplete() {
    return !actions.isEmpty() && actions.stream().allMatch(Action::isComplete);
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