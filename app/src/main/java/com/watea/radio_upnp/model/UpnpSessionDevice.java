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

package com.watea.radio_upnp.model;

import android.content.Context;
import android.media.AudioManager;
import android.net.Uri;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.activity.MainActivity;
import com.watea.radio_upnp.service.UpnpStreamServer;
import com.watea.radio_upnp.upnp.Action;
import com.watea.radio_upnp.upnp.ActionController;
import com.watea.radio_upnp.upnp.Device;
import com.watea.radio_upnp.upnp.Request;
import com.watea.radio_upnp.upnp.Service;
import com.watea.radio_upnp.upnp.UpnpAction;

import java.util.function.Function;

@OptIn(markerClass = UnstableApi.class)
public class UpnpSessionDevice extends SessionDevice {
  public static final String PROTOCOL_INFO_TAIL = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
  private static final String LOG_TAG = UpnpSessionDevice.class.getSimpleName();
  private static final String AV_TRANSPORT_SERVICE_ID = "AVTransport";
  private static final String RENDERING_CONTROL_ID = "RenderingControl";
  private static final String CONNECTION_MANAGER_ID = "ConnectionManager";
  private static final String PROTOCOL_INFO_HEADER = "http-get:*:";
  private static final String ACTION_PREPARE_FOR_CONNECTION = "PrepareForConnection";
  private static final String ACTION_SET_AV_TRANSPORT_URI = "SetAVTransportURI";
  private static final String ACTION_PLAY = "Play";
  private static final String ACTION_STOP = "Stop";
  private static final String ACTION_SET_VOLUME = "SetVolume";
  private static final String ACTION_GET_VOLUME = "GetVolume";
  private static final String INPUT_DESIRED_VOLUME = "DesiredVolume";
  private static final String INPUT_CHANNEL = "Channel";
  private static final String INPUT_MASTER = "Master";
  @NonNull
  private final ActionController actionController;
  @NonNull
  private final Uri radioUri;
  @NonNull
  private final Uri logoUri;
  @Nullable
  private final Service connectionManager;
  @Nullable
  private final Service avTransportService;
  @Nullable
  private final Service renderingControl;
  @NonNull
  private final String information; // Not final in further use
  private int currentVolume;
  private int volumeDirection = AudioManager.ADJUST_SAME;
  @NonNull
  private String instanceId = "0";

  public UpnpSessionDevice(
    @NonNull Context context,
    @NonNull ExoPlayer exoPlayer,
    @NonNull UpnpStreamServer.ConnectionSetSupplier upnpStreamServerConnectionSetSupplier,
    @NonNull Listener listener,
    @NonNull String lockKey,
    @NonNull Radio radio,
    @NonNull Uri radioUri,
    @NonNull Uri logoUri,
    @NonNull Device device,
    @NonNull ActionController actionController) {
    super(context, exoPlayer, upnpStreamServerConnectionSetSupplier, listener, lockKey, radio);
    this.radioUri = radioUri;
    this.actionController = actionController;
    this.logoUri = logoUri;
    information = this.context.getString(R.string.app_name);
    // Only devices with AVTransport are processed
    avTransportService = device.getShortService(AV_TRANSPORT_SERVICE_ID);
    // Those services are mandatory in UPnP standard
    connectionManager = device.getShortService(CONNECTION_MANAGER_ID);
    renderingControl = device.getShortService(RENDERING_CONTROL_ID);
  }

  @Override
  public boolean isRemote() {
    return true;
  }

  @Override
  public boolean isUpnp() {
    return true;
  }

  // Not implemented
  @Override
  public void setVolume(float volume) {
  }

  @Override
  public void adjustVolume(int direction) {
    final UpnpAction upnpAction = getActionGetVolume();
    if (upnpAction == null) {
      Log.e(LOG_TAG, "adjustVolume: scheduleActionGetVolume() is null!");
    } else {
      // Do only if nothing done currently
      if (volumeDirection == AudioManager.ADJUST_SAME) {
        volumeDirection = direction;
        upnpAction.execute(true);
      }
    }
  }

  @Override
  public void prepareFromMediaId() {
    super.prepareFromMediaId();
    if (upnpStreamServerConnectionSet == null) {
      Log.e(LOG_TAG, "prepareFromMediaId: unable to connect");
      onState(PlaybackStateCompat.STATE_ERROR);
      return;
    }
    onState(PlaybackStateCompat.STATE_BUFFERING);
    scheduleActionPrepareForConnection();
    scheduleActionSetAvTransportUri();
    scheduleActionPlay();
  }

  @Override
  public void play() {
    Log.e(LOG_TAG, "play: shall not be used!");
  }

  @Override
  public void pause() {
    super.pause();
    scheduleActionStop();
  }

  @Override
  public void stop() {
    super.stop();
    scheduleActionStop();
  }

  @Override
  public void release() {
    super.release();
    scheduleActionStop();
  }

  private void scheduleMandatoryAction(
    @Nullable Action action, @NonNull Function<Action, UpnpAction> function) {
    if (action == null) {
      // Shall not happen
      Log.e(LOG_TAG, "scheduleMandatoryAction: mandatory UPnP action not found");
      onState(PlaybackStateCompat.STATE_ERROR);
    } else {
      function.apply(action).schedule();
    }
  }

  private void scheduleOptionalAction(
    @Nullable Action action, @NonNull Function<Action, UpnpAction> function) {
    if (action != null) {
      function.apply(action).schedule();
    }
  }

  private void scheduleActionPlay() {
    scheduleMandatoryAction(
      (avTransportService == null) ? null : avTransportService.getAction(ACTION_PLAY),
      action -> new UpnpAction(action, actionController, instanceId) {
        @Override
        protected void onSuccess() {
          onState(PlaybackStateCompat.STATE_PLAYING);
          super.onSuccess();
        }

        @Override
        protected void onFailure() {
          Log.d(LOG_TAG, "scheduleActionPlay: error");
          onState(PlaybackStateCompat.STATE_ERROR);
          super.onFailure();
        }
      }
        .addArgument("Speed", "1"));
  }

  private void scheduleActionStop() {
    scheduleMandatoryAction(
      (avTransportService == null) ? null : avTransportService.getAction(ACTION_STOP),
      action -> new UpnpAction(action, actionController, instanceId) {
        @Override
        protected void onSuccess() {
          if (isPaused()) {
            onState(PlaybackStateCompat.STATE_PAUSED);
          }
          super.onSuccess();
        }

        @Override
        protected void onFailure() {
          Log.d(LOG_TAG, "scheduleActionStop: error");
          onState(PlaybackStateCompat.STATE_ERROR);
          super.onFailure();
        }
      });
  }

  private void scheduleActionPrepareForConnection() {
    scheduleOptionalAction(
      (connectionManager == null) ? null : connectionManager.getAction(ACTION_PREPARE_FOR_CONNECTION),
      action -> new UpnpAction(action, actionController) {
        @Override
        protected void onSuccess() {
          final String aVTransportID = getResponse("AVTransportID");
          if (aVTransportID == null) {
            Log.e(LOG_TAG, "Unable to find instanceId");
          } else {
            instanceId = aVTransportID;
          }
          super.onSuccess();
        }
        // Note: failure is not taken into account
      }
        .addArgument("RemoteProtocolInfo", PROTOCOL_INFO_HEADER + "*:" + PROTOCOL_INFO_TAIL)
        .addArgument("PeerConnectionManager", "")
        .addArgument("PeerConnectionID", "-1")
        .addArgument("Direction", "Input"));
  }

  // On calling thread
  private void executeActionSetVolume() {
    final Action action = (renderingControl == null) ? null : renderingControl.getAction(ACTION_SET_VOLUME);
    if (action != null) {
      Log.d(LOG_TAG, "Volume required: " + currentVolume);
      new UpnpAction(action, actionController, instanceId) {
        @Override
        protected void onSuccess() {
          volumeDirection = AudioManager.ADJUST_SAME;
          Log.d(LOG_TAG, "Volume set!");
        }
        // Note: failure is not taken into account
      }
        .addArgument(INPUT_CHANNEL, INPUT_MASTER)
        .addArgument(INPUT_DESIRED_VOLUME, Integer.toString(currentVolume))
        .execute(false);
    }
  }

  @Nullable
  private UpnpAction getActionGetVolume() {
    final Action action = (renderingControl == null) ? null : renderingControl.getAction(ACTION_GET_VOLUME);
    return (action == null) ? null :
      new UpnpAction(action, actionController, instanceId) {
        @Override
        protected void onSuccess() {
          final String response = getResponse("CurrentVolume");
          if (response != null) {
            try {
              currentVolume = Integer.parseInt(response);
              switch (volumeDirection) {
                case AudioManager.ADJUST_LOWER:
                  currentVolume = Math.max(0, --currentVolume);
                  executeActionSetVolume();
                  break;
                case AudioManager.ADJUST_RAISE:
                  currentVolume++;
                  executeActionSetVolume();
                  break;
                default:
                  // Nothing to do
              }
            } catch (Exception exception) {
              Log.e(LOG_TAG, "Unable to set volume", exception);
            }
          }
        }

        @Override
        protected void onFailure() {
          // No more action
          volumeDirection = AudioManager.ADJUST_SAME;
        }
      }
        .addArgument(INPUT_CHANNEL, INPUT_MASTER);
  }

  private void scheduleActionSetAvTransportUri() {
    scheduleMandatoryAction(
      (avTransportService == null) ? null : avTransportService.getAction(ACTION_SET_AV_TRANSPORT_URI),
      action -> new UpnpAction(action, actionController, instanceId) {
        @Override
        protected void onSuccess() {
          onState(PlaybackStateCompat.STATE_BUFFERING);
          super.onSuccess();
        }

        @Override
        protected void onFailure() {
          Log.d(LOG_TAG, "scheduleActionSetAvTransportUri: error");
          onState(PlaybackStateCompat.STATE_ERROR);
          // Release other UPnP actions on this device
          actionController.release(action.getDevice());
          super.onFailure();
        }
      }
        .addArgument("CurrentURI", radioUri.toString())
        .addArgument("CurrentURIMetaData", getMetaData()));
  }

  @NonNull
  private String getDidlMime() {
    // PCM?
    if (MainActivity.getAppPreferences(context).getBoolean(context.getString(R.string.key_pcm_mode), true)) {
      return UpnpStreamServer.PCM_MIME;
    }
    // Relay
    final String content = (upnpStreamServerConnectionSet == null) ? UpnpStreamServer.DEFAULT_MIME : upnpStreamServerConnectionSet.getContent();
    switch (content) {
      case "audio/aac":
      case "audio/x-aac":
      case "audio/aacp":
        // Renderers list audio/mp4, not raw AAC MIME types
        return "audio/mp4";
      case "audio/x-mpeg":
      case "audio/mp2":
      case "audio/mpeg3":
      case "audio/x-mp3":
        // Normalize all MP3 variants
        return "audio/mpeg";
      case "audio/x-m4a":
        return "audio/mp4";
      case "audio/ogg":
      case "audio/vorbis":
      case "application/ogg":
        // OGG: no standard DLNA MIME, best effort
        return "audio/ogg";
      default:
        return content;
    }
  }

  // Create DIDL-Lite metadata
  @NonNull
  private String getMetaData() {
    return "<DIDL-Lite " +
      "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
      "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
      "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
      "<item id=\"" + Request.escapeXml(radio.getId()) + "\" parentID=\"0\" restricted=\"1\">" +
      "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
      "<dc:title>" + Request.escapeXml(radio.getName()) + "</dc:title>" +
      "<upnp:artist>" + information + "</upnp:artist>" +
      "<upnp:album>" + context.getString(R.string.live_streaming) + "</upnp:album>" +
      "<upnp:albumArtURI>" + logoUri + "</upnp:albumArtURI>" +
      "<res duration=\"0:00:00\" protocolInfo=\"" + PROTOCOL_INFO_HEADER + getDidlMime() + ":" + PROTOCOL_INFO_TAIL + "\">" + radioUri + "</res>" +
      "</item>" +
      "</DIDL-Lite>";
  }
}