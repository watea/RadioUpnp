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
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.DlnaDevicesAdapter;
import com.watea.radio_upnp.adapter.RadiosAdapter;
import com.watea.radio_upnp.model.DlnaDevice;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioSQLContract;
import com.watea.radio_upnp.service.NetworkTester;
import com.watea.radio_upnp.service.RadioService;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.registry.RegistryListener;

import java.util.List;
import java.util.Objects;

import static android.content.Context.BIND_AUTO_CREATE;
import static com.watea.radio_upnp.adapter.UpnpPlayerAdapter.AV_TRANSPORT_SERVICE_ID;

public class MainFragment
  extends MainActivityFragment
  implements
  RadiosAdapter.Listener,
  View.OnClickListener,
  View.OnLongClickListener {
  private static final String LOG_TAG = MainFragment.class.getName();
  private final Handler handler = new Handler();
  // <HMI assets
  private LinearLayout playedRadioDataLinearLayout;
  private FrameLayout playFrameLayout;
  private ImageButton playImageButton;
  private ProgressBar progressBar;
  private ImageView albumArtImageView;
  private TextView playedRadioNameTextView;
  private TextView playedRadioInformationTextView;
  private TextView playedRadioRateTextView;
  private View radiosDefaultView;
  private View dlnaView;
  private RecyclerView dlnaRecyclerView;
  private RecyclerView radiosView;
  private MenuItem preferredMenuItem;
  private MenuItem dlnaMenuItem;
  private AlertDialog dlnaAlertDialog;
  private AlertDialog radioLongPressAlertDialog;
  private AlertDialog playLongPressAlertDialog;
  private AlertDialog dlnaEnableAlertDialog;
  // />
  private boolean isPreferredRadios = false;
  private boolean gotItRadioLongPress;
  private boolean gotItPlayLongPress;
  private boolean gotItDlnaEnable;
  private MediaControllerCompat mediaController = null;
  private boolean isErrorAllowedToTell = false;
  // Callback from media control
  private final MediaControllerCompat.Callback mediaControllerCallback =
    new MediaControllerCompat.Callback() {
      // This might happen if the RadioService is killed while the Activity is in the
      // foreground and onStart() has been called (but not onStop())
      @Override
      public void onSessionDestroyed() {
        onPlaybackStateChanged(new PlaybackStateCompat
          .Builder()
          .setState(PlaybackStateCompat.STATE_ERROR, 0, 1.0f, SystemClock.elapsedRealtime())
          .build());
        Log.d(LOG_TAG, "onSessionDestroyed: RadioService is dead!!!");
      }

      @SuppressLint("SwitchIntDef")
      @Override
      public void onPlaybackStateChanged(@Nullable final PlaybackStateCompat state) {
        // Do nothing if view not defined
        if (isActuallyAdded()) {
          int intState = (state == null) ? PlaybackStateCompat.STATE_NONE : state.getState();
          Log.d(LOG_TAG, "onPlaybackStateChanged: " + intState);
          // Play button stores state to reach
          switch (intState) {
            case PlaybackStateCompat.STATE_PLAYING:
              setFrameVisibility(true, true);
              // DLNA device doesn't support PAUSE but STOP
              boolean isDlna =
                mediaController.getExtras().containsKey(getString(R.string.key_dlna_device));
              playImageButton.setImageResource(
                isDlna ? R.drawable.ic_stop_black_24dp : R.drawable.ic_pause_black_24dp);
              playImageButton.setTag(
                isDlna ? PlaybackStateCompat.STATE_STOPPED : PlaybackStateCompat.STATE_PAUSED);
              break;
            case PlaybackStateCompat.STATE_PAUSED:
              setFrameVisibility(true, true);
              playImageButton.setImageResource(R.drawable.ic_play_arrow_black_24dp);
              playImageButton.setTag(PlaybackStateCompat.STATE_PLAYING);
              break;
            case PlaybackStateCompat.STATE_BUFFERING:
            case PlaybackStateCompat.STATE_CONNECTING:
              setFrameVisibility(true, false);
              break;
            case PlaybackStateCompat.STATE_NONE:
            case PlaybackStateCompat.STATE_STOPPED:
              setFrameVisibility(false, false);
              break;
            default:
              setFrameVisibility(false, false);
              // Tell error, just once per reading session
              if (isErrorAllowedToTell) {
                tell(R.string.radio_connection_error);
                isErrorAllowedToTell = false;
              }
          }
        }
      }

      @Override
      public void onMetadataChanged(final MediaMetadataCompat mediaMetadata) {
        // Do nothing if view not defined or nothing to change
        if (isActuallyAdded() && (mediaMetadata != null)) {
          playedRadioNameTextView.setText(
            mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
          playedRadioInformationTextView.setText(
            mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST));
          // Use WRITER for rate
          String rate = mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_WRITER);
          playedRadioRateTextView.setText((rate == null) ? "" : rate + getString(R.string.kbs));
          albumArtImageView.setImageBitmap(
            Bitmap.createScaledBitmap(
              mediaMetadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART),
              RADIO_ICON_SIZE,
              RADIO_ICON_SIZE,
              false));
        }
      }

      private void setFrameVisibility(boolean isVisible, boolean isPlayVisible) {
        playedRadioDataLinearLayout.setVisibility(getVisibility(isVisible));
        playFrameLayout.setVisibility(getVisibility(isVisible));
        albumArtImageView.setVisibility(getVisibility(isVisible));
        playImageButton.setVisibility(getVisibility(isVisible && isPlayVisible));
        progressBar.setVisibility(getVisibility(isVisible && !isPlayVisible));
      }

      private int getVisibility(boolean isVisible) {
        return isVisible ? View.VISIBLE : View.INVISIBLE;
      }
    };
  private RadiosAdapter radiosAdapter;
  private AndroidUpnpService androidUpnpService = null;
  private MediaBrowserCompat mediaBrowser = null;
  // Forced context has getActivity(), used when getActivity() may be null
  private Context context = null;
  // MediaController from the MediaBrowser when it has successfully connected
  private final MediaBrowserCompat.ConnectionCallback mediaBrowserConnectionCallback =
    new MediaBrowserCompat.ConnectionCallback() {
      @Override
      public void onConnected() {
        // Do nothing if we were disposed
        if (mediaBrowser == null) {
          return;
        }
        try {
          // Get a MediaController for the MediaSession
          mediaController = new MediaControllerCompat(
            context,
            mediaBrowser.getSessionToken());
          // Link to the callback controller
          mediaController.registerCallback(mediaControllerCallback);
          // Sync existing MediaSession state with UI
          browserViewSync();
        } catch (RemoteException remoteException) {
          Log.d(LOG_TAG, "onConnected: problem: ", remoteException);
          throw new RuntimeException(remoteException);
        }
        // Nota: no mediaBrowser.subscribe here needed
      }

      @Override
      public void onConnectionSuspended() {
        releaseBrowserResources();
        mediaBrowser = null;
      }

      @Override
      public void onConnectionFailed() {
        Log.d(LOG_TAG, "Connection to RadioService failed");
      }
    };
  // DLNA devices management
  private DlnaDevicesAdapter dlnaDevicesAdapter = null;
  // UPnP service listener
  private final RegistryListener browseRegistryListener = new DefaultRegistryListener() {
    @Override
    public void remoteDeviceAdded(Registry registry, final RemoteDevice remoteDevice) {
      Log.i(LOG_TAG,
        "remoteDeviceAdded: " + remoteDevice.getDisplayString() + " " + remoteDevice.toString());
      for (Service<?, ?> service : remoteDevice.getServices()) {
        if (service.getServiceId().equals(AV_TRANSPORT_SERVICE_ID)) {
          Log.i(LOG_TAG, ">> is UPnP reader");
          // Add DlnaDevice to Adapter
          handler.post(new Runnable() {
            public void run() {
              // Do nothing if we were disposed
              if (dlnaDevicesAdapter != null) {
                dlnaDevicesAdapter.addOrReplace(remoteDevice);
              }
            }
          });
          break;
        }
      }
    }

    @Override
    public void remoteDeviceRemoved(Registry registry, final RemoteDevice remoteDevice) {
      Log.i(LOG_TAG,
        "remoteDeviceRemoved: " + remoteDevice.getDisplayString() + " " + remoteDevice.toString());
      handler.post(new Runnable() {
        public void run() {
          // Do nothing if we were disposed
          if (dlnaDevicesAdapter != null) {
            dlnaDevicesAdapter.remove(remoteDevice);
          }
        }
      });
    }
  };
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
      androidUpnpService = (AndroidUpnpService) service;
      Registry registry = androidUpnpService.getRegistry();
      // May be null if onCreateView() not yet called
      if (dlnaDevicesAdapter == null) {
        registry.removeAllRemoteDevices();
      } else {
        dlnaDevicesAdapter.clear();
        // Add all devices to the list we already know about
        for (Device<?, ?, ?> device : registry.getDevices()) {
          if (device instanceof RemoteDevice) {
            browseRegistryListener.remoteDeviceAdded(registry, (RemoteDevice) device);
          }
        }
      }
      // Get ready for future device advertisements
      registry.addListener(browseRegistryListener);
      androidUpnpService.getControlPoint().search();
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
      releaseUpnpResources();
    }
  };
  private long timeDlnaSearch = 0;

  @Override
  public Radio getRadioFromId(@NonNull Long radioId) {
    return radioLibrary.getFrom(radioId);
  }

  @Override
  public void onRowClick(@NonNull Radio radio) {
    if (NetworkTester.isDeviceOffline(Objects.requireNonNull(getActivity()))) {
      tell(R.string.no_internet);
      return;
    }
    startReading(radio);
    if (!gotItRadioLongPress) {
      radioLongPressAlertDialog.show();
    }
  }

  @Override
  public boolean onPreferredClick(@NonNull Long radioId, Boolean isPreferred) {
    ContentValues values = new ContentValues();
    values.put(RadioSQLContract.Columns.COLUMN_IS_PREFERRED, isPreferred.toString());
    if (radioLibrary.updateFrom(radioId, values) > 0) {
      return true;
    } else {
      Log.w(LOG_TAG, "Internal failure, radio database update failed");
      return false;
    }
  }

  @Override
  public void onCreateOptionsMenu(@NonNull Menu menu) {
    preferredMenuItem = menu.findItem(R.id.action_preferred);
    dlnaMenuItem = menu.findItem(R.id.action_dlna);
    setPreferredMenuItem();
    setDlnaMenuItem();
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    // Restore saved state, if any
    if (savedInstanceState != null) {
      isPreferredRadios = savedInstanceState.getBoolean(getString(R.string.key_preferred_radios));
    }
    // Shared preferences
    SharedPreferences sharedPreferences =
      Objects.requireNonNull(getActivity()).getPreferences(Context.MODE_PRIVATE);
    gotItRadioLongPress =
      sharedPreferences.getBoolean(getString(R.string.key_radio_long_press_got_it), false);
    gotItPlayLongPress =
      sharedPreferences.getBoolean(getString(R.string.key_play_long_press_got_it), false);
    gotItDlnaEnable =
      sharedPreferences.getBoolean(getString(R.string.key_dlna_enable_got_it), false);
    // Adapters
    // Don't re-create if re-enter in fragment
    if (dlnaDevicesAdapter == null) {
      dlnaDevicesAdapter = new DlnaDevicesAdapter(
        getActivity(),
        (savedInstanceState == null) ?
          null : savedInstanceState.getString(getString(R.string.key_selected_device)),
        new DlnaDevicesAdapter.Listener() {
          @Override
          public void onRowClick(@NonNull DlnaDevice dlnaDevice, boolean isChosen) {
            if (isChosen) {
              Radio radio = getCurrentRadio();
              if (radio != null) {
                startReading(radio);
              }
              tell(getResources().getString(R.string.dlna_selection) + dlnaDevice);
            } else {
              tell(R.string.no_dlna_selection);
            }
            dlnaAlertDialog.dismiss();
          }

          @Override
          public void onChosenDeviceChange() {
            // Do nothing if not yet created or if we were disposed
            if ((dlnaMenuItem != null) && (getActivity() != null)) {
              setDlnaMenuItem();
            }
          }
        });
    }
    radiosAdapter = new RadiosAdapter(getActivity(), this, RADIO_ICON_SIZE / 2);
    radiosView.setLayoutManager(
      new LinearLayoutManager(getActivity(), LinearLayoutManager.HORIZONTAL, false));
    radiosView.setAdapter(radiosAdapter);
    // Build alert dialogs
    AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setMessage(R.string.radio_long_press)
      .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialogInterface, int i) {
          gotItRadioLongPress = true;
        }
      });
    radioLongPressAlertDialog = alertDialogBuilder.create();
    alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setMessage(R.string.play_long_press)
      .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialogInterface, int i) {
          gotItPlayLongPress = true;
        }
      });
    playLongPressAlertDialog = alertDialogBuilder.create();
    alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setMessage(R.string.dlna_enable)
      .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialogInterface, int i) {
          gotItDlnaEnable = true;
        }
      });
    dlnaEnableAlertDialog = alertDialogBuilder.create();
    // Specific DLNA devices dialog
    dlnaAlertDialog = new AlertDialog.Builder(getActivity()).setView(dlnaView).create();
    dlnaRecyclerView.setLayoutManager(new LinearLayoutManager(getActivity()));
    dlnaRecyclerView.setAdapter(dlnaDevicesAdapter);
  }

  @Override
  public void onResume() {
    super.onResume();
    browserViewSync();
    setRadiosView();
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return new View.OnClickListener() {
      @Override
      public void onClick(View view) {
        if (!NetworkTester.hasWifiIpAddress(Objects.requireNonNull(getActivity()))) {
          tell(R.string.LAN_required);
          return;
        }
        if (androidUpnpService == null) {
          tell(R.string.device_no_device_yet);
          return;
        }
        // Do not search more than 1 peer 5 s
        if (System.currentTimeMillis() - timeDlnaSearch > 5000) {
          timeDlnaSearch = System.currentTimeMillis();
          androidUpnpService.getControlPoint().search();
        }
        dlnaAlertDialog.show();
        if (!gotItDlnaEnable) {
          dlnaEnableAlertDialog.show();
        }
      }
    };
  }

  @NonNull
  @Override
  public View.OnLongClickListener getFloatingActionButtonOnLongClickListener() {
    return new View.OnLongClickListener() {
      @Override
      public boolean onLongClick(View view) {
        if (!NetworkTester.hasWifiIpAddress(Objects.requireNonNull(getActivity()))) {
          tell(R.string.LAN_required);
          return true;
        }
        if (androidUpnpService == null) {
          tell(R.string.device_no_device_yet);
          return true;
        }
        dlnaDevicesAdapter.clear();
        androidUpnpService.getRegistry().removeAllRemoteDevices();
        tell(R.string.dlna_reset);
        return true;
      }
    };
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_cast_black_24dp;
  }

  @Override
  public int getMenuId() {
    return R.menu.menu_main;
  }

  @Override
  public int getTitle() {
    return R.string.title_main;
  }

  @Nullable
  @Override
  public View onCreateView(
    @NonNull LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    // Inflate the view so that graphical objects exists
    View view = inflater.inflate(R.layout.content_main, container, false);
    playedRadioDataLinearLayout = view.findViewById(R.id.played_radio_data_linear_layout);
    albumArtImageView = view.findViewById(R.id.album_art_image_view);
    playedRadioNameTextView = view.findViewById(R.id.played_radio_name_text_view);
    playedRadioNameTextView.setSelected(true); // For scrolling
    playedRadioInformationTextView = view.findViewById(R.id.played_radio_information_text_view);
    playedRadioInformationTextView.setSelected(true); // For scrolling
    playedRadioRateTextView = view.findViewById(R.id.played_radio_rate_text_view);
    playFrameLayout = view.findViewById(R.id.play_frame_layout);
    progressBar = view.findViewById(R.id.progress_bar);
    playImageButton = view.findViewById(R.id.play_image_button);
    playImageButton.setOnClickListener(this);
    playImageButton.setOnLongClickListener(this);
    radiosView = view.findViewById(R.id.radios_recycler_view);
    radiosDefaultView = view.findViewById(R.id.view_radios_default);
    dlnaView = inflater.inflate(R.layout.view_dlna_devices, container, false);
    dlnaRecyclerView = dlnaView.findViewById(R.id.dlna_devices_recycler_view);
    return view;
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(getString(R.string.key_preferred_radios), isPreferredRadios);
    DlnaDevice chosenDlnaDevice = dlnaDevicesAdapter.getChosenDlnaDevice();
    outState.putString(
      getString(R.string.key_selected_device),
      (chosenDlnaDevice == null) ? null : chosenDlnaDevice.getIdentity());
  }

  @Override
  public void onPause() {
    super.onPause();
    // Shared preferences
    Objects.requireNonNull(getActivity())
      .getPreferences(Context.MODE_PRIVATE)
      .edit()
      .putBoolean(getString(R.string.key_radio_long_press_got_it), gotItRadioLongPress)
      .putBoolean(getString(R.string.key_play_long_press_got_it), gotItRadioLongPress)
      .putBoolean(getString(R.string.key_dlna_enable_got_it), gotItDlnaEnable)
      .apply();
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    switch (item.getItemId()) {
      case R.id.action_preferred:
        isPreferredRadios = !isPreferredRadios;
        setPreferredMenuItem();
        setRadiosView();
        return true;
      case R.id.action_dlna:
        dlnaDevicesAdapter.removeChosenDlnaDevice();
        dlnaMenuItem.setVisible(false);
        tell(R.string.dlna_reset);
        return true;
      default:
        // If we got here, the user's action was not recognized
        return false;
    }
  }

  @Override
  public void onClick(View view) {
    // Should not happen
    if (mediaController == null) {
      tell(R.string.radio_connection_waiting);
    } else {
      // Tag on button has stored state to reach
      switch ((int) playImageButton.getTag()) {
        case PlaybackStateCompat.STATE_PLAYING:
          mediaController.getTransportControls().play();
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          mediaController.getTransportControls().pause();
          break;
        case PlaybackStateCompat.STATE_STOPPED:
          mediaController.getTransportControls().stop();
          break;
        default:
          // Should not happen
          Log.d(LOG_TAG, "Internal failure, no action to perform on play button");
      }
      if (!gotItPlayLongPress) {
        playLongPressAlertDialog.show();
      }
    }
  }

  @Override
  public boolean onLongClick(View view) {
    // Should not happen
    if (mediaController == null) {
      tell(R.string.radio_connection_waiting);
    } else {
      mediaController.getTransportControls().stop();
    }
    return true;
  }

  // Must be called on activity resume
  // Handle services
  public void onActivityResume(@NonNull Context context) {
    // Force context setting to ensure it is known by MainFragment (getActivity() may be null)
    this.context = context;
    // Start the UPnP service
    if (!this.context.bindService(
      new Intent(this.context, AndroidUpnpServiceImpl.class),
      upnpConnection,
      BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "onActivityResume: internal failure; AndroidUpnpService not bound");
    }
    // MediaBrowser creation, launch RadioService
    if (mediaBrowser == null) {
      mediaBrowser = new MediaBrowserCompat(
        this.context,
        new ComponentName(this.context, RadioService.class),
        mediaBrowserConnectionCallback,
        null);
      mediaBrowser.connect();
    }
  }

  // Must be called on activity pause
  // Handle services
  public void onActivityPause() {
    releaseBrowserResources();
    if (mediaBrowser != null) {
      mediaBrowser.disconnect();
      mediaBrowser = null;
    }
    releaseUpnpResources();
    context.unbindService(upnpConnection);
  }

  private void browserViewSync() {
    if (mediaController != null) {
      mediaControllerCallback.onMetadataChanged(mediaController.getMetadata());
      mediaControllerCallback.onPlaybackStateChanged(mediaController.getPlaybackState());
    }
  }

  private void releaseUpnpResources() {
    if (androidUpnpService != null) {
      androidUpnpService.getRegistry().removeListener(browseRegistryListener);
      androidUpnpService = null;
    }
  }

  private void releaseBrowserResources() {
    if (mediaController != null) {
      mediaController.unregisterCallback(mediaControllerCallback);
      mediaController = null;
    }
  }

  // MediaController controls played radio
  @Nullable
  private Radio getCurrentRadio() {
    return (mediaController == null) ? null : radioLibrary.getFrom(mediaController.getMetadata());
  }

  // Utility to set radio list views
  private void setRadiosView() {
    List<Long> chosenRadioIds =
      isPreferredRadios ? radioLibrary.getPreferredRadioIds() : radioLibrary.getAllRadioIds();
    radiosAdapter.setRadioIds(chosenRadioIds);
    // Default view if no radio
    radiosDefaultView.setVisibility((chosenRadioIds.size() == 0) ? View.VISIBLE : View.INVISIBLE);
  }

  private void setPreferredMenuItem() {
    preferredMenuItem.setIcon(
      isPreferredRadios ? R.drawable.ic_star_black_30dp : R.drawable.ic_star_border_black_30dp);
  }

  private void startReading(@NonNull Radio radio) {
    if (mediaController == null) {
      tell(R.string.radio_connection_waiting);
      return;
    }
    // Allow to tell error again
    isErrorAllowedToTell = true;
    Bundle bundle = new Bundle();
    DlnaDevice chosenDlnaDevice = dlnaDevicesAdapter.getChosenDlnaDevice();
    if ((androidUpnpService != null) &&
      NetworkTester.hasWifiIpAddress(Objects.requireNonNull(getActivity())) &&
      (chosenDlnaDevice != null)) {
      bundle.putString(getString(R.string.key_dlna_device), chosenDlnaDevice.getIdentity());
    }
    mediaController.getTransportControls().prepareFromMediaId(radio.getId().toString(), bundle);
  }

  private void setDlnaMenuItem() {
    Bitmap icon = dlnaDevicesAdapter.getChosenDlnaDeviceIcon();
    dlnaMenuItem.setVisible((icon != null));
    if (icon != null) {
      dlnaMenuItem.setIcon(new BitmapDrawable(getResources(), icon));
    }
  }
}