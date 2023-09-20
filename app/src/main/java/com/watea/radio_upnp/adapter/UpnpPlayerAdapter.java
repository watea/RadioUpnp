/*
 * Copyright (c) 2018. Stephane Treuchot
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

package com.watea.radio_upnp.adapter;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.UpnpActionController;
import com.watea.radio_upnp.service.UpnpWatchdog;

import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.DeviceType;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDAServiceId;

import java.util.List;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpnpPlayerAdapter extends PlayerAdapter {
  public static final ServiceId AV_TRANSPORT_SERVICE_ID = new UDAServiceId("AVTransport");
  public static final DeviceType RENDERER_DEVICE_TYPE = new UDADeviceType("MediaRenderer");
  private static final String LOG_TAG = UpnpPlayerAdapter.class.getName();
  private static final ServiceId RENDERING_CONTROL_ID = new UDAServiceId("RenderingControl");
  private static final ServiceId CONNECTION_MANAGER_ID = new UDAServiceId("ConnectionManager");
  private static final String PROTOCOL_INFO_HEADER = "http-get:*:";
  private static final String PROTOCOL_INFO_ALL = ":*";
  private static final String DEFAULT_PROTOCOL_INFO =
    PROTOCOL_INFO_HEADER + "*" + PROTOCOL_INFO_ALL;
  private static final String ACTION_PREPARE_FOR_CONNECTION = "PrepareForConnection";
  private static final String ACTION_SET_AV_TRANSPORT_URI = "SetAVTransportURI";
  private static final String ACTION_SET_STATE_VARIABLES = "SetStateVariables";
  private static final String ACTION_GET_PROTOCOL_INFO = "GetProtocolInfo";
  private static final String ACTION_PLAY = "Play";
  private static final String ACTION_STOP = "Stop";
  private static final String ACTION_SET_VOLUME = "SetVolume";
  private static final String ACTION_GET_VOLUME = "GetVolume";
  private static final String INPUT_DESIRED_VOLUME = "DesiredVolume";
  private static final String INPUT_CHANNEL = "Channel";
  private static final String INPUT_MASTER = "Master";
  @NonNull
  private final Device<?, ?, ?> device;
  @NonNull
  private final UpnpActionController upnpActionController;
  @Nullable
  private final Uri logoUri;
  @Nullable
  private final UpnpActionController.UpnpAction actionPlay;
  @Nullable
  private final UpnpActionController.UpnpAction actionStop;
  @Nullable
  private final UpnpActionController.UpnpAction actionPrepareForConnection;
  @Nullable
  private final UpnpActionController.UpnpAction actionSetVolume;
  @Nullable
  private final UpnpActionController.UpnpAction actionGetVolume;
  @Nullable
  private final UpnpActionController.UpnpAction actionSetAvTransportUri;
  @Nullable
  private final UpnpActionController.UpnpAction actionGetProtocolInfo;
  @Nullable
  private final UpnpActionController.UpnpAction actionSetStateVariables = null;
  @NonNull
  private final UpnpWatchdog upnpWatchdog;
  private int currentVolume;
  private int volumeDirection = AudioManager.ADJUST_SAME;
  @NonNull
  private String instanceId = "0";
  @NonNull
  private String information;

  public UpnpPlayerAdapter(
    @NonNull Context context,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey,
    @NonNull Uri radioUri,
    @Nullable Uri logoUri,
    @NonNull Device<?, ?, ?> device,
    @NonNull UpnpActionController upnpActionController) {
    super(context, listener, radio, lockKey, radioUri);
    this.device = device;
    this.upnpActionController = upnpActionController;
    this.logoUri = logoUri;
    information = this.context.getString(R.string.app_name);
    final Service<?, ?> connectionManager = device.findService(CONNECTION_MANAGER_ID);
    final Service<?, ?> avTransportService = device.findService(AV_TRANSPORT_SERVICE_ID);
    final Service<?, ?> renderingControl = device.findService(RENDERING_CONTROL_ID);
    // Watchdog test if reader is actually playing
    upnpWatchdog = new UpnpWatchdog(
      this.upnpActionController,
      avTransportService,
      this::getInstanceId,
      readerState -> changeAndNotifyState(
        (readerState == UpnpWatchdog.ReaderState.PLAYING) ?
          PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_ERROR));
    Action<?> action = getAction(avTransportService, ACTION_PLAY, true);
    // Actual Playing state is tested by Watchdog, so nothing to do in case of success
    actionPlay = (action == null) ? null :
      new UpnpActionController.UpnpAction(this.upnpActionController, action) {
        @NonNull
        @Override
        public ActionInvocation<?> getActionInvocation() {
          final ActionInvocation<?> actionInvocation = getActionInvocation(instanceId);
          actionInvocation.setInput("Speed", "1");
          return actionInvocation;
        }

        @Override
        protected void failure() {
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
          super.failure();
        }
      };
    action = getAction(avTransportService, ACTION_STOP, true);
    actionStop = (action == null) ? null :
      new UpnpActionController.UpnpAction(this.upnpActionController, action) {
        @NonNull
        @Override
        public ActionInvocation<?> getActionInvocation() {
          return getActionInvocation(instanceId);
        }

        @Override
        protected void failure() {
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
          super.failure();
        }
      };
    action = getAction(connectionManager, ACTION_PREPARE_FOR_CONNECTION, false);
    actionPrepareForConnection = (action == null) ? null :
      new UpnpActionController.UpnpAction(this.upnpActionController, action) {
        @NonNull
        @Override
        public ActionInvocation<?> getActionInvocation() {
          ActionInvocation<?> actionInvocation = getActionInvocation(null);
          actionInvocation.setInput("RemoteProtocolInfo", DEFAULT_PROTOCOL_INFO);
          actionInvocation.setInput("PeerConnectionManager", "");
          actionInvocation.setInput("PeerConnectionID", "-1");
          actionInvocation.setInput("Direction", "Input");
          return actionInvocation;
        }

        @Override
        protected void success(@NonNull ActionInvocation<?> actionInvocation) {
          final ActionArgumentValue<?> instanceIdArgument =
            actionInvocation.getOutput("AVTransportID");
          if (instanceIdArgument != null) {
            instanceId = instanceIdArgument.getValue().toString();
          }
          super.success(actionInvocation);
        }

        @Override
        protected void failure() {
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
          super.failure();
        }
      };
    action = getAction(renderingControl, ACTION_SET_VOLUME, false);
    actionSetVolume = (action == null) ? null :
      new UpnpActionController.UpnpAction(this.upnpActionController, action) {
        @NonNull
        @Override
        public ActionInvocation<?> getActionInvocation() {
          final ActionInvocation<?> actionInvocation = getActionInvocation(instanceId);
          actionInvocation.setInput(INPUT_DESIRED_VOLUME, Integer.toString(currentVolume));
          actionInvocation.setInput(INPUT_CHANNEL, INPUT_MASTER);
          Log.d(LOG_TAG, "Volume required: " + currentVolume);
          return actionInvocation;
        }

        @Override
        protected void success(@NonNull ActionInvocation<?> actionInvocation) {
          volumeDirection = AudioManager.ADJUST_SAME;
          Log.d(LOG_TAG, "Volume set: " + actionInvocation.getInput(INPUT_DESIRED_VOLUME));
        }

        @Override
        protected void failure() {
          // No more action
          volumeDirection = AudioManager.ADJUST_SAME;
        }
      };
    action = getAction(renderingControl, ACTION_GET_VOLUME, false);
    actionGetVolume = (action == null) ? null :
      new UpnpActionController.UpnpAction(this.upnpActionController, action) {
        @NonNull
        @Override
        public ActionInvocation<?> getActionInvocation() {
          final ActionInvocation<?> actionInvocation = getActionInvocation(instanceId);
          // Should not, but may fail
          try {
            actionInvocation.setInput(INPUT_CHANNEL, INPUT_MASTER);
          } catch (Exception exception) {
            Log.d(LOG_TAG, ACTION_GET_VOLUME + ": fail", exception);
          }
          return actionInvocation;
        }

        @Override
        protected void success(@NonNull ActionInvocation<?> actionInvocation) {
          currentVolume =
            Integer.parseInt(actionInvocation.getOutput("CurrentVolume").getValue().toString());
          switch (volumeDirection) {
            case AudioManager.ADJUST_LOWER:
              currentVolume = Math.max(0, --currentVolume);
              if (actionSetVolume != null) {
                actionSetVolume.execute();
              }
              break;
            case AudioManager.ADJUST_RAISE:
              currentVolume++;
              if (actionSetVolume != null) {
                actionSetVolume.execute();
              }
              break;
            default:
              // Nothing to do
          }
        }

        @Override
        protected void failure() {
          // No more action
          volumeDirection = AudioManager.ADJUST_SAME;
        }
      };
    action = getAction(avTransportService, ACTION_SET_AV_TRANSPORT_URI, true);
    actionSetAvTransportUri = (action == null) ? null :
      new UpnpActionController.UpnpAction(this.upnpActionController, action) {
        @NonNull
        @Override
        public ActionInvocation<?> getActionInvocation() {
          final ActionInvocation<?> actionInvocation = getActionInvocation(instanceId);
          final String metadata = getMetaData();
          actionInvocation.setInput("CurrentURI", radioUri.toString());
          actionInvocation.setInput("CurrentURIMetaData", metadata);
          Log.d(LOG_TAG, "SetAVTransportURI=> InstanceID: " + instanceId);
          Log.d(LOG_TAG, "SetAVTransportURI=> CurrentURI: " + radioUri);
          Log.d(LOG_TAG, "SetAVTransportURI=> CurrentURIMetaData: " + metadata);
          return actionInvocation;
        }

        @Override
        protected void success(@NonNull ActionInvocation<?> actionInvocation) {
          changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
          super.success(actionInvocation);
        }

        @Override
        protected void failure() {
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
          super.failure();
        }
      };
    // TODO: to validate with AVTransport:3 Service Device
//    action = getAction(avTransportService, ACTION_SET_STATE_VARIABLES, false);
//    actionSetStateVariables = (action == null) ? null :
//      new UpnpActionController.UpnpAction(this.upnpActionController, action) {
//        @Override
//        public ActionInvocation<?> getActionInvocation() {
//          final ActionInvocation<?> actionInvocation = getActionInvocation(instanceId);
//          actionInvocation.setInput("AVTransportUDN", device.getIdentity().getUdn());
//          actionInvocation.setInput("ServiceType", avTransportService.getServiceType());
//          actionInvocation.setInput("ServiceId", avTransportService.getServiceId());
//          actionInvocation.setInput(
//            "StateVariableValuePairs",
//            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
//              "<stateVariableValuePairs " +
//              "xmlns=\"urn:schemas-upnp-org:av:avs\" " +
//              "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
//              "xsi:schemaLocation=\"urn:schemas-upnp-org:av:avs\nhttp://www.upnp.org/schemas/av/avs.xsd\">" +
//              "<stateVariable variableName=\"AVTransportURIMetaData\">" +
//              getMetaData() +
//              "</stateVariable>" +
//              "</stateVariableValuePairs>");
//          return actionInvocation;
//        }
//      };
    action = getAction(connectionManager, ACTION_GET_PROTOCOL_INFO, true);
    actionGetProtocolInfo = (action == null) ? null :
      new UpnpActionController.UpnpAction(this.upnpActionController, action) {
        @NonNull
        @Override
        public ActionInvocation<?> getActionInvocation() {
          return getActionInvocation(null);
        }

        @Override
        public void success(@NonNull ActionInvocation<?> actionInvocation) {
          final List<String> protocolInfos = new Vector<>();
          for (String protocolInfo : actionInvocation.getOutput("Sink").toString().split(",")) {
            if (UpnpPlayerAdapter.isHandling(protocolInfo)) {
              Log.d(LOG_TAG, "Audio ProtocolInfo: " + protocolInfo);
              protocolInfos.add(protocolInfo);
            }
          }
          if (!protocolInfos.isEmpty()) {
            putProtocolInfo(protocolInfos);
          }
          super.success(actionInvocation);
        }

        @Override
        protected void failure() {
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
          super.failure();
        }
      };
  }

  @NonNull
  public String getInstanceId() {
    return instanceId;
  }

  @Override
  public void adjustVolume(int direction) {
    // Do only if nothing done currently
    if ((actionGetVolume != null) && (volumeDirection == AudioManager.ADJUST_SAME)) {
      volumeDirection = direction;
      actionGetVolume.execute();
    }
  }

  @Override
  public long getAvailableActions() {
    long actions = super.getAvailableActions();
    switch (state) {
      case PlaybackStateCompat.STATE_NONE:
      case PlaybackStateCompat.STATE_BUFFERING:
      case PlaybackStateCompat.STATE_PAUSED:
        actions |= PlaybackStateCompat.ACTION_PLAY;
        break;
      default:
        // Nothing else
    }
    return actions;
  }

  @Override
  protected boolean isRemote() {
    return true;
  }

  @Override
  protected void onPrepareFromMediaId() {
    changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
    // Launch watchdog
    upnpWatchdog.start();
    // Fetch content in a new thread
    new Thread(() -> {
      if (upnpActionController.getContentType(radio) == null) {
        upnpActionController.fetchContentType(radio);
      }
      // We can now call prepare, only if we are still waiting
      if (state == PlaybackStateCompat.STATE_BUFFERING) {
        onPreparedPlay();
      }
    }).start();
  }

  @Override
  protected void onPlay() {
    if (actionPlay != null) {
      actionPlay.schedule();
    }
  }

  // Nota: as tested, not supported by UPnP device
  @Override
  protected void onPause() {
    Log.d(LOG_TAG, "onPause: not supported");
  }

  @Override
  protected void onStop() {
    if (actionStop != null) {
      actionStop.schedule();
    }
  }

  @Override
  protected void onRelease() {
    upnpWatchdog.kill();
  }

  // Special handling for MIME type
  @NonNull
  public String getContentType() {
    final String HEAD_EXP = "[a-z]*/";
    String contentType = upnpActionController.getContentType(radio);
    // Default value
    if (contentType == null) {
      contentType = DEFAULT_CONTENT_TYPE;
    }
    // First choice: contentType
    String result = searchContentType(contentType);
    if (result != null) {
      return result;
    }
    // Second choice: MIME subtype
    result = searchContentType(HEAD_EXP + contentType.replaceFirst(HEAD_EXP, ""));
    if (result != null) {
      return result;
    }
    // AAC special case
    if (contentType.contains("aac")) {
      result = searchContentType(AUDIO_CONTENT_TYPE + "mp4");
      if (result != null) {
        return result;
      }
    }
    // Default case
    return contentType;
  }

  public void onNewInformation(@NonNull String information) {
    if (actionSetStateVariables != null) {
      this.information =
        (information.length() == 0) ? context.getString(R.string.app_name) : information;
      actionSetStateVariables.schedule();
    }
  }

  private void onPreparedPlay() {
    // Do prepare if available
    if (actionPrepareForConnection != null) {
      actionPrepareForConnection.schedule();
    }
    if ((actionGetProtocolInfo != null) && (upnpActionController.getProtocolInfo(device) == null)) {
      actionGetProtocolInfo.schedule();
    }
    if (actionSetAvTransportUri != null) {
      actionSetAvTransportUri.schedule();
    }
    if (actionPlay != null) {
      actionPlay.schedule();
    }
  }

  // Create DIDL-Lite metadata
  @NonNull
  private String getMetaData() {
    return "<DIDL-Lite " +
      "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
      "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
      "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
      "<item id=\"" + radio.hashCode() + "\" parentID=\"0\" restricted=\"1\">" +
      "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
      "<dc:title>" + radio.getName() + "</dc:title>" +
      "<upnp:artist>" + information + "</upnp:artist>" +
      "<upnp:album>" + context.getString(R.string.live_streaming) + "</upnp:album>" +
      "<res duration=\"0:00:00\" protocolInfo=\"" +
      PROTOCOL_INFO_HEADER + getContentType() + PROTOCOL_INFO_ALL + "\">" + radioUri + "</res>" +
      "<upnp:albumArtURI>" + logoUri + "</upnp:albumArtURI>" +
      "</item>" +
      "</DIDL-Lite>";
  }

  @Nullable
  private String searchContentType(@NonNull String contentType) {
    final List<String> protocolInfos = upnpActionController.getProtocolInfo(device);
    if (protocolInfos != null) {
      Pattern mimePattern = Pattern.compile("http-get:\\*:(" + contentType + "):.*");
      for (String protocolInfo : protocolInfos) {
        Matcher matcher = mimePattern.matcher(protocolInfo);
        if (matcher.find()) {
          return matcher.group(1);
        }
      }
    }
    return null;
  }

  @Nullable
  private Action<?> getAction(
    @Nullable Service<?, ?> service, @NonNull String actionName, boolean shallExist) {
    Action<?> action = null;
    if (service == null) {
      Log.i(LOG_TAG, "Service not available for: " + actionName);
    } else {
      action = service.getAction(actionName);
    }
    if (action == null) {
      Log.i(LOG_TAG, "Action not available: " + actionName);
      if (shallExist) {
        changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
      }
    }
    return action;
  }
}