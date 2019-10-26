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
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.DlnaDevice;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.HttpServer;
import com.watea.radio_upnp.service.RadioHandler;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.UDAServiceId;

import java.util.Hashtable;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.watea.radio_upnp.service.NetworkTester.getStreamContentType;

public class UpnpPlayerAdapter extends PlayerAdapter {
  public static final ServiceId AV_TRANSPORT_SERVICE_ID = new UDAServiceId("AVTransport");
  private static final String LOG_TAG = UpnpPlayerAdapter.class.getName();
  private static final ServiceId RENDERING_CONTROL_ID = new UDAServiceId("RenderingControl");
  private static final ServiceId CONNECTION_MANAGER_ID = new UDAServiceId("ConnectionManager");
  private static final String ACTION_PREPARE_FOR_CONNECTION = "PrepareForConnection";
  private static final String ACTION_SET_AV_TRANSPORT_URI = "SetAVTransportURI";
  private static final String ACTION_GET_PROTOCOL_INFO = "GetProtocolInfo";
  private static final String ACTION_PLAY = "Play";
  private static final String ACTION_PAUSE = "Pause";
  private static final String ACTION_STOP = "Stop";
  private static final String ACTION_SET_VOLUME = "SetVolume";
  private static final String ACTION_GET_VOLUME = "GetVolume";
  private static final String INPUT_DESIRED_VOLUME = "DesiredVolume";
  private static final Pattern PATTERN_DLNA = Pattern.compile(".*DLNA\\.ORG_PN=([^;]*).*");
  private static final int REMOTE_LOGO_SIZE = 140;
  private static final Map<Long, String> contentTypes = new Hashtable<>();
  private static final Map<String, String> protocolInfos = new Hashtable<>();
  @NonNull
  private final Device dlnaDevice;
  @NonNull
  private final AndroidUpnpService androidUpnpService;
  private final Handler handler = new Handler();
  private final boolean hasPrepareForConnection;
  private int volumeDirection = AudioManager.ADJUST_SAME;
  @NonNull
  private String instanceId = "0";
  @NonNull
  private String radioUri = "";
  private final Runnable SETAVTRANSPORTURI_RUNNABLE = new Runnable() {
    @Override
    public void run() {
      onSetAVTransportURI();
    }
  };

  public UpnpPlayerAdapter(
    @NonNull Context context,
    @NonNull HttpServer httpServer,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey,
    @NonNull Device dlnaDevice,
    @NonNull AndroidUpnpService androidUpnpService) {
    super(context, httpServer, listener, radio, lockKey);
    this.dlnaDevice = dlnaDevice;
    this.androidUpnpService = androidUpnpService;
    // Shall not be null
    Uri uri = httpServer.getUri();
    if (uri == null) {
      Log.e(LOG_TAG, "UpnpPlayerAdapter: service not available");
    } else {
      radioUri = RadioHandler.getHandledUri(uri, this.radio, this.lockKey).toString();
    }
    Service connectionManager = dlnaDevice.findService(CONNECTION_MANAGER_ID);
    hasPrepareForConnection = (connectionManager != null) &&
      (connectionManager.getAction(ACTION_PREPARE_FOR_CONNECTION) != null);
  }

  @NonNull
  @Override
  public String getProtocolInfo() {
    String protocolInfo = protocolInfos.get(getDlnaIdentity());
    return (protocolInfo == null) ? super.getProtocolInfo() : protocolInfo;
  }

  @Override
  public void adjustVolume(int direction) {
    // Do nothing if volume not controlled or already running
    if ((dlnaDevice.findService(RENDERING_CONTROL_ID) == null) ||
      (volumeDirection != AudioManager.ADJUST_SAME)) {
      return;
    }
    volumeDirection = direction;
    upnpExecuteAction(getRenderingControlActionInvocation(ACTION_GET_VOLUME));
  }

  @Override
  protected boolean isLocal() {
    return false;
  }

  @Override
  protected void onPrepareFromMediaId() {
    if ((dlnaDevice.findService(CONNECTION_MANAGER_ID) == null) ||
      (dlnaDevice.findService(AV_TRANSPORT_SERVICE_ID) == null)) {
      Log.e(LOG_TAG, "onPrepareFromMediaId: services not available");
      changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
      return;
    }
    changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
    if (contentTypes.get(radio.getId()) == null) {
      // Fetch ContentType before launching
      new Thread() {
        @Override
        public void run() {
          String contentType = getStreamContentType(radio.getURL());
          // Now we can call GetProtocolInfo, only if current action not cancelled
          if (contentType != null) {
            contentTypes.put(radio.getId(), contentType);
          }
          launchDlnaReading();
        }
      }.start();
    } else {
      launchDlnaReading();
    }
  }

  public void onPlay() {
    ActionInvocation actionInvocation = getAVTransportActionInvocation(ACTION_PLAY);
    actionInvocation.setInput("Speed", "1");
    upnpExecuteAction(actionInvocation);
  }

  // Nota: as tested, not supported by DLNA device
  @Override
  public void onPause() {
    upnpExecuteAction(getAVTransportActionInvocation(ACTION_PAUSE));
  }

  @Override
  public void onStop() {
    upnpExecuteAction(getAVTransportActionInvocation(ACTION_STOP));
  }

  @Override
  public void onRelease() {
    // Force STOP state
    state = PlaybackStateCompat.STATE_STOPPED;
  }

  @Override
  protected long getAvailableActions() {
    long actions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID | PlaybackStateCompat.ACTION_STOP;
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

  private void launchDlnaReading() {
    if (protocolInfos.containsKey(getDlnaIdentity())) {
      launchConnection();
    } else {
      onGetProtocolInfo();
    }
  }

  private void launchConnection() {
    if (hasPrepareForConnection) {
      onPrepareForConnection();
    } else {
      onSetAVTransportURI();
    }
  }

  @NonNull
  private String getDlnaIdentity() {
    return DlnaDevice.getIdentity(dlnaDevice);
  }

  private void onGetProtocolInfo() {
    upnpExecuteAction(getActionInvocation(CONNECTION_MANAGER_ID, ACTION_GET_PROTOCOL_INFO));
  }

  private void onSetAVTransportURI() {
    ActionInvocation actionInvocation = getAVTransportActionInvocation(ACTION_SET_AV_TRANSPORT_URI);
    actionInvocation.setInput("CurrentURI", radioUri);
    actionInvocation.setInput("CurrentURIMetaData", getMetaData());
    upnpExecuteAction(actionInvocation);
  }

  private void onPrepareForConnection() {
    ActionInvocation actionInvocation =
      getActionInvocation(CONNECTION_MANAGER_ID, ACTION_PREPARE_FOR_CONNECTION);
    actionInvocation.setInput("RemoteProtocolInfo", getProtocolInfo());
    actionInvocation.setInput("PeerConnectionManager", "");
    actionInvocation.setInput("PeerConnectionID", "-1");
    actionInvocation.setInput("Direction", "Input");
    upnpExecuteAction(actionInvocation);
  }

  // Execute asynchronous in the background
  private void upnpExecuteAction(@NonNull ActionInvocation actionInvocation) {
    androidUpnpService.getControlPoint().execute(new ActionCallback(actionInvocation) {
      @Override
      public void success(ActionInvocation actionInvocation) {
        String action = actionInvocation.getAction().getName();
        Log.d(LOG_TAG, "Successfully called UPnP action: " + action);
        switch (action) {
          case ACTION_GET_VOLUME:
            int currentVolume;
            currentVolume =
              Integer.parseInt(actionInvocation.getOutput("CurrentVolume").getValue().toString());
            switch (volumeDirection) {
              case AudioManager.ADJUST_LOWER:
                currentVolume = Math.max(0, --currentVolume);
                break;
              case AudioManager.ADJUST_RAISE:
                currentVolume++;
                break;
              default:
                // Nothing to do
                return;
            }
            actionInvocation = getRenderingControlActionInvocation(ACTION_SET_VOLUME);
            actionInvocation.setInput(INPUT_DESIRED_VOLUME, Integer.toString(currentVolume));
            upnpExecuteAction(actionInvocation);
            Log.d(LOG_TAG, "Volume required: " + currentVolume);
            break;
          case ACTION_SET_VOLUME:
            volumeDirection = AudioManager.ADJUST_SAME;
            Log.d(LOG_TAG, "Volume set: " + actionInvocation.getInput(INPUT_DESIRED_VOLUME));
            break;
          case ACTION_SET_AV_TRANSPORT_URI:
            // Now we can call Play, only if current action not cancelled
            launch(new Runnable() {
              @Override
              public void run() {
                onPlay();
              }
            });
            break;
          case ACTION_STOP:
            changeAndNotifyState(PlaybackStateCompat.STATE_NONE);
            break;
          case ACTION_PLAY:
            changeAndNotifyState(PlaybackStateCompat.STATE_PLAYING);
            break;
          // Should not happen as PAUSE not allowed
          case ACTION_PAUSE:
            changeAndNotifyState(PlaybackStateCompat.STATE_PAUSED);
            break;
          case ACTION_GET_PROTOCOL_INFO:
            String contentType = contentTypes.get(radio.getId());
            String pattern = CONTENT_FEATURES_HTTP +
              ((contentType == null) ? CONTENT_FEATURES_AUDIO_MPEG : contentType);
            String info = CONTENT_FEATURES_MP3;
            // DLNA player data for MIME type
            for (String string : actionInvocation.getOutput("Sink").toString().split(",")) {
              if (string.startsWith(pattern)) {
                Matcher matcher = PATTERN_DLNA.matcher(string);
                if (matcher.find()) {
                  String newInfo = matcher.group(1);
                  if (newInfo.length() <= info.length()) {
                    info = newInfo;
                  }
                }
              }
            }
            protocolInfos.put(
              getDlnaIdentity(),
              pattern + CONTENT_FEATURES_BASE + info + CONTENT_FEATURES_EXTENDED);
            // Now we can launch connection, only if current action not cancelled
            launch(new Runnable() {
              @Override
              public void run() {
                launchConnection();
              }
            });
            break;
          case ACTION_PREPARE_FOR_CONNECTION:
            ActionArgumentValue instanceIdArgument = actionInvocation.getOutput("AVTransportID");
            if (instanceIdArgument != null) {
              instanceId = instanceIdArgument.getValue().toString();
            }
            // Now we can call SetAVTransportURI
            launch(SETAVTRANSPORTURI_RUNNABLE);
            break;
          // Should not happen
          default:
            Log.e(LOG_TAG, "=> State not managed: " + action);
        }
      }

      @Override
      public void failure(
        ActionInvocation actionInvocation, UpnpResponse operation, String defaultMsg) {
        String action = actionInvocation.getAction().getName();
        Log.d(LOG_TAG, action + ": " + defaultMsg);
        // Don't handle error on volume
        // Lock key should be tested here ot be rigorous
        switch (action) {
          case ACTION_GET_VOLUME:
          case ACTION_SET_VOLUME:
            // No more action
            volumeDirection = AudioManager.ADJUST_SAME;
            return;
          case ACTION_PREPARE_FOR_CONNECTION:
            // Now we can call SetAVTransportURI
            launch(SETAVTRANSPORTURI_RUNNABLE);
            return;
          default:
            changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
        }
      }
    });
  }

  // Launch if not cancelled
  private void launch(@NonNull final Runnable runnable) {
    handler.post(new Runnable() {
      @Override
      public void run() {
        if ((state == PlaybackStateCompat.STATE_NONE) ||
          (state == PlaybackStateCompat.STATE_BUFFERING)) {
          runnable.run();
        }
      }
    });
  }

  // Create DIDL-Lite metadata
  @NonNull
  private String getMetaData() {
    return "<DIDL-Lite " +
      "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"" +
      "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
      "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
      "<item id=\"" + radio.getName() + "\" parentID=\"0\" restricted=\"1\">" +
      // object.item.audioItem.audioBroadcast not valid
      "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
      "<dc:title>" + radio.getName() + "</dc:title>" +
      "<upnp:artist>" + context.getString(R.string.app_name) + "</upnp:artist>" +
      "<upnp:album>" + context.getString(R.string.live_streaming) + "</upnp:album>" +
      "<res duration=\"0:00:00\" protocolInfo=\"" + getProtocolInfo() + "\">" +
      radioUri + "</res>" +
      "<upnp:albumArtURI>" + httpServer.createLogoFile(radio, REMOTE_LOGO_SIZE) +
      "</upnp:albumArtURI>" +
      "</item>" +
      "</DIDL-Lite>";
  }

  @NonNull
  private ActionInvocation getRenderingControlActionInvocation(@NonNull String action) {
    ActionInvocation actionInvocation = getInstanceActionInvocation(RENDERING_CONTROL_ID, action);
    actionInvocation.setInput("Channel", "Master");
    return actionInvocation;
  }

  @NonNull
  private ActionInvocation getAVTransportActionInvocation(@NonNull String action) {
    return getInstanceActionInvocation(AV_TRANSPORT_SERVICE_ID, action);
  }

  @NonNull
  private ActionInvocation getInstanceActionInvocation(
    @NonNull ServiceId serviceId, @NonNull String action) {
    ActionInvocation actionInvocation = getActionInvocation(serviceId, action);
    actionInvocation.setInput("InstanceID", instanceId);
    return actionInvocation;
  }

  @NonNull
  private ActionInvocation getActionInvocation(
    @NonNull ServiceId serviceId, @NonNull String action) {
    //noinspection unchecked
    return new ActionInvocation(dlnaDevice.findService(serviceId).getAction(action));
  }
}