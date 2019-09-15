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
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.service.HttpServer;
import com.watea.radio_upnp.service.RadioHandler;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.UDAServiceId;

import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import static com.watea.radio_upnp.service.NetworkTester.getStreamContentType;

public class UpnpPlayerAdapter extends PlayerAdapter {
  public static final ServiceId AV_TRANSPORT_SERVICE_ID = new UDAServiceId("AVTransport");
  private static final ServiceId RENDERING_CONTROL_ID = new UDAServiceId("RenderingControl");
  private static final ServiceId CONNECTION_MANAGER_ID = new UDAServiceId("ConnectionManager");
  private static final String LOG_TAG = UpnpPlayerAdapter.class.getName();
  private static final String MPEG = "audio/mpeg";
  private static final String UPNP_ACTION_SET_AV_TRANSPORT_URI = "SetAVTransportURI";
  private static final String UPNP_ACTION_GET_PROTOCOL_INFO = "GetProtocolInfo";
  private static final String UPNP_ACTION_PLAY = "Play";
  private static final String UPNP_ACTION_PAUSE = "Pause";
  private static final String UPNP_ACTION_STOP = "Stop";
  private static final String UPNP_ACTION_SET_VOLUME = "SetVolume";
  private static final String UPNP_ACTION_GET_VOLUME = "GetVolume";
  private static final String UPNP_INPUT_DESIRED_VOLUME = "DesiredVolume";
  private static final int REMOTE_LOGO_SIZE = 140;
  @NonNull
  private static final Map<Long, String> contentTypes = new Hashtable<>();
  @Nullable
  private Device dlnaDevice = null;
  @Nullable
  private AndroidUpnpService androidUpnpService = null;
  private int volumeDirection = AudioManager.ADJUST_SAME;

  public UpnpPlayerAdapter(
    @NonNull Context context, @NonNull HttpServer httpServer, @NonNull Listener listener) {
    super(context, httpServer, listener);
  }

  // Must be called
  public void setAndroidUpnpService(@NonNull AndroidUpnpService androidUpnpService) {
    this.androidUpnpService = androidUpnpService;
  }

  // Must be called
  public boolean setDlnaDevice(@Nullable String identity) {
    if (androidUpnpService != null) {
      for (Device device : androidUpnpService.getRegistry().getDevices()) {
        if (device.getIdentity().getUdn().getIdentifierString().equals(identity)) {
          dlnaDevice = device;
          return true;
        }
      }
    }
    dlnaDevice = null;
    return false;
  }

  @Override
  public void adjustVolume(int direction) {
    // Do nothing if volume not controlled or already running
    if ((dlnaDevice == null) ||
      (dlnaDevice.findService(RENDERING_CONTROL_ID) == null) ||
      (volumeDirection != AudioManager.ADJUST_SAME)) {
      return;
    }
    volumeDirection = direction;
    upnpExecuteAction(getRenderingControlActionInvocation(UPNP_ACTION_GET_VOLUME));
  }

  @Override
  protected boolean isLocal() {
    return false;
  }

  @Override
  protected void onPrepareFromMediaId(@NonNull final Radio radio) {
    if (dlnaDevice == null) {
      throw new RuntimeException("onPrepareFromMediaId: dlnaDevice not defined");
    }
    final Service connectionManager = dlnaDevice.findService(CONNECTION_MANAGER_ID);
    if (connectionManager == null) {
      Log.i(LOG_TAG, "onPrepareFromMediaId: ConnectionManager not available");
      return;
    }
    // Robustness
    volumeDirection = AudioManager.ADJUST_SAME;
    // Fetch content type and start
    // Current action tag
    final String actionLockKey = lockKey;
    if (contentTypes.get(radio.getId()) == null) {
      // Fetch ContentType before launching
      new Thread() {
        @Override
        public void run() {
          final String contentType = getStreamContentType(radio.getURL());
          // Now we can call GetProtocolInfo, only if current action not cancelled
          handler.post(new Runnable() {
            @Override
            public void run() {
              if (contentType != null) {
                contentTypes.put(radio.getId(), contentType);
              }
              if (actionLockKey.equals(lockKey)) {
                launchGetProtocolInfo(connectionManager, radio);
              }
            }
          });
        }
      }.start();
    } else {
      launchGetProtocolInfo(connectionManager, radio);
    }
  }

  public void onPlay() {
    if (dlnaDevice == null) {
      Log.i(LOG_TAG, "onPlay: dlnaDevice not defined");
      return;
    }
    ActionInvocation actionInvocation = getAVTransportActionInvocation(UPNP_ACTION_PLAY);
    actionInvocation.setInput("Speed", "1");
    upnpExecuteAction(actionInvocation);
  }

  // Nota: as tested, not supported by DLNA device
  @Override
  public void onPause() {
    if (dlnaDevice == null) {
      Log.i(LOG_TAG, "onPause: dlnaDevice not definede");
      return;
    }
    ActionInvocation actionInvocation = getAVTransportActionInvocation(UPNP_ACTION_PAUSE);
    upnpExecuteAction(actionInvocation);
  }

  @Override
  public void onStop() {
    if (dlnaDevice == null) {
      Log.i(LOG_TAG, "onStop:  dlnaDevice not defined");
      return;
    }
    ActionInvocation actionInvocation = getAVTransportActionInvocation(UPNP_ACTION_STOP);
    upnpExecuteAction(actionInvocation);
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

  /* DLNA.ORG_FLAGS, padded with 24 trailing 0s
   *     80000000  31  senderPaced
   *     40000000  30  lsopTimeBasedSeekSupported
   *     20000000  29  lsopByteBasedSeekSupported
   *     10000000  28  playcontainerSupported
   *      8000000  27  s0IncreasingSupported
   *      4000000  26  sNIncreasingSupported
   *      2000000  25  rtspPauseSupported
   *      1000000  24  streamingTransferModeSupported
   *       800000  23  interactiveTransferModeSupported
   *       400000  22  backgroundTransferModeSupported
   *       200000  21  connectionStallingSupported
   *       100000  20  dlnaVersion15Supported
   *
   *     Example: (1 << 24) | (1 << 22) | (1 << 21) | (1 << 20)
   *       DLNA.ORG_FLAGS=01700000[000000000000000000000000] // [] show padding
   *
   * If DLNA.ORG_OP=11, then left/rght keys uses range header, and up/down uses TimeSeekRange.DLNA.ORG header
   * If DLNA.ORG_OP=10, then left/rght and up/down keys uses TimeSeekRange.DLNA.ORG header
   * If DLNA.ORG_OP=01, then left/rght keys uses range header, and up/down keys are disabled
   * and if DLNA.ORG_OP=00, then all keys are disabled
   * DLNA.ORG_CI 0 = native 1, = transcoded
   * DLNA.ORG_PN Media file format profile, usually combination of container/video codec/audio codec/sometimes region
   * Example:
   * DLNA_PARAMS = "DLNA.ORG_PN=MP3;DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"; */
  private void launchGetProtocolInfo(@NonNull Service connectionManager, @NonNull Radio radio) {
    //noinspection unchecked
    upnpExecuteAction(
      new ActionInvocation(connectionManager.getAction(UPNP_ACTION_GET_PROTOCOL_INFO)), radio);
  }

  // Execute asynchronous in the background, no radio actually known
  private void upnpExecuteAction(@NonNull ActionInvocation actionInvocation) {
    upnpExecuteAction(actionInvocation, Radio.DUMMY_RADIO);
  }

  // Execute asynchronous in the background
  private void upnpExecuteAction(@NonNull ActionInvocation actionInvocation, @NonNull Radio radio) {
    if (androidUpnpService == null) {
      Log.d(LOG_TAG, "upnpExecuteAction: AndroidUpnpService is null");
      postChangeAndNotifyState(PlaybackStateCompat.STATE_ERROR, lockKey);
    } else {
      androidUpnpService
        .getControlPoint()
        .execute(new TaggedActionCallback(actionInvocation, radio));
    }
  }

  @NonNull
  private ActionInvocation getInstanceActionInvocation(
    @NonNull ServiceId serviceId, @NonNull String action) {
    if (dlnaDevice == null) {
      throw new RuntimeException("getInstanceActionInvocation on null dlnaDevice for: " + action);
    }
    //noinspection unchecked
    ActionInvocation actionInvocation =
      new ActionInvocation(dlnaDevice.findService(serviceId).getAction(action));
    actionInvocation.setInput("InstanceID", "0");
    return actionInvocation;
  }

  @NonNull
  private ActionInvocation getAVTransportActionInvocation(@NonNull String action) {
    return getInstanceActionInvocation(AV_TRANSPORT_SERVICE_ID, action);
  }

  @NonNull
  private ActionInvocation getRenderingControlActionInvocation(@NonNull String action) {
    ActionInvocation actionInvocation = getInstanceActionInvocation(RENDERING_CONTROL_ID, action);
    actionInvocation.setInput("Channel", "Master");
    return actionInvocation;
  }

  private class TaggedActionCallback extends ActionCallback {
    @NonNull
    private final String actionLockKey;
    @NonNull
    private final Radio radio;

    private TaggedActionCallback(@NonNull ActionInvocation actionInvocation, @NonNull Radio radio) {
      super(actionInvocation);
      actionLockKey = lockKey;
      this.radio = radio;
    }

    @Override
    public void success(ActionInvocation actionInvocation) {
      String action = actionInvocation.getAction().getName();
      Log.d(LOG_TAG, "Successfully called UPnP action: " + action);
      switch (action) {
        case UpnpPlayerAdapter.UPNP_ACTION_GET_VOLUME:
          if (actionLockKey.equals(lockKey)) {
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
            actionInvocation = getRenderingControlActionInvocation(UPNP_ACTION_SET_VOLUME);
            actionInvocation.setInput(UPNP_INPUT_DESIRED_VOLUME, Integer.toString(currentVolume));
            upnpExecuteAction(actionInvocation, radio);
            Log.d(LOG_TAG, "Volume required: " + currentVolume);
          }
          break;
        case UpnpPlayerAdapter.UPNP_ACTION_SET_VOLUME:
          // Done!
          // Lock key should be tested here ot be rigorous
          volumeDirection = AudioManager.ADJUST_SAME;
          Log.d(LOG_TAG, "Volume set: " + actionInvocation.getInput(UPNP_INPUT_DESIRED_VOLUME));
          break;
        case UpnpPlayerAdapter.UPNP_ACTION_SET_AV_TRANSPORT_URI:
          // Now we can call Play, only if current action not cancelled
          if (actionLockKey.equals(lockKey)) {
            postChangeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING, actionLockKey);
            onPlay();
          }
          break;
        case UpnpPlayerAdapter.UPNP_ACTION_STOP:
          postChangeAndNotifyState(PlaybackStateCompat.STATE_NONE, actionLockKey);
          break;
        case UpnpPlayerAdapter.UPNP_ACTION_PLAY:
          postChangeAndNotifyState(PlaybackStateCompat.STATE_PLAYING, actionLockKey);
          break;
        // Should not happen as PAUSE not allowed
        case UpnpPlayerAdapter.UPNP_ACTION_PAUSE:
          postChangeAndNotifyState(PlaybackStateCompat.STATE_PAUSED, actionLockKey);
          break;
        case UpnpPlayerAdapter.UPNP_ACTION_GET_PROTOCOL_INFO:
          // Now we can call SetAVTransportURI, only if current action not cancelled
          if (actionLockKey.equals(lockKey)) {
            String contentType = contentTypes.get(radio.getId());
            if (contentType == null) {
              contentType = MPEG;
            }
            List<String> protocolInfos = new Vector<>();
            // DLNA player data for MIME type
            for (String string : actionInvocation.getOutput("Sink").toString().split(",")) {
              if (string.contains(contentType)) {
                protocolInfos.add(string);
              }
            }
            if (protocolInfos.isEmpty()) {
              protocolInfos.add("http-get:*:\"" + contentType + ":*");
            }
            // DLNA action call
            String radioUri =
              RadioHandler.getHandledUri(httpServer.getUri(), radio, actionLockKey).toString();
            actionInvocation = getAVTransportActionInvocation(UPNP_ACTION_SET_AV_TRANSPORT_URI);
            actionInvocation.setInput("CurrentURI", radioUri);
            actionInvocation.setInput("CurrentURIMetaData", getMetaData(radioUri, protocolInfos));
            upnpExecuteAction(actionInvocation, radio);
          }
          break;
        // Should not happen
        default:
          Log.e(LOG_TAG, "=> State not managed: " + action);
      }
    }

    @Override
    public void failure(
      ActionInvocation actionInvocation, UpnpResponse operation, String defaultMsg) {
      Log.d(LOG_TAG, defaultMsg);
      String action = actionInvocation.getAction().getName();
      // Don't handle error on volume
      // Lock key should be tested here ot be rigorous
      if (action.equals(UpnpPlayerAdapter.UPNP_ACTION_GET_VOLUME) ||
        action.equals(UPNP_ACTION_SET_VOLUME)) {
        // No more action
        volumeDirection = AudioManager.ADJUST_SAME;
        return;
      }
      postChangeAndNotifyState(PlaybackStateCompat.STATE_ERROR, actionLockKey);
    }

    // Create DIDL-Lite metadata
    @NonNull
    private String getMetaData(@NonNull String radioUri, @NonNull List<String> protocolInfos) {
      StringBuilder metaData = new StringBuilder("<DIDL-Lite " +
        "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"" +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item id=\"" + radio.getName() + "\" parentID=\"0\" restricted=\"1\">" +
        // object.item.audioItem.audioBroadcast not valid
        "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
        "<dc:title>" + radio.getName() + "</dc:title>" +
        "<upnp:artist>" + context.getString(R.string.app_name) + "</upnp:artist>" +
        "<upnp:album>" + context.getString(R.string.live_streaming) + "</upnp:album>");
      for (String protocolInfo : protocolInfos) {
        metaData
          .append("<res duration=\"0:00:00\" protocolInfo=\"")
          .append(protocolInfo)
          .append("\">")
          .append(radioUri)
          .append("</res>");
      }
      return metaData +
        "<upnp:albumArtURI>" + httpServer.createLogoFile(radio, REMOTE_LOGO_SIZE) +
        "</upnp:albumArtURI>" +
        "</item>" +
        "</DIDL-Lite>";
    }
  }
}