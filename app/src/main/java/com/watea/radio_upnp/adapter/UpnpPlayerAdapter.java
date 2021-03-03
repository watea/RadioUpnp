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
import com.watea.radio_upnp.service.NetworkTester;
import com.watea.radio_upnp.service.RadioHandler;
import com.watea.radio_upnp.service.RadioService;

import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.UDAServiceId;

import java.util.List;
import java.util.Vector;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpnpPlayerAdapter extends PlayerAdapter {
  public static final ServiceId AV_TRANSPORT_SERVICE_ID = new UDAServiceId("AVTransport");
  private static final String LOG_TAG = UpnpPlayerAdapter.class.getName();
  private static final ServiceId RENDERING_CONTROL_ID = new UDAServiceId("RenderingControl");
  private static final ServiceId CONNECTION_MANAGER_ID = new UDAServiceId("ConnectionManager");
  private static final String PROTOCOL_INFO_HEADER = "http-get:*:";
  private static final String PROTOCOL_INFO_ALL = ":*";
  private static final String DEFAULT_PROTOCOL_INFO = PROTOCOL_INFO_HEADER + "*" + PROTOCOL_INFO_ALL;
  private static final String ACTION_PREPARE_FOR_CONNECTION = "PrepareForConnection";
  private static final String ACTION_SET_AV_TRANSPORT_URI = "SetAVTransportURI";
  private static final String ACTION_GET_PROTOCOL_INFO = "GetProtocolInfo";
  private static final String ACTION_PLAY = "Play";
  private static final String ACTION_PAUSE = "Pause";
  private static final String ACTION_STOP = "Stop";
  private static final String ACTION_SET_VOLUME = "SetVolume";
  private static final String ACTION_GET_VOLUME = "GetVolume";
  private static final String INPUT_CURRENT_URI_METADATA = "CurrentURIMetaData";
  private static final String INPUT_DESIRED_VOLUME = "DesiredVolume";
  private static final String INPUT_CHANNEL = "Channel";
  private static final String INPUT_MASTER = "Master";
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
  @NonNull
  private final RadioService.UpnpAction actionPlay;
  @NonNull
  private final RadioService.UpnpAction actionPause;
  @NonNull
  private final RadioService.UpnpAction actionStop;
  @NonNull
  private final RadioService.UpnpAction actionPrepareForConnection;
  @NonNull
  private final RadioService.UpnpAction actionSetVolume;
  @NonNull
  private final RadioService.UpnpAction actionGetVolume;
  @NonNull
  private final RadioService.UpnpAction actionSetAvTransportUri;
  @NonNull
  private final RadioService.UpnpAction actionGetProtocolInfo;
  @NonNull
  private String contentType = "audio/mpeg";
  @NonNull
  private String radioUri = "";
  private int currentVolume;
  private int volumeDirection = AudioManager.ADJUST_SAME;
  @NonNull
  private String instanceId = "0";

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
    actionPlay = new RadioService.UpnpAction(avTransportService, ACTION_PLAY) {
      @Override
      public ActionInvocation<?> getActionInvocation() {
        ActionInvocation<?> actionInvocation = getActionInvocation(instanceId);
        actionInvocation.setInput("Speed", "1");
        return actionInvocation;
      }

      @Override
      protected void success(@NonNull ActionInvocation<?> actionInvocation) {
        changeAndNotifyState(PlaybackStateCompat.STATE_PLAYING);
        UpnpPlayerAdapter.this.upnpActionControler.runNextAction();
      }

      @Override
      protected void failure() {
        abort();
      }
    };
    actionPause = new RadioService.UpnpAction(avTransportService, ACTION_PAUSE) {
      @Override
      public ActionInvocation<?> getActionInvocation() {
        return getActionInvocation(instanceId);
      }

      @Override
      protected void success(@NonNull ActionInvocation<?> actionInvocation) {
        changeAndNotifyState(PlaybackStateCompat.STATE_PAUSED);
        UpnpPlayerAdapter.this.upnpActionControler.runNextAction();
      }

      @Override
      protected void failure() {
        abort();
      }
    };
    actionStop = new RadioService.UpnpAction(avTransportService, ACTION_STOP) {
      @Override
      public ActionInvocation<?> getActionInvocation() {
        return getActionInvocation(instanceId);
      }

      @Override
      protected void success(@NonNull ActionInvocation<?> actionInvocation) {
        changeAndNotifyState(PlaybackStateCompat.STATE_NONE);
        UpnpPlayerAdapter.this.upnpActionControler.runNextAction();
      }

      @Override
      protected void failure() {
        abort();
      }
    };
    actionPrepareForConnection =
      new RadioService.UpnpAction(connectionManager, ACTION_PREPARE_FOR_CONNECTION) {
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
          ActionArgumentValue<?> instanceIdArgument = actionInvocation.getOutput("AVTransportID");
          if (instanceIdArgument != null) {
            instanceId = instanceIdArgument.getValue().toString();
          }
          UpnpPlayerAdapter.this.upnpActionControler.runNextAction();
        }

        @Override
        protected void failure() {
          abort();
        }
      };
    actionSetVolume = new RadioService.UpnpAction(renderingControl, ACTION_SET_VOLUME) {
      @Override
      public ActionInvocation<?> getActionInvocation() {
        ActionInvocation<?> actionInvocation = getActionInvocation(instanceId);
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
    actionGetVolume = new RadioService.UpnpAction(renderingControl, ACTION_GET_VOLUME) {
      @Override
      public ActionInvocation<?> getActionInvocation() {
        ActionInvocation<?> actionInvocation = getActionInvocation(instanceId);
        actionInvocation.setInput(INPUT_CHANNEL, INPUT_MASTER);
        return actionInvocation;
      }

      @Override
      protected void success(@NonNull ActionInvocation<?> actionInvocation) {
        currentVolume =
          Integer.parseInt(actionInvocation.getOutput("CurrentVolume").getValue().toString());
        switch (volumeDirection) {
          case AudioManager.ADJUST_LOWER:
            currentVolume = Math.max(0, --currentVolume);
            actionSetVolume.execute(UpnpPlayerAdapter.this.upnpActionControler, false);
            break;
          case AudioManager.ADJUST_RAISE:
            currentVolume++;
            actionSetVolume.execute(UpnpPlayerAdapter.this.upnpActionControler, false);
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
    actionSetAvTransportUri =
      new RadioService.UpnpAction(avTransportService, ACTION_SET_AV_TRANSPORT_URI) {
        @Override
        public ActionInvocation<?> getActionInvocation() {
          ActionInvocation<?> actionInvocation = getActionInvocation(instanceId);
          String metadata = getMetaData();
          actionInvocation.setInput("CurrentURI", radioUri);
          actionInvocation.setInput(INPUT_CURRENT_URI_METADATA, metadata);
          Log.d(LOG_TAG, "SetAVTransportURI=> InstanceID: " + instanceId);
          Log.d(LOG_TAG, "SetAVTransportURI=> CurrentURI: " + radioUri);
          Log.d(LOG_TAG, "SetAVTransportURI=> CurrentURIMetaData: " + metadata);
          return actionInvocation;
        }

        @Override
        protected void success(@NonNull ActionInvocation<?> actionInvocation) {
          UpnpPlayerAdapter.this.upnpActionControler.runNextAction();
        }

        @Override
        protected void failure() {
          abort();
        }
      };
    actionGetProtocolInfo =
      new RadioService.UpnpAction(connectionManager, ACTION_GET_PROTOCOL_INFO) {
        @Override
        public ActionInvocation<?> getActionInvocation() {
          return getActionInvocation(null);
        }

        @Override
        public void success(@NonNull ActionInvocation<?> actionInvocation) {
          List<String> protocolInfos = new Vector<>();
          for (String protocolInfo : actionInvocation.getOutput("Sink").toString().split(",")) {
            if (UpnpPlayerAdapter.isHandling(protocolInfo)) {
              Log.d(LOG_TAG, "Audio ProtocolInfo: " + protocolInfo);
              protocolInfos.add(protocolInfo);
            }
          }
          if (!protocolInfos.isEmpty()) {
            UpnpPlayerAdapter.this.upnpActionControler.putProtocolInfo(
              getDevice(), protocolInfos);
          }
          UpnpPlayerAdapter.this.upnpActionControler.runNextAction();
        }

        @Override
        protected void failure() {
          abort();
        }
      };
  }

  @Override
  public void adjustVolume(int direction) {
    // Do only if nothing done currently
    if (actionGetVolume.isAvailable() && (volumeDirection == AudioManager.ADJUST_SAME)) {
      volumeDirection = direction;
      actionGetVolume.execute(upnpActionControler, false);
    }
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
          String contentType = NetworkTester.getStreamContentType(radio.getURL());
          // Now we can call GetProtocolInfo, only if current action not cancelled
          if (contentType != null) {
            upnpActionControler.putContentType(radio, contentType);
            UpnpPlayerAdapter.this.contentType = contentType;
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
    upnpActionControler.schedule(actionPlay);
  }

  // Nota: as tested, not supported by DLNA device
  @Override
  public void onPause() {
    upnpActionControler.schedule(actionPause);
  }

  @Override
  public void onStop() {
    upnpActionControler.schedule(actionStop);
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
    // Do prepare if available
    if (actionPrepareForConnection.isAvailable()) {
      upnpActionControler.schedule(actionPrepareForConnection);
    }
    if (upnpActionControler.getProtocolInfo(device) == null) {
      upnpActionControler.schedule(actionGetProtocolInfo);
    }
    upnpActionControler.schedule(actionSetAvTransportUri);
    upnpActionControler.schedule(actionPlay);
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
      "<res duration=\"0:00:00\" protocolInfo=\"" +
      PROTOCOL_INFO_HEADER + getContentType() + PROTOCOL_INFO_ALL + "\">" + radioUri + "</res>" +
      "<upnp:albumArtURI>" + logo + "</upnp:albumArtURI>" +
      "</item>" +
      "</DIDL-Lite>";
  }

  private void abort() {
    upnpActionControler.releaseActions(device);
    changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
  }

  // Special handling for MIME type
  @NonNull
  private String getContentType() {
    final String HEAD_EXP = "[a-z]*/";
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
    return contentType;
  }

  @Nullable
  private String searchContentType(@NonNull String contentType) {
    List<String> protocolInfos = upnpActionControler.getProtocolInfo(device);
    if (protocolInfos != null) {
      Pattern mimePattern = Pattern.compile(".*:.*:(" + contentType + "):.*");
      for (String protocolInfo : protocolInfos) {
        Matcher matcher = mimePattern.matcher(protocolInfo);
        if (matcher.find()) {
          return matcher.group(1);
        }
      }
    }
    return null;
  }
}