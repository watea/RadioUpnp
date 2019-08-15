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
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

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
import org.fourthline.cling.model.meta.Icon;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.registry.RegistryListener;

import java.util.List;

import static android.content.Context.BIND_AUTO_CREATE;

public class MainFragment
  extends MainActivityFragment
  implements
  RadiosAdapter.Listener,
  View.OnClickListener,
  View.OnLongClickListener {
  private static final String LOG_TAG = MainFragment.class.getName();
  private static final String AVTTRANSPORT_SERVICE_ID = "urn:upnp-org:serviceId:AVTransport";
  private final Handler mHandler = new Handler();
  // <HMI assets
  private ImageButton mPlayImageButton;
  private ProgressBar mProgressBar;
  private ImageView mAlbumArtImageView;
  private TextView mPlayedRadioNameTextView;
  private TextView mPlayedRadioInformationTextView;
  private View mRadiosDefaultView;
  private MenuItem mPreferredMenuItem;
  private int mScreenWidthDp;
  private AlertDialog mDlnaAlertDialog;
  private AlertDialog mRadioLongPressAlertDialog;
  private AlertDialog mPlayLongPressAlertDialog;
  private AlertDialog mDlnaEnableAlertDialog;
  // />
  private boolean mIsPreferredRadios = false;
  private boolean mGotItRadioLongPress;
  private boolean mGotItPlayLongPress;
  private boolean mGotItDlnaEnable;
  private MediaControllerCompat mMediaController = null;
  // Callback from media control
  private final MediaControllerCompat.Callback mMediaControllerCallback =
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
              mPlayedRadioNameTextView.setVisibility(View.VISIBLE);
              mPlayedRadioInformationTextView.setVisibility(View.VISIBLE);
              mAlbumArtImageView.setVisibility(View.VISIBLE);
              // DLNA device doesn't support PAUSE but STOP
              // No extras for local playing
              boolean isLocal = mMediaController.getExtras().isEmpty();
              mPlayImageButton.setImageResource(
                isLocal ? R.drawable.ic_pause_black_24dp : R.drawable.ic_stop_black_24dp);
              mPlayImageButton.setTag(
                isLocal ? PlaybackStateCompat.STATE_PAUSED : PlaybackStateCompat.STATE_STOPPED);
              mPlayImageButton.setVisibility(View.VISIBLE);
              mProgressBar.setVisibility(View.INVISIBLE);
              break;
            case PlaybackStateCompat.STATE_PAUSED:
              mPlayedRadioNameTextView.setVisibility(View.VISIBLE);
              mPlayedRadioInformationTextView.setVisibility(View.VISIBLE);
              mAlbumArtImageView.setVisibility(View.VISIBLE);
              mPlayImageButton.setImageResource(R.drawable.ic_play_arrow_black_24dp);
              mPlayImageButton.setTag(PlaybackStateCompat.STATE_PLAYING);
              mPlayImageButton.setVisibility(View.VISIBLE);
              mProgressBar.setVisibility(View.INVISIBLE);
              break;
            case PlaybackStateCompat.STATE_BUFFERING:
            case PlaybackStateCompat.STATE_CONNECTING:
              mPlayedRadioNameTextView.setVisibility(View.VISIBLE);
              mPlayedRadioInformationTextView.setVisibility(View.VISIBLE);
              mAlbumArtImageView.setVisibility(View.VISIBLE);
              mPlayImageButton.setVisibility(View.INVISIBLE);
              mProgressBar.setVisibility(View.VISIBLE);
              break;
            case PlaybackStateCompat.STATE_NONE:
            case PlaybackStateCompat.STATE_STOPPED:
              mPlayedRadioNameTextView.setVisibility(View.INVISIBLE);
              mPlayedRadioInformationTextView.setVisibility(View.INVISIBLE);
              mAlbumArtImageView.setVisibility(View.INVISIBLE);
              mPlayImageButton.setVisibility(View.INVISIBLE);
              mProgressBar.setVisibility(View.INVISIBLE);
              break;
            default:
              mPlayedRadioNameTextView.setVisibility(View.INVISIBLE);
              mPlayedRadioInformationTextView.setVisibility(View.INVISIBLE);
              mAlbumArtImageView.setVisibility(View.INVISIBLE);
              mPlayImageButton.setVisibility(View.INVISIBLE);
              mProgressBar.setVisibility(View.INVISIBLE);
              tell(R.string.radio_connection_error);
          }
        }
      }

      @Override
      public void onMetadataChanged(final MediaMetadataCompat mediaMetadata) {
        // Do nothing if view not defined or nothing to change
        if (isActuallyAdded() && (mediaMetadata != null)) {
          mPlayedRadioNameTextView.setText(
            mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
          String radioInformation =
            mediaMetadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
          mPlayedRadioInformationTextView.setText(radioInformation);
          //noinspection SuspiciousNameCombination
          mAlbumArtImageView.setImageBitmap(
            Bitmap.createScaledBitmap(
              mediaMetadata.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART),
              mScreenWidthDp,
              mScreenWidthDp,
              false));
        }
      }
    };
  private RadiosAdapter mRadiosAdapter;
  private AndroidUpnpService mAndroidUpnpService = null;
  private MediaBrowserCompat mMediaBrowser = null;
  // Forced context has getActivity(), used when getActivity() may be null
  private Context mContext = null;
  // MediaController from the MediaBrowser when it has successfully connected
  private final MediaBrowserCompat.ConnectionCallback mMediaBrowserConnectionCallback =
    new MediaBrowserCompat.ConnectionCallback() {
      @Override
      public void onConnected() {
        // Do nothing if we were disposed
        if (mMediaBrowser == null) {
          return;
        }
        try {
          // Get a MediaController for the MediaSession
          mMediaController = new MediaControllerCompat(
            mContext,
            mMediaBrowser.getSessionToken());
          // Link to the callback controller
          mMediaController.registerCallback(mMediaControllerCallback);
          // Sync existing MediaSession state with UI
          browserViewSync();
        } catch (RemoteException remoteException) {
          Log.d(LOG_TAG, "onConnected: problem: ", remoteException);
          throw new RuntimeException(remoteException);
        }
        // Nota: no mMediaBrowser.subscribe here needed
      }

      @Override
      public void onConnectionSuspended() {
        releaseBrowserResources();
        mMediaBrowser = null;
      }

      @Override
      public void onConnectionFailed() {
        Log.d(LOG_TAG, "Connection to RadioService failed");
      }
    };
  // DLNA devices management
  private DlnaDevicesAdapter mDlnaDevicesAdapter = null;
  // UPnP service listener
  private final RegistryListener mBrowseRegistryListener = new DefaultRegistryListener() {
    @Override
    public void remoteDeviceAdded(Registry registry, final RemoteDevice remoteDevice) {
      for (Service service : remoteDevice.getServices()) {
        if (service.getServiceId().toString().equals(AVTTRANSPORT_SERVICE_ID)) {
          final DlnaDevice dlnaDevice = new DlnaDevice(remoteDevice);
          // Search for icon
          if (remoteDevice.isFullyHydrated()) {
            Icon deviceIcon = getLargestIcon(remoteDevice.getIcons());
            dlnaDevice.setIcon((deviceIcon == null) ? null :
              NetworkTester.getBitmapFromUrl(remoteDevice.normalizeURI(deviceIcon.getUri())));
          }
          // Add DlnaDevice to Adapter
          mHandler.post(new Runnable() {
            public void run() {
              // Do nothing if we were disposed
              if (mDlnaDevicesAdapter != null) {
                int position = mDlnaDevicesAdapter.getPosition(dlnaDevice);
                if (position >= 0) {
                  DlnaDevice foundDlnaDevice = mDlnaDevicesAdapter.getItem(position);
                  if (foundDlnaDevice != null) {
                    foundDlnaDevice.setDevice(remoteDevice);
                    foundDlnaDevice.setIcon(dlnaDevice.getIcon());
                  }
                } else {
                  mDlnaDevicesAdapter.add(dlnaDevice);
                }
              }
            }
          });
          break;
        }
      }
    }

    @Override
    public void remoteDeviceRemoved(Registry registry, final RemoteDevice remoteDevice) {
      mHandler.post(new Runnable() {
        public void run() {
          // Do nothing if we were disposed
          if (mDlnaDevicesAdapter != null) {
            mDlnaDevicesAdapter.remove(new DlnaDevice(remoteDevice));
          }
        }
      });
    }

    @Nullable
    private Icon getLargestIcon(@NonNull Icon[] deviceIcons) {
      Icon icon = null;
      int maxWidth = 0;
      for (Icon deviceIcon : deviceIcons) {
        int width = deviceIcon.getWidth();
        if (width > maxWidth) {
          maxWidth = width;
          icon = deviceIcon;
        }
      }
      return icon;
    }
  };
  private final ServiceConnection mUpnpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
      mAndroidUpnpService = (AndroidUpnpService) service;
      mHandler.post(new Runnable() {
        public void run() {
          // Do nothing if we were disposed
          if (mAndroidUpnpService == null) {
            return;
          }
          final Registry registry = mAndroidUpnpService.getRegistry();
          // May be null if onCreateView() not yet called
          if (mDlnaDevicesAdapter == null) {
            registry.removeAllRemoteDevices();
          } else {
            mDlnaDevicesAdapter.clear();
            // Add all devices to the list we already know about
            new Thread() {
              @Override
              public void run() {
                super.run();
                for (Device device : registry.getDevices()) {
                  if (device instanceof RemoteDevice) {
                    mBrowseRegistryListener.remoteDeviceAdded(registry, (RemoteDevice) device);
                  }
                }
              }
            }.start();
          }
          // Get ready for future device advertisements
          registry.addListener(mBrowseRegistryListener);
          mAndroidUpnpService.getControlPoint().search();
        }
      });
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
      releaseUpnpResources();
    }
  };
  private long mTimeDlnaSearch = 0;

  @Override
  public Radio getRadioFromId(@NonNull Long radioId) {
    return mRadioLibrary.getFrom(radioId);
  }

  @Override
  public void onRowClick(@NonNull Radio radio) {
    if (NetworkTester.isDeviceOffline(getActivity())) {
      tell(R.string.no_internet);
      return;
    }
    startReading(radio);
    if (!mGotItRadioLongPress) {
      mRadioLongPressAlertDialog.show();
    }
  }

  @Override
  public boolean onPreferredClick(@NonNull Long radioId, Boolean isPreferred) {
    ContentValues values = new ContentValues();
    values.put(RadioSQLContract.Columns.COLUMN_IS_PREFERRED, isPreferred.toString());
    if (mRadioLibrary.updateFrom(radioId, values) > 0) {
      return true;
    } else {
      Log.w(LOG_TAG, "Internal failure, radio database update failed");
      return false;
    }
  }

  @Override
  public void onCreateOptionsMenu(@NonNull Menu menu) {
    // Init Preferred MenuItem...
    mPreferredMenuItem = menu.findItem(R.id.action_preferred);
    // ...set it
    setPreferredMenuItem();
  }

  @Nullable
  @Override
  public View onCreateView(
    LayoutInflater inflater, @Nullable ViewGroup container, Bundle savedInstanceState) {
    super.onCreateView(inflater, container, savedInstanceState);
    // Restore saved state, if any
    if (savedInstanceState != null) {
      mIsPreferredRadios = savedInstanceState.getBoolean(getString(R.string.key_preferred_radios));
    }
    // Shared preferences
    SharedPreferences sharedPreferences = getActivity().getPreferences(Context.MODE_PRIVATE);
    mGotItRadioLongPress =
      sharedPreferences.getBoolean(getString(R.string.key_radio_long_press_got_it), false);
    mGotItPlayLongPress =
      sharedPreferences.getBoolean(getString(R.string.key_play_long_press_got_it), false);
    mGotItDlnaEnable =
      sharedPreferences.getBoolean(getString(R.string.key_dlna_enable_got_it), false);
    // Display metrics
    mScreenWidthDp = getResources().getConfiguration().screenWidthDp;
    // Inflate the view so that graphical objects exists
    View view = inflater.inflate(R.layout.content_main, container, false);
    // Fill content including recycler
    // A an exception, created only if necessary as handles UPnP events
    if (mDlnaDevicesAdapter == null) {
      mDlnaDevicesAdapter = new DlnaDevicesAdapter(
        getActivity(),
        R.layout.row_dlna_device,
        (savedInstanceState == null) ?
          null : savedInstanceState.getString(getString(R.string.key_selected_device)));
    }
    // Build alert dialogs
    AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setAdapter(
        mDlnaDevicesAdapter,
        new DialogInterface.OnClickListener() {
          @Override
          public void onClick(DialogInterface dialogInterface, int i) {
            mDlnaDevicesAdapter.onSelection(i);
            if (mDlnaDevicesAdapter.hasChosenDlnaDevice()) {
              Radio radio = getCurrentRadio();
              if (radio != null) {
                startReading(radio);
              }
              tell(
                getResources().getString(R.string.dlna_selection) + mDlnaDevicesAdapter.getItem(i));
            } else {
              tell(R.string.no_dlna_selection);
            }
          }
        });
    mDlnaAlertDialog = alertDialogBuilder.create();
    alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setMessage(R.string.radio_long_press)
      .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialogInterface, int i) {
          mGotItRadioLongPress = true;
        }
      });
    mRadioLongPressAlertDialog = alertDialogBuilder.create();
    alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setMessage(R.string.play_long_press)
      .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialogInterface, int i) {
          mGotItPlayLongPress = true;
        }
      });
    mPlayLongPressAlertDialog = alertDialogBuilder.create();
    alertDialogBuilder = new AlertDialog.Builder(getActivity())
      .setMessage(R.string.dlna_enable)
      .setPositiveButton(R.string.got_it, new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialogInterface, int i) {
          mGotItDlnaEnable = true;
        }
      });
    mDlnaEnableAlertDialog = alertDialogBuilder.create();
    mAlbumArtImageView = view.findViewById(R.id.album_art_image_view);
    mPlayedRadioNameTextView = view.findViewById(R.id.played_radio_name_text_view);
    mPlayedRadioNameTextView.setSelected(true); // For scrolling
    mPlayedRadioInformationTextView = view.findViewById(R.id.played_radio_information_text_view);
    mPlayedRadioInformationTextView.setSelected(true); // For scrolling
    mRadiosAdapter = new RadiosAdapter(getActivity(), this);
    RecyclerView radiosView = view.findViewById(R.id.radios_recycler_view);
    radiosView.setLayoutManager(
      new LinearLayoutManager(getActivity(), LinearLayoutManager.HORIZONTAL, false));
    radiosView.setAdapter(mRadiosAdapter);
    mRadiosDefaultView = view.findViewById(R.id.view_radios_default);
    mProgressBar = view.findViewById(R.id.progressbar);
    mProgressBar.setVisibility(View.INVISIBLE);
    mPlayImageButton = view.findViewById(R.id.play_image_button);
    mPlayImageButton.setVisibility(View.INVISIBLE);
    mPlayImageButton.setOnClickListener(this);
    mPlayImageButton.setOnLongClickListener(this);
    return view;
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
        if (!NetworkTester.hasWifiIpAddress(getActivity())) {
          tell(R.string.LAN_required);
          return;
        }
        if (mAndroidUpnpService == null) {
          tell(R.string.device_no_device_yet);
          return;
        }
        // Do not search more than 1 peer 5 s
        if (System.currentTimeMillis() - mTimeDlnaSearch > 5000) {
          mTimeDlnaSearch = System.currentTimeMillis();
          mAndroidUpnpService.getControlPoint().search();
        }
        mDlnaAlertDialog.show();
        if (!mGotItDlnaEnable) {
          mDlnaEnableAlertDialog.show();
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
        if (!NetworkTester.hasWifiIpAddress(getActivity())) {
          tell(R.string.LAN_required);
          return true;
        }
        if (mAndroidUpnpService == null) {
          tell(R.string.device_no_device_yet);
          return true;
        }
        mAndroidUpnpService.getRegistry().removeAllRemoteDevices();
        mDlnaDevicesAdapter.removeChosenDlnaDevice();
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

  @Override
  public void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putBoolean(getString(R.string.key_preferred_radios), mIsPreferredRadios);
    outState.putString(
      getString(R.string.key_selected_device), mDlnaDevicesAdapter.getChosenDlnaDeviceIdentity());
  }

  @Override
  public void onPause() {
    super.onPause();
    // Shared preferences
    getActivity()
      .getPreferences(Context.MODE_PRIVATE)
      .edit()
      .putBoolean(getString(R.string.key_radio_long_press_got_it), mGotItRadioLongPress)
      .putBoolean(getString(R.string.key_play_long_press_got_it), mGotItRadioLongPress)
      .putBoolean(getString(R.string.key_dlna_enable_got_it), mGotItDlnaEnable)
      .apply();
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == R.id.action_preferred) {
      mIsPreferredRadios = !mIsPreferredRadios;
      setPreferredMenuItem();
      setRadiosView();
      return true;
    } else {
      // If we got here, the user's action was not recognized
      return false;
    }
  }

  @Override
  public void onClick(View view) {
    // Should not happen
    if (mMediaController == null) {
      tell(R.string.radio_connection_waiting);
    } else {
      // Tag on button has stored state to reach
      switch ((int) mPlayImageButton.getTag()) {
        case PlaybackStateCompat.STATE_PLAYING:
          mMediaController.getTransportControls().play();
          break;
        case PlaybackStateCompat.STATE_PAUSED:
          mMediaController.getTransportControls().pause();
          break;
        case PlaybackStateCompat.STATE_STOPPED:
          mMediaController.getTransportControls().stop();
          break;
        default:
          // Should not happen
          Log.d(LOG_TAG, "Internal failure, no action to perform on play button");
      }
      if (!mGotItPlayLongPress) {
        mPlayLongPressAlertDialog.show();
      }
    }
  }

  @Override
  public boolean onLongClick(View view) {
    // Should not happen
    if (mMediaController == null) {
      tell(R.string.radio_connection_waiting);
    } else {
      mMediaController.getTransportControls().stop();
    }
    return true;
  }

  // Must be called on activity resume
  // Handle services
  public void onActivityResume(@NonNull Context context) {
    // Force context setting to ensure it is known by MainFragment (getActivity() may be null)
    mContext = context;
    // Start the UPnP service
    if (!mContext.bindService(
      new Intent(mContext, AndroidUpnpServiceImpl.class),
      mUpnpConnection,
      BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "onActivityResume: internal failure; AndroidUpnpService not bound");
    }
    // MediaBrowser creation, launch RadioService
    if (mMediaBrowser == null) {
      mMediaBrowser = new MediaBrowserCompat(
        mContext,
        new ComponentName(mContext, RadioService.class),
        mMediaBrowserConnectionCallback,
        null);
      mMediaBrowser.connect();
    }
  }

  // Must be called on activity pause
  // Handle services
  public void onActivityPause() {
    releaseBrowserResources();
    if (mMediaBrowser != null) {
      mMediaBrowser.disconnect();
      mMediaBrowser = null;
    }
    releaseUpnpResources();
    mContext.unbindService(mUpnpConnection);
  }

  private void browserViewSync() {
    if (mMediaController != null) {
      mMediaControllerCallback.onMetadataChanged(mMediaController.getMetadata());
      mMediaControllerCallback.onPlaybackStateChanged(mMediaController.getPlaybackState());
    }
  }

  private void releaseUpnpResources() {
    if (mAndroidUpnpService != null) {
      mAndroidUpnpService.getRegistry().removeListener(mBrowseRegistryListener);
      mAndroidUpnpService = null;
    }
  }

  private void releaseBrowserResources() {
    if (mMediaController != null) {
      mMediaController.unregisterCallback(mMediaControllerCallback);
      mMediaController = null;
    }
  }

  // MediaController controls played radio
  @Nullable
  private Radio getCurrentRadio() {
    return
      (mMediaController == null) ? null : mRadioLibrary.getFrom(mMediaController.getMetadata());
  }

  // Utility to set radio list views
  private void setRadiosView() {
    List<Long> chosenRadioIds =
      mIsPreferredRadios ? mRadioLibrary.getPreferredRadioIds() : mRadioLibrary.getAllRadioIds();
    mRadiosAdapter.setRadioIds(chosenRadioIds);
    // Default view if no radio
    mRadiosDefaultView.setVisibility((chosenRadioIds.size() == 0) ? View.VISIBLE : View.INVISIBLE);
  }

  private void setPreferredMenuItem() {
    mPreferredMenuItem.setIcon(
      mIsPreferredRadios ? R.drawable.ic_star_black_24dp : R.drawable.ic_star_border_black_24dp);
  }

  private void startReading(@NonNull Radio radio) {
    if (mMediaController == null) {
      tell(R.string.radio_connection_waiting);
      return;
    }
    Bundle bundle = new Bundle();
    if ((mAndroidUpnpService != null) &&
      NetworkTester.hasWifiIpAddress(getActivity()) &&
      mDlnaDevicesAdapter.hasChosenDlnaDevice()) {
      bundle.putString(getString(R.string.key_dlna_device), mDlnaDevicesAdapter.getChosenDlnaDeviceIdentity());
    }
    mMediaController.getTransportControls().prepareFromMediaId(radio.getId().toString(), bundle);
  }
}