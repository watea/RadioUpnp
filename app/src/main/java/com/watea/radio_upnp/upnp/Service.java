package com.watea.radio_upnp.upnp;

import android.util.Log;

import androidx.annotation.NonNull;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class Service implements Asset {
  public static final String XML_TAG = "service";
  public static final String SERVICE_TYPE = "serviceType";
  public static final String SERVICE_ID = "serviceId";
  public static final String SCPDURL = "SCPDURL";
  public static final String CONTROL_URL = "controlURL";
  private static final String LOG_TAG = Service.class.getName();
  private final URL baseURL;
  private final String serviceType;
  private final String serviceId;
  private final URI controlURL;
  private final URI descriptionURL;
  private final Set<Action> actions = new HashSet<>();
  private boolean isComplete = false;
  private final URLService.Consumer xMLBuilder = new URLService.Consumer() {
    final AtomicReference<Action> currentAction = new AtomicReference<>();

    @Override
    public void startAccept(@NonNull URLService urlService, @NonNull String currentTag)
      throws XmlPullParserException, IOException {
      // Process Action, if any
      if (currentTag.equals(Action.XML_NAME)) {
        currentAction.set(new Action());
      }
      final Action action = currentAction.get();
      if (action != null) {
        action.getXMLBuilder().startAccept(urlService, currentTag);
      }
    }

    @Override
    public void endAccept(@NonNull URLService urlService, @NonNull String currentTag) {
      // Process Action, if any
      final Action action = currentAction.get();
      if (action != null) {
        // Process Action, if any
        action.getXMLBuilder().endAccept(urlService, currentTag);
        // Action complete?
        if (currentTag.equals(Action.XML_NAME)) {
          if (action.isComplete()) {
            actions.add(action);
          } else {
            Log.d(LOG_TAG, "hydrate: try to add an incomplete Action to: " + serviceType);
          }
          currentAction.set(null);
        }
      }
      // End of actions
      isComplete = currentTag.equalsIgnoreCase("actionlList");
    }
  };

  public Service(
    @NonNull URL baseURL,
    @NonNull String serviceType,
    @NonNull String serviceId,
    @NonNull String descriptionURL,
    @NonNull String controlURL) throws IOException, XmlPullParserException, URISyntaxException {
    this.baseURL = baseURL;
    this.serviceType = serviceType;
    this.serviceId = serviceId;
    this.controlURL = new URI(controlURL);
    this.descriptionURL = new URI(descriptionURL);
    hydrate();
  }

  public URL getBaseURL() {
    return baseURL;
  }

  public String getServiceType() {
    return serviceType;
  }

  public String getServiceId() {
    return serviceId;
  }

  public URI getControlURL() {
    return controlURL;
  }

  public URI getDescriptionURL() {
    return descriptionURL;
  }

  public Set<Action> getActions() {
    return actions;
  }

  @Override
  public boolean isComplete() {
    return isComplete;
  }

  @NonNull
  @Override
  public URLService.Consumer getXMLBuilder() {
    return xMLBuilder;
  }

  // Fetch service content
  private void hydrate() throws IOException, XmlPullParserException, URISyntaxException {
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
    new URLService(baseURL, descriptionURL).fetchContent().parseXml(xMLBuilder);
  }
}