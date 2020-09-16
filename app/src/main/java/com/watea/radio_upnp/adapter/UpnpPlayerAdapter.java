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
import com.watea.radio_upnp.service.HttpServer;
import com.watea.radio_upnp.service.RadioHandler;
import com.watea.radio_upnp.service.RadioService;

import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Action;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.UDAServiceId;

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
  private static final String FIELD_CURRENT_URI_METADATA = "CurrentURIMetaData";
  private static final String ACTION_PLAY = "Play";
  private static final String ACTION_PAUSE = "Pause";
  private static final String ACTION_STOP = "Stop";
  private static final String ACTION_SET_VOLUME = "SetVolume";
  private static final String ACTION_GET_VOLUME = "GetVolume";
  private static final String INPUT_DESIRED_VOLUME = "DesiredVolume";
  private static final Pattern PATTERN_DLNA = Pattern.compile(".*DLNA\\.ORG_PN=([^;]*).*");
  private static final int REMOTE_LOGO_SIZE = 300;
  @NonNull
  private final Device<?, ?, ?> device;
  @NonNull
  private final RadioService.UpnpActionControler upnpActionControler;
  @Nullable
  private final Uri logo;
  @Nullable
  private final Service<?, ?> connectionManager;
  @Nullable
  private final Service<?, ?> avTransportService;
  @Nullable
  private final Service<?, ?> renderingControl;
  private int volumeDirection = AudioManager.ADJUST_SAME;
  @NonNull
  private String instanceId = "0";
  @NonNull
  private String radioUri = "";

  public UpnpPlayerAdapter(
    @NonNull Context context,
    @NonNull HttpServer httpServer,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey,
    @NonNull Device<?, ?, ?> device,
    @NonNull RadioService.UpnpActionControler upnpActionControler) {
    super(context, httpServer, listener, radio, lockKey);
    this.device = device;
    this.upnpActionControler = upnpActionControler;
    // Shall not be null
    Uri uri = this.httpServer.getUri();
    if (uri == null) {
      Log.e(LOG_TAG, "UpnpPlayerAdapter: service not available");
    } else {
      radioUri = RadioHandler.getHandledUri(uri, this.radio, this.lockKey).toString();
    }
    logo = this.httpServer.createLogoFile(radio, REMOTE_LOGO_SIZE);
    connectionManager = device.findService(CONNECTION_MANAGER_ID);
    avTransportService = device.findService(AV_TRANSPORT_SERVICE_ID);
    renderingControl = device.findService(RENDERING_CONTROL_ID);
  }

  @NonNull
  @Override
  public String getProtocolInfo() {
    String protocolInfo = upnpActionControler.getProtocolInfo(device);
    return (protocolInfo == null) ? super.getProtocolInfo() : protocolInfo;
  }

  @Override
  public void adjustVolume(int direction) {
    // Do nothing if already running
    if (volumeDirection != AudioManager.ADJUST_SAME) {
      return;
    }
    ActionInvocation<?> actionInvocation = getRenderingControlActionInvocation(ACTION_GET_VOLUME);
    // Do nothing if volume not controlled
    if (actionInvocation == null) {
      Log.d(LOG_TAG, "adjustVolume: service not available");
      return;
    }
    volumeDirection = direction;
    executeAction(actionInvocation);
  }

  @Override
  protected boolean isLocal() {
    return false;
  }

  @Override
  protected void onPrepareFromMediaId() {
    if ((connectionManager == null) || (avTransportService == null)) {
      Log.e(LOG_TAG, "onPrepareFromMediaId: services not available");
      changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
      return;
    }
    changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
    if (upnpActionControler.getContentType(radio) == null) {
      // Fetch ContentType before launching
      new Thread() {
        @Override
        public void run() {
          String contentType = getStreamContentType(radio.getURL());
          // Now we can call GetProtocolInfo, only if current action not cancelled
          if (contentType != null) {
            upnpActionControler.putContentType(radio, contentType);
          }
          onPreparedPlay();
        }
      }.start();
    } else {
      onPreparedPlay();
    }
    // Watchdog
    new Thread() {
      @Override
      public void run() {
        try {
          Thread.sleep(10000);
        } catch (InterruptedException interruptedException) {
          Log.e(LOG_TAG, "onPrepareFromMediaId: watchdog error");
        }
        if (state == PlaybackStateCompat.STATE_BUFFERING) {
          Log.i(LOG_TAG, "onPrepareFromMediaId: watchdog fired");
          abort();
        }
      }
    }.start();
  }

  public void onPlay() {
    ActionInvocation<?> actionInvocation = getAVTransportActionInvocation(ACTION_PLAY);
    if (actionInvocation == null) {
      Log.e(LOG_TAG, "onPlay: service not available");
      return;
    }
    actionInvocation.setInput("Speed", "1");
    scheduleAction(actionInvocation);
  }

  // Nota: as tested, not supported by DLNA device
  @Override
  public void onPause() {
    ActionInvocation<?> actionInvocation = getAVTransportActionInvocation(ACTION_PAUSE);
    if (actionInvocation == null) {
      Log.e(LOG_TAG, "onPause: service not available");
      return;
    }
    scheduleAction(actionInvocation);
  }

  @Override
  public void onStop() {
    ActionInvocation<?> actionInvocation = getAVTransportActionInvocation(ACTION_STOP);
    if (actionInvocation == null) {
      Log.e(LOG_TAG, "onStop: service not available");
      return;
    }
    scheduleAction(actionInvocation);
  }

  @Override
  public void onRelease() {
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

  private void onPreparedPlay() {
    ActionInvocation<?> actionInvocation;
    if (upnpActionControler.getProtocolInfo(device) == null) {
      // Search ProtocolInfo if possible
      actionInvocation = getConnectionActionInvocation(ACTION_GET_PROTOCOL_INFO);
      if (actionInvocation == null) {
        Log.d(LOG_TAG, "onPreparedPlay: ProtocolInfo not available");
      } else {
        scheduleAction(actionInvocation);
      }
    }
    // Do prepare if available
    actionInvocation = getConnectionActionInvocation(ACTION_PREPARE_FOR_CONNECTION);
    if (actionInvocation != null) {
      actionInvocation.setInput("RemoteProtocolInfo", getProtocolInfo());
      actionInvocation.setInput("PeerConnectionManager", "");
      actionInvocation.setInput("PeerConnectionID", "-1");
      actionInvocation.setInput("Direction", "Input");
      scheduleAction(actionInvocation);
    }
    actionInvocation = getAVTransportActionInvocation(ACTION_SET_AV_TRANSPORT_URI);
    if (actionInvocation == null) {
      Log.e(LOG_TAG, "onPreparedPlay: internal error for set");
      return;
    }
    String metadata = getMetaData();
    actionInvocation.setInput("CurrentURI", radioUri);
    actionInvocation.setInput(FIELD_CURRENT_URI_METADATA, metadata);
    Log.d(LOG_TAG, "SetAVTransportURI=> InstanceID: " + instanceId);
    Log.d(LOG_TAG, "SetAVTransportURI=> CurrentURI: " + radioUri);
    Log.d(LOG_TAG, "SetAVTransportURI=> CurrentURIMetaData: " + metadata);
    scheduleAction(actionInvocation);
    onPlay();
  }

  private void scheduleAction(@NonNull ActionInvocation<?> actionInvocation) {
    upnpActionControler.scheduleAction(getActionCallback(actionInvocation));
  }

  private void executeAction(@NonNull ActionInvocation<?> actionInvocation) {
    upnpActionControler.executeAction(getActionCallback(actionInvocation));
  }

  @NonNull
  private ActionCallback getActionCallback(@NonNull ActionInvocation<?> actionInvocation) {
    return new ActionCallback(actionInvocation) {
      @Override
      public void success(ActionInvocation actionInvocation) {
        String action = actionInvocation.getAction().getName();
        Log.d(LOG_TAG, "Successfully called UPnP action: " + action + "/" + lockKey);
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
            if (actionInvocation == null) {
              Log.e(LOG_TAG, "success: internal error for ACTION_SET_VOLUME");
            } else {
              actionInvocation.setInput(INPUT_DESIRED_VOLUME, Integer.toString(currentVolume));
              executeAction(actionInvocation);
              Log.d(LOG_TAG, "Volume required: " + currentVolume);
            }
            break;
          case ACTION_SET_VOLUME:
            volumeDirection = AudioManager.ADJUST_SAME;
            Log.d(LOG_TAG, "Volume set: " + actionInvocation.getInput(INPUT_DESIRED_VOLUME));
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
            String contentType = upnpActionControler.getContentType(radio);
            String pattern = CONTENT_FEATURES_HTTP +
              ((contentType == null) ? CONTENT_FEATURES_AUDIO_MPEG : contentType);
            String info = CONTENT_FEATURES_MP3;
            // DLNA player data for MIME type
            for (String string : actionInvocation.getOutput("Sink").toString().split(",")) {
              if (string.startsWith(pattern)) {
                Matcher matcher = PATTERN_DLNA.matcher(string);
                if (matcher.find()) {
                  String newInfo = matcher.group(1);
                  if ((newInfo != null) && (newInfo.length() <= info.length())) {
                    info = newInfo;
                  }
                }
              }
            }
            upnpActionControler.putProtocolInfo(
              device, pattern + CONTENT_FEATURES_BASE + info + CONTENT_FEATURES_EXTENDED);
            break;
          case ACTION_PREPARE_FOR_CONNECTION:
            ActionArgumentValue<?> instanceIdArgument = actionInvocation.getOutput("AVTransportID");
            if (instanceIdArgument != null) {
              instanceId = instanceIdArgument.getValue().toString();
            }
            break;
          case ACTION_SET_AV_TRANSPORT_URI:
            // Nothing to do
            break;
          // Should not happen
          default:
            Log.e(LOG_TAG, "=> State not managed: " + action);
        }
        upnpActionControler.scheduleNextAction();
      }

      @Override
      public void failure(
        ActionInvocation actionInvocation, UpnpResponse operation, String defaultMsg) {
        String action = actionInvocation.getAction().getName();
        Log.d(LOG_TAG, "UPnP error: " + action + "/" + lockKey + "=> " + defaultMsg);
        // Don't handle error on volume
        // Lock key should be tested here ot be rigorous
        switch (action) {
          case ACTION_GET_VOLUME:
          case ACTION_SET_VOLUME:
            // No more action
            volumeDirection = AudioManager.ADJUST_SAME;
            return;
          case ACTION_PREPARE_FOR_CONNECTION:
            // Nothing to do
            return;
          case ACTION_SET_AV_TRANSPORT_URI:
            // For unknown reason, some devices (seen on SONY) don't decode metadata correctly.
            // So we try without instead...
            ActionArgumentValue<?> metaData = actionInvocation.getInput(FIELD_CURRENT_URI_METADATA);
            // Should not be null, but null pointer exception occurs
            if (metaData != null) {
              String metaDataValue = (String) metaData.getValue();
              if ((metaDataValue != null) && (metaDataValue.length() > 0)) {
                Log.d(LOG_TAG, "SetAVTransportURI=> CurrentURIMetaData forced to void");
                actionInvocation.setInput(FIELD_CURRENT_URI_METADATA, "");
                upnpActionControler.pushAction(getActionCallback(actionInvocation));
                break;
              }
            }
          default:
            abort();
        }
        upnpActionControler.scheduleNextAction();
      }
    };
  }

  // Create DIDL-Lite metadata
  @NonNull
  private String getMetaData() {
    return "<DIDL-Lite " +
      "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"" +
      "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
      "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
      "<item id=\"" + radio.getId() + "\" parentID=\"0\" restricted=\"1\">" +
      // object.item.audioItem.audioBroadcast not valid
      "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
      "<dc:title>" + radio.getName() + "</dc:title>" +
      "<upnp:artist>" + context.getString(R.string.app_name) + "</upnp:artist>" +
      "<upnp:album>" + context.getString(R.string.live_streaming) + "</upnp:album>" +
      "<res duration=\"0:00:00\" protocolInfo=\"" + getProtocolInfo() + "\">" + radioUri +
      "</res>" +
      "<upnp:albumArtURI>" + logo + "</upnp:albumArtURI>" +
      "</item>" +
      "</DIDL-Lite>";
  }

  @Nullable
  private ActionInvocation<?> getRenderingControlActionInvocation(@NonNull String action) {
    if (renderingControl == null) {
      Log.d(LOG_TAG, "getRenderingControlActionInvocation: no service");
      return null;
    }
    ActionInvocation<?> actionInvocation = getInstanceActionInvocation(renderingControl, action);
    if (actionInvocation == null) {
      return null;
    }
    actionInvocation.setInput("Channel", "Master");
    return actionInvocation;
  }

  @Nullable
  private ActionInvocation<?> getAVTransportActionInvocation(@NonNull String action) {
    if (avTransportService == null) {
      Log.e(LOG_TAG, "getAVTransportActionInvocation: internal error");
      return null;
    }
    return getInstanceActionInvocation(avTransportService, action);
  }

  @Nullable
  private ActionInvocation<?> getConnectionActionInvocation(@NonNull String action) {
    if (connectionManager == null) {
      Log.e(LOG_TAG, "getConnectionActionInvocation: internal error");
      return null;
    }
    return getActionInvocation(connectionManager, action);
  }

  @Nullable
  private ActionInvocation<?> getInstanceActionInvocation(
    @NonNull Service<?, ?> service, @NonNull String action) {
    ActionInvocation<?> actionInvocation = getActionInvocation(service, action);
    if (actionInvocation == null) {
      return null;
    }
    actionInvocation.setInput("InstanceID", instanceId);
    return actionInvocation;
  }

  @Nullable
  private ActionInvocation<?> getActionInvocation(
    @NonNull Service<?, ?> service, @NonNull String actionId) {
    Action<?> action = service.getAction(actionId);
    return (action == null) ? null : new ActionInvocation<>(action);
  }

  private void abort() {
    // Remove remaining actions on device
    upnpActionControler.releaseActions(device);
    changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
  }
}