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

package com.watea.radio_upnp.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SimpleAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;
import com.watea.radio_upnp.service.RadioService;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;

public class PlayerController {
  private static final String LOG_TAG = PlayerController.class.getName();
  private static final String DATE = "date";
  private static final String INFORMATION = "information";
  @NonNull
  private final MainActivity mainActivity;
  private final List<Map<String, String>> playInformations = new Vector<>();
  @NonNull
  private final SimpleAdapter playlistAdapter;
  // <HMI assets
  @NonNull
  private final ImageButton playImageButton;
  @NonNull
  private final ImageButton preferredImageButton;
  @NonNull
  private final ProgressBar progressBar;
  @NonNull
  private final ImageView albumArtImageView;
  @NonNull
  private final LinearLayout playedRadioLinearLayout;
  @NonNull
  private final TextView playedRadioNameTextView;
  @NonNull
  private final TextView playedRadioInformationTextView;
  @NonNull
  private final TextView playedRadioRateTextView;
  @NonNull
  private final AlertDialog playLongPressAlertDialog;
  @NonNull
  private final AlertDialog informationPressAlertDialog;
  @NonNull
  private final AlertDialog playlistAlertDialog;
  @NonNull
  private final MediaBrowserCompat mediaBrowser;
  // />
  private int informationCount = 0;
  @Nullable
  private RadioLibrary radioLibrary = null;
  private final RadioLibrary.Listener radioLibraryListener = new RadioLibrary.Listener() {
    @Override
    public void onPreferredChange(@NonNull Radio radio) {
      if (radioLibrary.isCurrentRadio(radio)) {
        setPreferredButton(radio.isPreferred());
      }
    }

    // Manage radio description
    @Override
    public void onNewCurrentRadio(@Nullable Radio radio) {
      final boolean isVisible = (radio != null);
      albumArtImageView.setVisibility(MainActivityFragment.getVisibleFrom(isVisible));
      playedRadioLinearLayout.setVisibility(MainActivityFragment.getVisibleFrom(isVisible));
      if (isVisible) {
        playedRadioNameTextView.setText(radio.getName());
        albumArtImageView.setImageBitmap(MainActivity.createScaledBitmap(radio.getIcon()));
        setPreferredButton(radio.isPreferred());
      }
    }
  };
  private boolean gotItPlayLongPress;
  private boolean gotItInformationPress;
  @Nullable
  private MediaControllerCompat mediaController = null;
  // Callback from media control
  private final MediaControllerCompat.Callback mediaControllerCallback =
    new MediaControllerCompat.Callback() {
      // This might happen if the RadioService is killed while the Activity is in the
      // foreground and onStart() has been called (but not onStop())
      @Override
      public void onSessionDestroyed() {
        onPlaybackStateChanged(new PlaybackStateCompat.Builder()
          .setState(PlaybackStateCompat.STATE_ERROR, 0, 1.0f, SystemClock.elapsedRealtime())
          .build());
        mediaBrowser.disconnect();
        Log.d(LOG_TAG, "onSessionDestroyed: RadioService is dead!!!");
      }

      // Manage play button
      @SuppressLint("SwitchIntDef")
      @Override
      public void onPlaybackStateChanged(@Nullable final PlaybackStateCompat state) {
        final int intState = getState(state);
        final Bundle bundle = mediaController.getExtras();
        final boolean isUpnp =
          (bundle != null) && bundle.containsKey(mainActivity.getString(R.string.key_upnp_device));
        Log.d(LOG_TAG, "onPlaybackStateChanged: " + intState);
        // Default
        playImageButton.setImageResource(R.drawable.ic_play_arrow_white_24dp);
        playImageButton.setTag(PlaybackStateCompat.STATE_PLAYING);
        // Play button stores state to reach
        switch (intState) {
          case PlaybackStateCompat.STATE_PLAYING:
            // UPnP device doesn't support PAUSE but STOP
            playImageButton.setImageResource(
              isUpnp ? R.drawable.ic_stop_white_24dp : R.drawable.ic_pause_white_24dp);
            playImageButton.setTag(
              isUpnp ? PlaybackStateCompat.STATE_STOPPED : PlaybackStateCompat.STATE_PAUSED);
          case PlaybackStateCompat.STATE_PAUSED:
            setFrameVisibility(true, false);
            break;
          case PlaybackStateCompat.STATE_BUFFERING:
          case PlaybackStateCompat.STATE_CONNECTING:
            radioLibrary.setCurrentRadio(mediaController.getMetadata());
            setFrameVisibility(true, true);
            break;
          case PlaybackStateCompat.STATE_NONE:
          case PlaybackStateCompat.STATE_STOPPED:
            radioLibrary.setCurrentRadio(null);
            setFrameVisibility(false, false);
            break;
          default:
            // On error, leave radio data visibility ON, if not UPnP streaming.
            // Important notice: this is for user convenience only.
            // Display state is not saved if the context is disposed
            // (as it would require a Radio Service safe context,
            // too complex to implement).
            if (isUpnp) {
              radioLibrary.setCurrentRadio(null);
            } else {
              playImageButton.setImageResource(R.drawable.ic_baseline_replay_24dp);
              playImageButton.setTag(PlaybackStateCompat.STATE_REWINDING);
            }
            setFrameVisibility(!isUpnp, false);
            mainActivity.tell(R.string.radio_connection_error);
        }
      }

      // Manage dynamic data
      @Override
      public void onMetadataChanged(@Nullable MediaMetadataCompat mediaMetadata) {
        if ((mediaMetadata != null) && isValid(mediaMetadata)) {
          // Use SubTitle as notification
          final CharSequence information = mediaMetadata.getDescription().getSubtitle();
          playedRadioInformationTextView.setText(information);
          // Rate in extras
          final Bundle extras = mediaController.getExtras();
          final String rate = (extras == null) ?
            null : extras.getString(mainActivity.getString(R.string.key_rate));
          playedRadioRateTextView.setText(
            (rate == null) ? "" : rate + mainActivity.getString(R.string.kbs));
          // Fill playlist
          if (information != null) {
            final DateFormat dateFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            addInformation(
              dateFormat.format(Calendar.getInstance().getTime()),
              information.toString());
          }
        }
      }

      private void setFrameVisibility(boolean isOn, boolean isWaiting) {
        playImageButton.setEnabled(isOn);
        playImageButton.setVisibility(MainActivityFragment.getVisibleFrom(!isWaiting));
        progressBar.setVisibility(MainActivityFragment.getVisibleFrom(isWaiting));
      }

      private int getState(@Nullable final PlaybackStateCompat state) {
        return (state == null) ? PlaybackStateCompat.STATE_NONE : state.getState();
      }
    };
  // MediaController from the MediaBrowser when it has successfully connected
  private final MediaBrowserCompat.ConnectionCallback mediaBrowserConnectionCallback =
    new MediaBrowserCompat.ConnectionCallback() {
      @Override
      public void onConnected() {
        // Get a MediaController for the MediaSession
        mediaController = new MediaControllerCompat(mainActivity, mediaBrowser.getSessionToken());
        MediaControllerCompat.setMediaController(mainActivity, mediaController);
        // Link to the callback controller
        mediaController.registerCallback(mediaControllerCallback);
        // Sync existing MediaSession state with UI
        final MediaMetadataCompat mediaMetadataCompat = mediaController.getMetadata();
        mediaControllerCallback.onPlaybackStateChanged(mediaController.getPlaybackState());
        if ((mediaMetadataCompat != null) && isValid(mediaMetadataCompat)) {
          mediaControllerCallback.onMetadataChanged(mediaMetadataCompat);
          radioLibrary.setCurrentRadio(mediaMetadataCompat);
        }
        // Nota: no mediaBrowser.subscribe here needed
      }

      @Override
      public void onConnectionSuspended() {
        if (mediaController != null) {
          radioLibrary.setCurrentRadio(null);
          mediaController.unregisterCallback(mediaControllerCallback);
        }
      }

      @Override
      public void onConnectionFailed() {
        Log.i(LOG_TAG, "Connection to RadioService failed");
      }
    };

  public PlayerController(@NonNull MainActivity mainActivity, @NonNull View view) {
    this.mainActivity = mainActivity;
    // Shared preferences
    final SharedPreferences sharedPreferences = mainActivity.getPreferences(Context.MODE_PRIVATE);
    gotItPlayLongPress = sharedPreferences.getBoolean(
      mainActivity.getString(R.string.key_play_long_press_got_it), false);
    gotItInformationPress = sharedPreferences.getBoolean(
      mainActivity.getString(R.string.key_information_press_got_it), false);
    // Build alert dialogs
    playLongPressAlertDialog = new AlertDialog.Builder(mainActivity, R.style.AlertDialogStyle)
      .setMessage(R.string.play_long_press)
      .setPositiveButton(R.string.action_got_it, (dialogInterface, i) -> gotItPlayLongPress = true)
      .create();
    informationPressAlertDialog = new AlertDialog.Builder(mainActivity, R.style.AlertDialogStyle)
      .setMessage(R.string.information_press)
      .setPositiveButton(
        R.string.action_got_it, (dialogInterface, i) -> gotItInformationPress = true)
      .create();
    playlistAdapter = new SimpleAdapter(
      mainActivity,
      playInformations,
      R.layout.row_playlist,
      new String[]{DATE, INFORMATION},
      new int[]{R.id.row_playlist_date_text_view, R.id.row_playlist_information_text_view});
    playlistAlertDialog = new AlertDialog.Builder(mainActivity, R.style.AlertDialogStyle)
      .setAdapter(playlistAdapter, null)
      .create();
    // Create view
    albumArtImageView = view.findViewById(R.id.album_art_image_view);
    albumArtImageView.setOnClickListener(v -> playlistAlertDialog.show());
    playedRadioLinearLayout = view.findViewById(R.id.played_radio_linear_layout);
    playedRadioNameTextView = view.findViewById(R.id.played_radio_name_text_view);
    playedRadioNameTextView.setSelected(true); // For scrolling
    playedRadioInformationTextView = view.findViewById(R.id.played_radio_information_text_view);
    playedRadioInformationTextView.setSelected(true); // For scrolling
    playedRadioRateTextView = view.findViewById(R.id.played_radio_rate_text_view);
    progressBar = view.findViewById(R.id.progress_bar);
    playImageButton = view.findViewById(R.id.play_image_button);
    playImageButton.setOnClickListener(v -> {
      // Should not happen
      if (mediaController == null) {
        this.mainActivity.tell(R.string.radio_connection_waiting);
      } else {
        // Tag on button has stored state to reach
        switch ((int) playImageButton.getTag()) {
          case PlaybackStateCompat.STATE_PLAYING:
            mediaController.getTransportControls().play();
            break;
          case PlaybackStateCompat.STATE_PAUSED:
            mediaController.getTransportControls().pause();
            if (!gotItPlayLongPress) {
              playLongPressAlertDialog.show();
            }
            break;
          case PlaybackStateCompat.STATE_STOPPED:
            mediaController.getTransportControls().stop();
            break;
          case PlaybackStateCompat.STATE_REWINDING:
            mediaController.getTransportControls().rewind();
            break;
          default:
            // Should not happen
            Log.i(LOG_TAG, "Internal failure, no action to perform on play button");
        }
      }
    });
    playImageButton.setOnLongClickListener(playImageButtonView -> {
      // Should not happen
      if (mediaController == null) {
        mainActivity.tell(R.string.radio_connection_waiting);
      } else {
        mediaController.getTransportControls().stop();
      }
      return true;
    });
    preferredImageButton = view.findViewById(R.id.preferred_image_button);
    preferredImageButton.setOnClickListener(v -> {
      final Radio radio = getCurrentRadio();
      if (radio == null) {
        // Should not happen
        Log.i(LOG_TAG, "Internal failure, radio is null");
      } else {
        assert radioLibrary != null;
        radioLibrary.setPreferred(radio.getId(), !radio.isPreferred());
      }
    });
    // Create MediaBrowserServiceCompat, launch RadioService
    mediaBrowser = new MediaBrowserCompat(
      mainActivity,
      new ComponentName(mainActivity, RadioService.class),
      mediaBrowserConnectionCallback,
      null);
  }

  // Must be called on activity resume
  public void onActivityResume(@NonNull RadioLibrary radioLibrary) {
    // Reset start state
    radioLibraryListener.onNewCurrentRadio(null);
    // Connect
    (this.radioLibrary = radioLibrary).addListener(radioLibraryListener);
    mediaBrowser.connect();
  }

  // Must be called on activity pause
  // Handle services
  public void onActivityPause() {
    // Clear radioLibrary
    assert radioLibrary != null;
    radioLibrary.removeListener(radioLibraryListener);
    // Shared preferences
    mainActivity
      .getPreferences(Context.MODE_PRIVATE)
      .edit()
      .putBoolean(mainActivity.getString(R.string.key_play_long_press_got_it), gotItPlayLongPress)
      .putBoolean(
        mainActivity.getString(R.string.key_information_press_got_it), gotItInformationPress)
      .apply();
    // Disconnect mediaBrowser
    mediaBrowser.disconnect();
    // Forced suspended connection
    mediaBrowserConnectionCallback.onConnectionSuspended();
  }

  // radio == null for current, do nothing if no current
  public void startReading(@Nullable Radio radio, @Nullable String upnpDeviceIdentity) {
    // Should not happen
    if (mediaController == null) {
      mainActivity.tell(R.string.radio_connection_waiting);
      return;
    }
    if (radio == null) {
      radio = getCurrentRadio();
      // Robustness, shall not happen
      if (radio == null) {
        return;
      }
    }
    final Bundle bundle = new Bundle();
    if (upnpDeviceIdentity != null) {
      bundle.putString(mainActivity.getString(R.string.key_upnp_device), upnpDeviceIdentity);
    }
    mediaController.getTransportControls().prepareFromMediaId(radio.getId().toString(), bundle);
    // Information are cleared
    playInformations.clear();
    insertInformation("", mainActivity.getString(R.string.no_data));
  }

  @Nullable
  private Radio getCurrentRadio() {
    assert radioLibrary != null;
    return (mediaController == null) ? null : radioLibrary.getCurrentRadio();
  }

  // Validity check: information must come from package
  private boolean isValid(@NonNull MediaMetadataCompat mediaMetadataCompat) {
    return mainActivity.getPackageName().equals(
      mediaMetadataCompat.getString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI));
  }

  private void addInformation(@NonNull String date, @NonNull String information) {
    // No empty data
    if (information.isEmpty()) {
      return;
    }
    // Reset default if any
    if ((playInformations.size() == 1) &&
      mainActivity.getString(R.string.no_data).equals(playInformations.get(0).get(INFORMATION))) {
      playInformations.clear();
    }
    // Insert if new
    if (playInformations.isEmpty() ||
      !information.equals(playInformations.get(playInformations.size() - 1).get(INFORMATION))) {
      insertInformation(date, information);
      // User help for fist valid information after a few time
      if (!(informationPressAlertDialog.isShowing() || gotItInformationPress) &&
        (informationCount++ > 4)) {
        informationPressAlertDialog.show();
      }
    }
  }

  private void insertInformation(@NonNull String date, @NonNull String information) {
    final Map<String, String> informationMap = new Hashtable<>();
    informationMap.put(DATE, date);
    informationMap.put(INFORMATION, information);
    playInformations.add(informationMap);
    playlistAdapter.notifyDataSetChanged();
  }

  private void setPreferredButton(boolean isPreferred) {
    preferredImageButton.setImageResource(
      isPreferred ? R.drawable.ic_star_white_30dp : R.drawable.ic_star_border_white_30dp);
  }
}