/*
 * Copyright (c) 2018-2026. Stephane Treuchot
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

package com.watea.radio_upnp.service;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.upnp.Action;
import com.watea.radio_upnp.upnp.Device;
import com.watea.radio_upnp.upnp.Request;
import com.watea.radio_upnp.upnp.RequestController;
import com.watea.radio_upnp.upnp.Service;

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
  private final RequestController requestController;
  @Nullable
  private final Service connectionManager;
  @Nullable
  private final Service avTransportService;
  @Nullable
  private final Service renderingControl;
  @NonNull
  private final String information; // Not final in further use
  private int currentVolume;
  @NonNull
  private String instanceId = "0";

  public UpnpSessionDevice(
    @NonNull Context context,
    boolean isPcm,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull Consumer<Radio> onPlayCallback,
    @NonNull StreamServer streamServer,
    @NonNull RequestController requestController,
    @NonNull Device device) {
    super(context, isPcm ? Mode.PCM : Mode.MUTE, listener, radio, onPlayCallback, streamServer);
    this.requestController = requestController;
    information = this.context.getString(R.string.app_name);
    // Only devices with AVTransport are processed
    avTransportService = device.getShortService(AV_TRANSPORT_SERVICE_ID);
    // Those services are mandatory in UPnP standard
    connectionManager = device.getShortService(CONNECTION_MANAGER_ID);
    renderingControl = device.getShortService(RENDERING_CONTROL_ID);
  }

  @NonNull
  public static String getDlnaTail(@NonNull String mime) {
    String result;
    switch (mime) {
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
      case PCM_MIME:
        // audio/wav (RIFF, little-endian) has no DLNA profile — LPCM mandates raw audio/L16 big-endian
      case "audio/flac":
      case "audio/x-flac":
        // No standard DLNA profile for FLAC
      default:
        // OGG, unknown — no DLNA profile
        result = "";
    }
    return result + PROTOCOL_INFO_TAIL;
  }

  @Override
  public synchronized void adjustVolume(int direction) {
    // Always resync: no UPnP eventing available, volume may have changed externally (e.g. remote control)
    scheduleActionGetVolume(direction);
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

  @Override
  protected boolean prepare() {
    // super.prepare() blocks until the upstream HTTP connection is established.
    if (super.prepare()) {
      scheduleActionGetProtocolInfo();
      scheduleActionPrepareForConnection();
      scheduleActionSetAvTransportUri();
      scheduleActionPlay();
      return true;
    }
    return false;
  }

  // Not implemented
  @Override
  protected void setVolume(float volume) {
  }

  private void scheduleMandatoryAction(@Nullable Service service, @NonNull String name, @NonNull Function<Action, Request> function) {
    scheduleAction(service, name, function, true);
  }

  private void scheduleOptionalAction(@Nullable Service service, @NonNull String name, @NonNull Function<Action, Request> function) {
    scheduleAction(service, name, function, false);
  }

  private void scheduleAction(
    @Nullable Service service,
    @NonNull String name,
    @NonNull Function<Action, Request> function,
    boolean isMandatory) {
    final Action action = (service == null) ? null : service.getAction(name);
    if (action == null) {
      if (isMandatory) {
        // Shall not happen
        Log.e(LOG_TAG, "scheduleAction: mandatory UPnP action not found");
        onState(State.ERROR);
      }
      return;
    }
    requestController.schedule(function.apply(action));
  }

  private void scheduleActionGetProtocolInfo() {
    scheduleOptionalAction(
      connectionManager, ACTION_GET_PROTOCOL_INFO,
      action -> new Request(action) {
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
        }
        // Note: failure is not taken into account
      });
  }

  private void scheduleActionPlay() {
    scheduleMandatoryAction(
      avTransportService, ACTION_PLAY,
      action -> new Request(action, instanceId) {
        @Override
        protected void onSuccess() {
          onState(State.PLAYING);
        }

        @Override
        protected void onFailure() {
          Log.d(LOG_TAG, "scheduleActionPlay: error");
          onState(State.ERROR);
        }
      }
        .addArgument("Speed", "1"));
  }

  private void scheduleActionStop() {
    scheduleMandatoryAction(
      avTransportService, ACTION_STOP,
      action -> new Request(action, instanceId) {
        @Override
        protected void onFailure() {
          Log.d(LOG_TAG, "scheduleActionStop: error");
          onState(State.ERROR);
        }
      });
  }

  private void scheduleActionPrepareForConnection() {
    scheduleOptionalAction(
      connectionManager, ACTION_PREPARE_FOR_CONNECTION,
      action -> new Request(action) {
        @Override
        protected void onSuccess() {
          final String aVTransportID = getResponse("AVTransportID");
          if (aVTransportID == null) {
            Log.e(LOG_TAG, "Unable to find instanceId");
          } else {
            instanceId = aVTransportID;
          }
        }
        // Note: failure is not taken into account
      }
        .addArgument("RemoteProtocolInfo", PROTOCOL_INFO_HEADER + "*:" + PROTOCOL_INFO_TAIL)
        .addArgument("PeerConnectionManager", "")
        .addArgument("PeerConnectionID", "-1")
        .addArgument("Direction", "Input"));
  }

  private void scheduleActionGetVolume(int direction) {
    scheduleMandatoryAction(
      renderingControl, ACTION_GET_VOLUME,
      action -> new Request(action, instanceId) {
        @Override
        protected void onSuccess() {
          final String response = getResponse("CurrentVolume");
          if (response != null) {
            try {
              final int volume = Integer.parseInt(response);
              synchronized (UpnpSessionDevice.this) {
                currentVolume = volume;
                scheduleActionSetVolume(direction);
              }
            } catch (Exception exception) {
              Log.e(LOG_TAG, "Unable to set volume", exception);
            }
          }
        }
        // Note: failure is not taken into account
      }
        .addArgument(INPUT_CHANNEL, INPUT_MASTER));
  }

  // Caller must hold the monitor (see call sites) since this is a compound read-modify-write
  private void scheduleActionSetVolume(int direction) {
    switch (direction) {
      case AudioManager.ADJUST_LOWER:
        currentVolume = Math.max(0, currentVolume - 1);
        break;
      case AudioManager.ADJUST_RAISE:
        currentVolume++;
        break;
      default:
        // Nothing to do
        return;
    }
    Log.d(LOG_TAG, "Volume required: " + currentVolume);
    scheduleMandatoryAction(
      renderingControl, ACTION_SET_VOLUME,
      action -> new Request(action, instanceId) {
        @Override
        protected void onSuccess() {
          Log.d(LOG_TAG, "Volume set");
        }

        @Override
        protected void onFailure() {
          Log.d(LOG_TAG, "Volume set failed");
        }
      }
        .addArgument(INPUT_CHANNEL, INPUT_MASTER)
        .addArgument(INPUT_DESIRED_VOLUME, Integer.toString(currentVolume)));
  }

  private void scheduleActionSetAvTransportUri() {
    scheduleMandatoryAction(
      avTransportService, ACTION_SET_AV_TRANSPORT_URI,
      action -> new Request(action, instanceId) {
        @Override
        protected void onSuccess() {
          onState(State.BUFFERING);
        }

        @Override
        protected void onFailure() {
          Log.d(LOG_TAG, "scheduleActionSetAvTransportUri: error");
          onState(State.ERROR);
          // Release other UPnP actions on this device
          requestController.release(action.getDevice());
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

  // Creates DIDL-Lite metadata
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