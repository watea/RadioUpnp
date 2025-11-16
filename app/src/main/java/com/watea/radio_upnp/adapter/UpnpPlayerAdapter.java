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
import com.watea.radio_upnp.service.ContentProvider;
import com.watea.radio_upnp.upnp.Action;
import com.watea.radio_upnp.upnp.ActionController;
import com.watea.radio_upnp.upnp.Device;
import com.watea.radio_upnp.upnp.Service;
import com.watea.radio_upnp.upnp.UpnpAction;
import com.watea.radio_upnp.upnp.Watchdog;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public class UpnpPlayerAdapter extends PlayerAdapter {
  private static final String LOG_TAG = UpnpPlayerAdapter.class.getSimpleName();
  private static final String AV_TRANSPORT_SERVICE_ID = "AVTransport";
  private static final String RENDERING_CONTROL_ID = "RenderingControl";
  private static final String CONNECTION_MANAGER_ID = "ConnectionManager";
  private static final String PROTOCOL_INFO_HEADER = "http-get:*:";
  private static final String PROTOCOL_INFO_ALL = ":*";
  private static final String DEFAULT_PROTOCOL_INFO =
    PROTOCOL_INFO_HEADER + "*" + PROTOCOL_INFO_ALL;
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
  private final Device device;
  @NonNull
  private final ActionController actionController;
  @NonNull
  private final ContentProvider contentProvider;
  @Nullable
  private final Uri logoUri;
  @Nullable
  private final Service connectionManager;
  @Nullable
  private final Service avTransportService;
  @Nullable
  private final Service renderingControl;
  @Nullable
  private final Watchdog watchdog;
  @NonNull
  private final String information; // Not final in further use
  private int currentVolume;
  private int volumeDirection = AudioManager.ADJUST_SAME;
  @NonNull
  private String instanceId = "0";

  public UpnpPlayerAdapter(
    @NonNull Context context,
    @NonNull Listener listener,
    @NonNull Radio radio,
    @NonNull String lockKey,
    @NonNull Uri radioUri,
    @Nullable Uri logoUri,
    @NonNull Device device,
    @NonNull ActionController actionController,
    @NonNull ContentProvider contentProvider) {
    super(context, listener, radio, lockKey, radioUri);
    this.device = device;
    this.actionController = actionController;
    this.contentProvider = contentProvider;
    this.logoUri = logoUri;
    information = this.context.getString(R.string.app_name);
    // Only devices with AVTransport are processed
    avTransportService = device.getShortService(AV_TRANSPORT_SERVICE_ID);
    // Those services are mandatory in UPnP standard
    connectionManager = device.getShortService(CONNECTION_MANAGER_ID);
    renderingControl = device.getShortService(RENDERING_CONTROL_ID);
    // Watchdog tests if reader is actually playing
    watchdog = (avTransportService == null) ? null : new Watchdog(this.actionController, avTransportService) {
      @Override
      public void onEvent(@NonNull ReaderState readerState) {
        // Do nothing if paused as event has already been sent
        if (!isPaused) {
          changeAndNotifyState(
            (readerState == Watchdog.ReaderState.PLAYING) ?
              PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_ERROR);
        }
      }
    };
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
  public long getAvailableActions() {
    long actions = super.getAvailableActions();
    switch (state) {
      case PlaybackStateCompat.STATE_PLAYING:
        actions |= PlaybackStateCompat.ACTION_PAUSE;
        break;
      case PlaybackStateCompat.STATE_PAUSED:
      case PlaybackStateCompat.STATE_BUFFERING:
        actions |= PlaybackStateCompat.ACTION_PLAY;
        break;
      default:
        // Nothing else
    }
    return actions;
  }

  // Special handling for MIME type
  @Override
  @NonNull
  public String getContentType() {
    String contentType = contentProvider.getContentType(radio);
    // Default value
    if (contentType == null) {
      contentType = DEFAULT_CONTENT_TYPE;
    }
    // First choice: contentType
    String result = contentProvider.getContentType(device, contentType);
    if (result != null) {
      return result;
    }
    // Second choice: MIME subtype
    final String HEAD_EXP = "[a-z]*/";
    result =
      contentProvider.getContentType(device, HEAD_EXP + contentType.replaceFirst(HEAD_EXP, ""));
    if (result != null) {
      return result;
    }
    // AAC special case
    if (contentType.contains("aac")) {
      result = contentProvider.getContentType(device, AUDIO_CONTENT_TYPE + "mp4");
      if (result != null) {
        return result;
      }
    }
    // Default case
    return contentType;
  }

  @Override
  protected boolean isRemote() {
    return true;
  }

  @Override
  protected void onPrepareFromMediaId() {
    changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
    // First we need to know radio content type
    contentProvider.fetchContentType(radio, () -> {
      if (state == PlaybackStateCompat.STATE_BUFFERING) {
        // Do prepare if action available
        scheduleActionPrepareForConnection();
        // Fetch ProtocolInfo if not available
        if (!contentProvider.hasProtocolInfo(device)) {
          scheduleActionGetProtocolInfo();
        }
        scheduleActionSetAvTransportUri();
        scheduleActionPlay();
      } else {
        // Something went wrong
        changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
      }
    });
  }

  @Override
  protected void onPlay() {
    changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
    scheduleActionSetAvTransportUri();
    scheduleActionPlay();
  }

  @Override
  protected void onPause() {
    scheduleActionStop();
  }

  @Override
  protected void onStop() {
    scheduleActionStop();
  }

  @Override
  protected void onRelease() {
    if (watchdog != null) {
      watchdog.kill();
    }
  }

  // For further use
  public void onNewInformation(@NonNull String ignoredInformation) {
  }

  private void scheduleMandatoryAction(
    @Nullable Action action, @NonNull Function<Action, UpnpAction> function) {
    if (action == null) {
      // Shall not happen
      Log.e(LOG_TAG, "scheduleMandatoryAction: mandatory UPnP action not found");
      changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
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
        // Actual Playing state is tested by Watchdog, so nothing to do in case of success
        @Override
        protected void onFailure() {
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
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
          if (isPaused) {
            changeAndNotifyState(PlaybackStateCompat.STATE_PAUSED);
          }
          super.onSuccess();
        }

        @Override
        protected void onFailure() {
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
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
        .addArgument("RemoteProtocolInfo", DEFAULT_PROTOCOL_INFO)
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
      ((watchdog == null) || (avTransportService == null)) ? null : avTransportService.getAction(ACTION_SET_AV_TRANSPORT_URI),
      action -> new UpnpAction(action, actionController, instanceId) {
        @Override
        protected void onSuccess() {
          changeAndNotifyState(PlaybackStateCompat.STATE_BUFFERING);
          // Now instanceId is known, we launch watchdog
          assert watchdog != null;
          watchdog.start(instanceId);
          super.onSuccess();
        }

        @Override
        protected void onFailure() {
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
          // Release other UPnP actions on this device
          actionController.release(action.getDevice());
          super.onFailure();
        }
      }
        .addArgument("CurrentURI", radioUri.toString())
        .addArgument("CurrentURIMetaData", getMetaData()));
  }

  private void scheduleActionGetProtocolInfo() {
    scheduleMandatoryAction(
      (connectionManager == null) ? null : connectionManager.getAction(ACTION_GET_PROTOCOL_INFO),
      action -> new UpnpAction(action, actionController) {
        @Override
        protected void onSuccess() {
          final String sink = getResponse("Sink");
          if (sink != null) {
            final List<String> protocolInfos = new ArrayList<>();
            for (final String protocolInfo : sink.split(",")) {
              if (UpnpPlayerAdapter.isHandling(protocolInfo)) {
                Log.d(LOG_TAG, "Audio ProtocolInfo: " + protocolInfo);
                protocolInfos.add(protocolInfo);
              }
            }
            contentProvider.putProtocolInfo(action.getDevice(), protocolInfos);
          }
          super.onSuccess();
        }

        @Override
        protected void onFailure() {
          changeAndNotifyState(PlaybackStateCompat.STATE_ERROR);
          super.onFailure();
        }
      });
  }

  @NonNull
  private String moveToSoap(@NonNull String string) {
    return string
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
      .replace("\"", "&quot;")
      .replace("'", "&apos;");
  }

  // Create DIDL-Lite metadata
  @NonNull
  private String getMetaData() {
    return moveToSoap("<DIDL-Lite " +
      "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
      "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
      "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
      "<item id=\"" + radio.getId() + "\" parentID=\"0\" restricted=\"1\">" +
      "<upnp:class>object.item.audioItem.audioBroadcast</upnp:class>" +
      "<dc:title>" + moveToSoap(radio.getName()) + "</dc:title>" +
      "<upnp:artist>" + moveToSoap(information) + "</upnp:artist>" +
      "<upnp:album>" + context.getString(R.string.live_streaming) + "</upnp:album>" +
      "<upnp:albumArtURI>" + logoUri + "</upnp:albumArtURI>" +
      "<res duration=\"0:00:00\" protocolInfo=\"" +
      PROTOCOL_INFO_HEADER + getContentType() + PROTOCOL_INFO_ALL + "\">" + radioUri + "</res>" +
      "</item>" +
      "</DIDL-Lite>");
  }
}