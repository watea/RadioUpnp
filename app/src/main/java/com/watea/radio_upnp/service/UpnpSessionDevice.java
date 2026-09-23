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
import com.watea.radio_upnp.upnp.StateVariable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class UpnpSessionDevice extends RemoteSessionDevice {
  public static final String PCM_MIME = "audio/wav";
  private static final String PCM_MIME_ALIAS = "audio/x-wav"; // Historical alias for audio/wav, still advertised by some renderers (e.g. Samsung TVs)
  private static final String L16_MIME = "audio/L16";
  private static final int PCM_FORMAT_UNKNOWN = -1;
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
  private static final String STATE_VARIABLE_VOLUME = "Volume";
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
  // Renderer's own Volume range (RenderingControl SCPD allowedValueRange), not assumed to be 0-100
  private final int volumeMinimum;
  private final int volumeMaximum;
  private final int volumeStep;
  @NonNull
  private final Set<String> sinkProtocolInfos = new HashSet<>();
  @NonNull
  private String instanceId = "0";
  private boolean isLaunched = false;

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
    final StateVariable volumeRange = (renderingControl == null) ? null : renderingControl.getStateVariable(STATE_VARIABLE_VOLUME);
    final boolean hasVolumeRange = (volumeRange != null) && volumeRange.hasRange();
    volumeMinimum = hasVolumeRange ? volumeRange.getMinimum() : 0;
    volumeMaximum = hasVolumeRange ? volumeRange.getMaximum() : DEVICE_MAX_VOLUME;
    volumeStep = Math.max(1, (int) Math.round((volumeMaximum - volumeMinimum) * VOLUME_STEP_RATIO));
  }

  @NonNull
  public static String getDlnaTail(@NonNull String mime) {
    String result;
    if (mime.startsWith(L16_MIME)) {
      result = "DLNA.ORG_PN=LPCM;";
    } else {
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
    }
    return result + PROTOCOL_INFO_TAIL;
  }

  public static boolean isRawPcm(@NonNull String mime) {
    return !PCM_MIME.equals(mime);
  }

  @NonNull
  public static String normalize(@NonNull String mime) {
    switch (mime) {
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
        return Radio.DEFAULT_MIME;
      case "audio/x-m4a":
        return "audio/mp4";
      case "audio/ogg":
      case "audio/vorbis":
      case "application/ogg":
        // OGG: no standard DLNA MIME, best effort
        return "audio/ogg";
      default:
        return mime;
    }
  }

  @Override
  public void adjustVolume(int direction) {
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
  public void onPcmFormat(int sampleRate, int channelCount) {
    launchPlay(sampleRate, channelCount);
  }

  @Override
  protected boolean prepare() {
    // super.prepare() blocks until the upstream HTTP connection is established
    if (super.prepare()) {
      scheduleActionGetProtocolInfo();
      scheduleActionPrepareForConnection();
      if (mode != Mode.PCM) {
        launchPlay(PCM_FORMAT_UNKNOWN, PCM_FORMAT_UNKNOWN);
      }
      return true;
    }
    return false;
  }

  // Not implemented
  @Override
  protected void setVolume(float volume) {
  }

  private void launchPlay(int sampleRate, int channelCount) {
    if (isLaunched) {
      return;
    }
    isLaunched = true;
    scheduleActionSetAvTransportUri(sampleRate, channelCount);
    scheduleActionPlay();
    scheduleActionGetVolume(AudioManager.ADJUST_SAME);
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
            // "http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM" => "audio/L16;rate=44100;channels=2"
            for (final String entry : sink.split(",")) {
              Log.i(LOG_TAG, "ProtocolInfo: " + entry);
              final String[] fields = entry.split(":", 4);
              if (fields.length >= 3) {
                sinkProtocolInfos.add(fields[2].trim());
              }
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
              scheduleActionSetVolume(Integer.parseInt(response), direction);
            } catch (Exception exception) {
              Log.e(LOG_TAG, "Unable to set volume", exception);
            }
          }
        }
        // Note: failure is not taken into account
      }
        .addArgument(INPUT_CHANNEL, INPUT_MASTER));
  }

  private void scheduleActionSetVolume(int currentVolume, int direction) {
    final int desiredVolume;
    switch (direction) {
      case AudioManager.ADJUST_LOWER:
        desiredVolume = Math.max(volumeMinimum, currentVolume - volumeStep);
        break;
      case AudioManager.ADJUST_RAISE:
        desiredVolume = Math.min(volumeMaximum, currentVolume + volumeStep);
        break;
      default:
        listener.onVolumeChanged(normalizeVolume(currentVolume), lockKey);
        return;
    }
    Log.d(LOG_TAG, "Volume required: " + desiredVolume);
    scheduleMandatoryAction(
      renderingControl, ACTION_SET_VOLUME,
      action -> new Request(action, instanceId) {
        @Override
        protected void onSuccess() {
          Log.d(LOG_TAG, "Volume set");
          listener.onVolumeChanged(normalizeVolume(desiredVolume), lockKey);
        }

        @Override
        protected void onFailure() {
          Log.d(LOG_TAG, "Volume set failed");
        }
      }
        .addArgument(INPUT_CHANNEL, INPUT_MASTER)
        .addArgument(INPUT_DESIRED_VOLUME, Integer.toString(desiredVolume)));
  }

  // Renderer's native Volume range may not be 0-100 (e.g. some devices express it in dB);
  // the system-facing device volume is always reported on the common RemoteSessionDevice scale
  private int normalizeVolume(int nativeVolume) {
    return (int) Math.round((nativeVolume - volumeMinimum) * DEVICE_MAX_VOLUME / (double) (volumeMaximum - volumeMinimum));
  }

  private void scheduleActionSetAvTransportUri(int sampleRate, int channelCount) {
    scheduleMandatoryAction(
      avTransportService, ACTION_SET_AV_TRANSPORT_URI,
      action -> new Request(action, instanceId) {
        @Override
        public void run() {
          final String mime = (mode == Mode.PCM) ?
            resolvePcmFormat(sampleRate, channelCount) :
            normalize((connectionSet == null) ? Radio.DEFAULT_MIME : connectionSet.getContent());
          if (mime == null) {
            Log.d(LOG_TAG, "scheduleActionSetAvTransportUri: no format compatible with this renderer");
            onFailure();
            return;
          }
          addArgument("CurrentURI", radioUri.toString());
          addArgument("CurrentURIMetaData", getMetaData(mime));
          super.run();
        }

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
      });
  }

  @Nullable
  private String resolvePcmFormat(int sampleRate, int channelCount) {
    if (sinkProtocolInfos.isEmpty()
      || sinkProtocolInfos.contains(PCM_MIME)
      || sinkProtocolInfos.contains(PCM_MIME_ALIAS)) {
      return onPcmMime(PCM_MIME);
    }
    if ((sampleRate == PCM_FORMAT_UNKNOWN) || (channelCount == PCM_FORMAT_UNKNOWN)) {
      // Shall not happen
      Log.e(LOG_TAG, "resolvePcmFormat: PCM format unexpectedly unknown");
      return null;
    }
    final String l16Mime = L16_MIME + ";rate=" + sampleRate + ";channels=" + channelCount;
    // A bare/wildcard audio/L16 entry (no rate/channels) means "any DLNA-standard PCM rate" —
    // still declare and serve the exact decoded rate/channels, just relax the match itself
    if (!sinkProtocolInfos.contains(L16_MIME) && !sinkProtocolInfos.contains(l16Mime)) {
      Log.d(LOG_TAG, "resolvePcmFormat: no format compatible with this renderer's Sink");
      return null;
    }
    return onPcmMime(l16Mime);
  }

  // Creates DIDL-Lite metadata
  @NonNull
  private String getMetaData(@NonNull String mime) {
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
      "<res duration=\"0:00:00\" protocolInfo=\"" + PROTOCOL_INFO_HEADER + mime + ":" + getDlnaTail(mime) + "\">" + Request.escapeXml(radioUri.toString()) + "</res>" +
      "</item>" +
      "</DIDL-Lite>";
  }
}