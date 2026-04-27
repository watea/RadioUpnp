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
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.upnp.Action;
import com.watea.radio_upnp.upnp.ActionController;
import com.watea.radio_upnp.upnp.Device;
import com.watea.radio_upnp.upnp.Request;
import com.watea.radio_upnp.upnp.Service;
import com.watea.radio_upnp.upnp.UpnpAction;

import java.util.function.Consumer;
import java.util.function.Function;

public class UpnpSessionDevice extends RemoteSessionDevice {
  public static final String PCM_MIME = "audio/wav";
  private static final String PROTOCOL_INFO_TAIL = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000";
  private static final String LOG_TAG = UpnpSessionDevice.class.getSimpleName();
  private static final String AV_TRANSPORT_SERVICE_ID = "AVTransport";
  private static final String RENDERING_CONTROL_ID = "RenderingControl";
  private static final String CONNECTION_MANAGER_ID = "ConnectionManager";
  private static final String PROTOCOL_INFO_HEADER = "http-get:*:";
  private static final String ACTION_PREPARE_FOR_CONNECTION = "PrepareForConnection";
  private static final String ACTION_SET_AV_TRANSPORT_URI = "SetAVTransportURI";
  private static final String ACTION_GET_PROTOCOL_INFO = "GetProtocolInfo";
  private static final String ACTION_PLAY = "Play";
  private static final String ACTION_STOP = "Stop";
  private static final String ACTION_SET_VOLUME = "SetVolume";
  private static final String ACTION_GET_VOLUME = "GetVolume";
  private static final String INPUT_DESIRED_VOLUME = "DesiredVolume";
  private static final String INPUT_CHANNEL = "Channel";
  private static final String INPUT_MASTER = "Master";
  @NonNull
  private final ActionController actionController;
  @Nullable
  private final Service connectionManager;
  @Nullable
  private final Service avTransportService;
  @Nullable
  private final Service renderingControl;
  @NonNull
  private final String information; // Not final in further use
  @NonNull
  private final Consumer<Radio> onPlayCallback;
  private int currentVolume;
  private int volumeDirection = AudioManager.ADJUST_SAME;
  @NonNull
  private String instanceId = "0";

  public UpnpSessionDevice(
    @NonNull Context context,
    boolean isPcm,
    @NonNull ServerCallback serverCallback,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey,
    @NonNull Device device,
    @NonNull ActionController actionController,
    @NonNull Consumer<Radio> onPlayCallback) {
    super(context, isPcm ? Mode.PCM : Mode.MUTE, serverCallback, listener, radio, lockKey);
    this.actionController = actionController;
    this.onPlayCallback = onPlayCallback;
    information = this.context.getString(R.string.app_name);
    // Only devices with AVTransport are processed
    avTransportService = device.getShortService(AV_TRANSPORT_SERVICE_ID);
    // Those services are mandatory in UPnP standard
    connectionManager = device.getShortService(CONNECTION_MANAGER_ID);
    renderingControl = device.getShortService(RENDERING_CONTROL_ID);
  }

  public static String getDlnaTail(@NonNull String mime) {
    String result;
    switch (mime) {
      case PCM_MIME:
        result = "DLNA.ORG_PN=LPCM;";
        break;
      case "audio/mpeg":
        result = "DLNA.ORG_PN=MP3;";
        break;
      case "audio/aac":
      case "audio/x-aac":
      case "audio/aacp":
        result = "DLNA.ORG_PN=AAC_ADTS;";
        break;
      case "audio/mp4":
      case "audio/x-m4a":
        result = "DLNA.ORG_PN=AAC_ISO;";
        break;
      case "audio/flac":
      case "audio/x-flac":
        // No standard DLNA profile for FLAC
      default:
        // OGG, unknown — no DLNA profile
        result = "";
    }
    return result + PROTOCOL_INFO_TAIL;
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
        upnpAction.ownThreadExecute();
      }
    }
  }

  @Override
  public boolean prepare() {
    // super.prepare() blocks until the upstream HTTP connection is established.
    // By the time it returns, the session may have been released (e.g. by a
    // connect watchdog). Guard against scheduling stale UPnP actions.
    if (super.prepare() && !isReleased) {
      scheduleActionGetProtocolInfo();
      scheduleActionPrepareForConnection();
      scheduleActionSetAvTransportUri();
      scheduleActionPlay();
      return true;
    }
    return false;
  }

  @Override
  public void play() {
    onPlayCallback.accept(radio);
  }

  @Override
  public void pause() {
    // Pause immediately
    onState(PlaybackStateCompat.STATE_PAUSED);
    stop();
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

  private void scheduleActionGetProtocolInfo() {
    scheduleOptionalAction(
      (connectionManager == null) ? null : connectionManager.getAction(ACTION_GET_PROTOCOL_INFO),
      action -> new UpnpAction(action, actionController) {
        @Override
        protected void onSuccess() {
          final String sink = getResponse("Sink");
          if (sink == null) {
            Log.i(LOG_TAG, "ProtocolInfo: null");
          } else {
            for (final String entry : sink.split(",")) {
              Log.i(LOG_TAG, "ProtocolInfo: " + entry);
            }
          }
          super.onSuccess();
        }
        // Note: failure is not taken into account
      });
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
        .execute();
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
  private String getDidlDlnaTail() {
    // Default is PCM
    String content = PCM_MIME;
    String mime = PCM_MIME;
    if (mode != Mode.PCM) {
      // Relay
      content = (connectionSet == null) ? Radio.DEFAULT_MIME : connectionSet.getContent();
      switch (content) {
        case "audio/aac":
        case "audio/x-aac":
        case "audio/aacp":
          // Renderers list audio/mp4, not raw AAC MIME types
          mime = "audio/mp4";
          break;
        case "audio/x-mpeg":
        case "audio/mp2":
        case "audio/mpeg3":
        case "audio/x-mp3":
          // Normalize all MP3 variants
          mime = "audio/mpeg";
          break;
        case "audio/x-m4a":
          mime = "audio/mp4";
          break;
        case "audio/ogg":
        case "audio/vorbis":
        case "application/ogg":
          // OGG: no standard DLNA MIME, best effort
          mime = "audio/ogg";
          break;
        default:
          mime = content;
      }
    }
    return mime + ":" + getDlnaTail(content);
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
      "<upnp:artist>" + Request.escapeXml(information) + "</upnp:artist>" +
      "<upnp:album>" + context.getString(R.string.live_streaming) + "</upnp:album>" +
      "<upnp:albumArtURI>" + Request.escapeXml(logoUri.toString()) + "</upnp:albumArtURI>" +
      "<res duration=\"0:00:00\" protocolInfo=\"" + PROTOCOL_INFO_HEADER + getDidlDlnaTail() + "\">" + Request.escapeXml(radioUri.toString()) + "</res>" +
      "</item>" +
      "</DIDL-Lite>";
  }
}