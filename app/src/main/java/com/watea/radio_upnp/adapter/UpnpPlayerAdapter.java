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
import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.UDAServiceId;

public class UpnpPlayerAdapter extends PlayerAdapter {
  public static final ServiceId AV_TRANSPORT_SERVICE_ID = new UDAServiceId("AVTransport");
  private static final ServiceId RENDERING_CONTROL_ID = new UDAServiceId("RenderingControl");
  private static final String LOG_TAG = UpnpPlayerAdapter.class.getName();
  private static final String UPNP_ACTION_SET_AV_TRANSPORT_URI = "SetAVTransportURI";
  private static final String UPNP_ACTION_PLAY = "Play";
  private static final String UPNP_ACTION_PAUSE = "Pause";
  private static final String UPNP_ACTION_STOP = "Stop";
  private static final String UPNP_ACTION_SET_VOLUME = "SetVolume";
  private static final String UPNP_ACTION_GET_VOLUME = "GetVolume";
  private static final String UPNP_INPUT_DESIRED_VOLUME = "DesiredVolume";
  private static final int DEFAULT = -1;
  private static final int REMOTE_LOGO_SIZE = 140;
  @Nullable
  private Device dlnaDevice = null;
  @Nullable
  private AndroidUpnpService androidUpnpService = null;
  private Integer currentVolume;
  private boolean isVolumeInProgress;

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
    // Do nothing if volume not controlled
    if ((currentVolume == DEFAULT) || isVolumeInProgress) {
      return;
    }
    Integer desiredVolume;
    switch (direction) {
      case AudioManager.ADJUST_LOWER:
        desiredVolume = Math.max(0, --currentVolume);
        break;
      case AudioManager.ADJUST_RAISE:
        desiredVolume = ++currentVolume;
        break;
      default:
        // Nothing to do
        return;
    }
    isVolumeInProgress = true;
    ActionInvocation actionInvocation =
      getRenderingControlUpnpActionInvocation(UPNP_ACTION_SET_VOLUME);
    actionInvocation.setInput(UPNP_INPUT_DESIRED_VOLUME, desiredVolume.toString());
    upnpExecuteAction(actionInvocation);
  }

  @Override
  protected boolean isLocal() {
    return false;
  }

  @Override
  protected void onPrepareFromMediaId(@NonNull Radio radio) {
    // dlnaDevice must be defined
    if (dlnaDevice == null) {
      throw new RuntimeException("dlnaDevice not defined");
    }
    ActionInvocation actionInvocation;
    // Volume
    currentVolume = DEFAULT;
    isVolumeInProgress = true;
    if (dlnaDevice.findService(RENDERING_CONTROL_ID) != null) {
      actionInvocation = getRenderingControlUpnpActionInvocation(UPNP_ACTION_GET_VOLUME);
      upnpExecuteAction(actionInvocation);
    }
    // Rendering
    String radioUri = RadioHandler
      .getHandledUri(httpServer.getUri(), radio, getLockKey()).toString();
    String radioName = radio.getName();
    actionInvocation = getAVTransportUpnpActionInvocation(UPNP_ACTION_SET_AV_TRANSPORT_URI);
    actionInvocation.setInput("CurrentURI", radioUri);
    actionInvocation.setInput("CurrentURIMetaData",
      "<DIDL-Lite " +
        "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"" +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"" +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
        "<item id=\"" + radioName + "\" parentID=\"0\" restricted=\"1\">" +
        // object.item.audioItem.audioBroadcast not valid
        "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
        "<dc:title>" + radioName + "</dc:title>" +
        "<upnp:artist>" + context.getString(R.string.app_name) + "</upnp:artist>" +
        "<upnp:album>" + context.getString(R.string.live_streaming) + "</upnp:album>" +
        "<res protocolInfo=\"http-get:*:audio/mpeg:*\">" + radioUri + "</res>" +
        "<upnp:albumArtURI>" + httpServer.createLogoFile(radio, REMOTE_LOGO_SIZE) +
        "</upnp:albumArtURI>" +
        "</item>" +
        "</DIDL-Lite>");
    upnpExecuteAction(actionInvocation);
  }

  public void onPlay() {
    if (dlnaDevice == null) {
      Log.i(LOG_TAG, "onPlay on null DlnaDevice");
      return;
    }
    ActionInvocation actionInvocation = getAVTransportUpnpActionInvocation(UPNP_ACTION_PLAY);
    actionInvocation.setInput("Speed", "1");
    upnpExecuteAction(actionInvocation);
  }

  // Nota: as tested, not supported by DLNA device
  @Override
  public void onPause() {
    if (dlnaDevice == null) {
      Log.i(LOG_TAG, "onPause on null DlnaDevice");
      return;
    }
    ActionInvocation actionInvocation = getAVTransportUpnpActionInvocation(UPNP_ACTION_PAUSE);
    upnpExecuteAction(actionInvocation);
  }

  @Override
  public void onStop() {
    if (dlnaDevice == null) {
      Log.i(LOG_TAG, "onStop on null Radio or DlnaDevice");
      return;
    }
    ActionInvocation actionInvocation = getAVTransportUpnpActionInvocation(UPNP_ACTION_STOP);
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

  // Execute asynchronous in the background
  private void upnpExecuteAction(@NonNull ActionInvocation actionInvocation) {
    if (androidUpnpService == null) {
      Log.d(LOG_TAG, "upnpExecuteAction: AndroidUpnpService is null");
      changeAndNotifyState(PlaybackStateCompat.STATE_ERROR, getLockKey());
    } else {
      androidUpnpService.getControlPoint().execute(new TaggedActionCallback(actionInvocation));
    }
  }

  @NonNull
  private ActionInvocation getUpnpActionInvocation(
    @NonNull ServiceId serviceId, @NonNull String action) {
    // dlnaDevice must be defined
    if (dlnaDevice == null) {
      throw new RuntimeException("dlnaDevice not defined");
    }
    //noinspection unchecked
    ActionInvocation actionInvocation =
      new ActionInvocation(dlnaDevice.findService(serviceId).getAction(action));
    actionInvocation.setInput("InstanceID", "0");
    return actionInvocation;
  }

  @NonNull
  private ActionInvocation getAVTransportUpnpActionInvocation(@NonNull String action) {
    return getUpnpActionInvocation(AV_TRANSPORT_SERVICE_ID, action);
  }

  @NonNull
  private ActionInvocation getRenderingControlUpnpActionInvocation(@NonNull String action) {
    ActionInvocation actionInvocation = getUpnpActionInvocation(RENDERING_CONTROL_ID, action);
    actionInvocation.setInput("Channel", "Master");
    return actionInvocation;
  }

  private class TaggedActionCallback extends ActionCallback {
    @NonNull
    private final String actionLockKey;

    private TaggedActionCallback(@NonNull ActionInvocation actionInvocation) {
      super(actionInvocation);
      actionLockKey = getLockKey();
    }

    @Override
    public void success(ActionInvocation invocation) {
      String action = actionInvocation.getAction().getName();
      Log.d(LOG_TAG, "Successfully called UPnP action: " + action);
      for (ActionArgumentValue value : actionInvocation.getOutput()) {
        Log.d(LOG_TAG, value.toString());
      }
      switch (action) {
        case UpnpPlayerAdapter.UPNP_ACTION_GET_VOLUME:
          synchronized (UpnpPlayerAdapter.this) {
            currentVolume =
              Integer.parseInt(actionInvocation.getOutput("CurrentVolume").toString());
            isVolumeInProgress = false;
            Log.d(LOG_TAG, "Volume: " + currentVolume);
          }
          break;
        case UpnpPlayerAdapter.UPNP_ACTION_SET_VOLUME:
          synchronized (UpnpPlayerAdapter.this) {
            currentVolume =
              Integer.parseInt(actionInvocation.getInput(UPNP_INPUT_DESIRED_VOLUME).toString());
            isVolumeInProgress = false;
            Log.d(LOG_TAG, "Volume: " + currentVolume);
          }
          break;
        case UpnpPlayerAdapter.UPNP_ACTION_SET_AV_TRANSPORT_URI:
          changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING, actionLockKey);
          // Now we can call Play with same tag, only if current action not cancelled
          synchronized (UpnpPlayerAdapter.this) {
            if (actionLockKey.equals(getLockKey())) {
              onPlay();
            }
          }
          break;
        case UpnpPlayerAdapter.UPNP_ACTION_STOP:
          changeAndNotifyState(PlaybackStateCompat.STATE_NONE, actionLockKey);
          break;
        case UpnpPlayerAdapter.UPNP_ACTION_PLAY:
          changeAndNotifyState(PlaybackStateCompat.STATE_PLAYING, actionLockKey);
          break;
        // Should not happen as PAUSE not allowed
        case UpnpPlayerAdapter.UPNP_ACTION_PAUSE:
          changeAndNotifyState(PlaybackStateCompat.STATE_PAUSED, actionLockKey);
          break;
        // Should not happen
        default:
          Log.e(LOG_TAG, "=> State not managed: " + action);
      }
    }

    @Override
    public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
      Log.d(LOG_TAG, defaultMsg);
      String action = actionInvocation.getAction().getName();
      // Don't handle error on volume
      if (action.equals(UpnpPlayerAdapter.UPNP_ACTION_GET_VOLUME) ||
        action.equals(UPNP_ACTION_SET_VOLUME)) {
        isVolumeInProgress = false;
        return;
      }
      changeAndNotifyState(PlaybackStateCompat.STATE_ERROR, actionLockKey);
    }
  }
}