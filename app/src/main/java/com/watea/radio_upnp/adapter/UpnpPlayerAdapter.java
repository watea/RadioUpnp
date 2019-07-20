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
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.service.HttpServer;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionArgumentValue;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.types.ServiceId;
import org.fourthline.cling.model.types.UDAServiceId;

public class UpnpPlayerAdapter extends PlayerAdapter {
  private static final String LOG_TAG = UpnpPlayerAdapter.class.getSimpleName();
  private static final ServiceId aVTransportServiceId = new UDAServiceId("AVTransport");
  private static final String UPNP_ACTION_SET_AV_TRANSPORT_URI = "SetAVTransportURI";
  private static final String UPNP_ACTION_PLAY = "Play";
  private static final String UPNP_ACTION_PAUSE = "Pause";
  private static final String UPNP_ACTION_STOP = "Stop";
  private static final int REMOTE_LOGO_SIZE = 140;
  @Nullable
  private Device mDlnaDevice;
  @Nullable
  private AndroidUpnpService mAndroidUpnpService;
  @Nullable
  private Object mLockKey;

  public UpnpPlayerAdapter(
    @NonNull Context context, @NonNull HttpServer httpServer, @NonNull Listener listener) {
    super(context, httpServer, listener, false);
    mAndroidUpnpService = null;
    mDlnaDevice = null;
    mLockKey = null;
  }

  // Must be called
  public void setAndroidUpnpService(AndroidUpnpService androidUpnpService) {
    mAndroidUpnpService = androidUpnpService;
  }

  // Must be called
  public boolean setDlnaDevice(int hashcode) {
    if (mAndroidUpnpService != null) {
      for (Device device : mAndroidUpnpService.getRegistry().getDevices()) {
        if (device.hashCode() == hashcode) {
          mDlnaDevice = device;
          return true;
        }
      }
    }
    mDlnaDevice = null;
    return false;
  }

  @Override
  protected void onPrepareFromMediaId() {
    assert mDlnaDevice != null;
    assert mRadio != null;
    // Shall not be null
    mLockKey = getLockKey();
    String radioUri = mHttpServer.getRadioUri(mRadio).toString();
    String radioName = mRadio.getName();
    ActionInvocation actionInvocation =
      getUpnpActionInvocation(mDlnaDevice, UPNP_ACTION_SET_AV_TRANSPORT_URI);
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
        "<upnp:artist>" + mContext.getString(R.string.app_name) + "</upnp:artist>" +
        "<upnp:album>" + mContext.getString(R.string.live_streaming) + "</upnp:album>" +
        "<res protocolInfo=\"http-get:*:audio/mpeg:*\">" + radioUri + "</res>" +
        "<upnp:albumArtURI>" + mHttpServer.createLogoFile(mRadio, REMOTE_LOGO_SIZE) +
        "</upnp:albumArtURI>" +
        "</item>" +
        "</DIDL-Lite>");
    upnpExecuteAction(actionInvocation);
  }

  @Override
  public void onPlay() {
    if (mDlnaDevice == null) {
      Log.i(LOG_TAG, "onPlay on null DlnaDevice");
      return;
    }
    ActionInvocation actionInvocation = getUpnpActionInvocation(mDlnaDevice, UPNP_ACTION_PLAY);
    actionInvocation.setInput("Speed", "1");
    upnpExecuteAction(actionInvocation);
  }

  // Nota: as tested, not supported by DLNA device
  @Override
  public void onPause() {
    if (mDlnaDevice == null) {
      Log.i(LOG_TAG, "onPause on null DlnaDevice");
      return;
    }
    ActionInvocation actionInvocation = getUpnpActionInvocation(mDlnaDevice, UPNP_ACTION_PAUSE);
    upnpExecuteAction(actionInvocation);
  }

  @Override
  public void onStop() {
    if (mDlnaDevice == null) {
      Log.i(LOG_TAG, "onStop on null Radio or DlnaDevice");
      return;
    }
    ActionInvocation actionInvocation = getUpnpActionInvocation(mDlnaDevice, UPNP_ACTION_STOP);
    upnpExecuteAction(actionInvocation);
  }

  @Override
  public void onRelease() {
  }

  @Override
  protected long getAvailableActions() {
    long actions = PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID | PlaybackStateCompat.ACTION_STOP;
    switch (mState) {
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
    if (mAndroidUpnpService == null) {
      Log.d(LOG_TAG, "upnpExecuteAction: AndroidUpnpService is null");
      changeAndNotifyState(PlaybackStateCompat.STATE_ERROR, mLockKey);
    } else {
      mAndroidUpnpService.getControlPoint().execute(new ActionCallback(actionInvocation) {
        @Override
        public void success(ActionInvocation invocation) {
          String action = actionInvocation.getAction().getName();
          Log.d(LOG_TAG, "Successfully called Upnp action: " + action);
          for (ActionArgumentValue value : actionInvocation.getOutput()) {
            Log.d(LOG_TAG, value.toString());
          }
          switch (action) {
            case UpnpPlayerAdapter.UPNP_ACTION_SET_AV_TRANSPORT_URI:
              changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING, mLockKey);
              // Now we can call Play
              onPlay();
              break;
            case UpnpPlayerAdapter.UPNP_ACTION_STOP:
              changeAndNotifyState(PlaybackStateCompat.STATE_NONE, mLockKey);
              break;
            case UpnpPlayerAdapter.UPNP_ACTION_PLAY:
              changeAndNotifyState(PlaybackStateCompat.STATE_PLAYING, mLockKey);
              break;
            // Should not happen as PAUSE not allowed
            case UpnpPlayerAdapter.UPNP_ACTION_PAUSE:
              changeAndNotifyState(PlaybackStateCompat.STATE_PAUSED, mLockKey);
              break;
            // Should not happen
            default:
              Log.e(LOG_TAG, "RadioActionCallback.success: state not managed: " + action);
          }
        }

        @Override
        public void failure(
          ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
          Log.d(LOG_TAG, defaultMsg);
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR, mLockKey);
          release();
        }
      });
    }
  }

  @NonNull
  private ActionInvocation getUpnpActionInvocation(
    @NonNull Device dlnaDevice, @NonNull String action) {
    @SuppressWarnings("unchecked")
    ActionInvocation actionInvocation =
      new ActionInvocation(dlnaDevice.findService(aVTransportServiceId).getAction(action));
    actionInvocation.setInput("InstanceID", "0");
    return actionInvocation;
  }
}