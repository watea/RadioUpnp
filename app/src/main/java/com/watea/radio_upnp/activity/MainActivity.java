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
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.UpnpDevicesAdapter;
import com.watea.radio_upnp.model.DefaultRadios;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.model.legacy.RadioLibrary;
import com.watea.radio_upnp.service.NetworkProxy;
import com.watea.radio_upnp.upnp.AndroidUpnpService;
import com.watea.radio_upnp.upnp.Device;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.util.Calendar;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Vector;

// TODO vérifier les . dans commantaires plusieurs lignes (attention find in file que partiel)
// TODO uniformiser noms des vues
public class MainActivity
  extends AppCompatActivity
  implements NavigationView.OnNavigationItemSelectedListener {
  private static final int RADIO_ICON_SIZE = 300;
  private static final String LOG_TAG = MainActivity.class.getName();
  private static final Map<Class<? extends Fragment>, Integer> FRAGMENT_MENU_IDS =
    new Hashtable<Class<? extends Fragment>, Integer>() {
      {
        put(MainFragment.class, R.id.action_home);
        put(SearchFragment.class, R.id.action_search);
        put(ItemAddFragment.class, R.id.action_add_item);
        put(ModifyFragment.class, R.id.action_modify);
        put(DonationFragment.class, R.id.action_donate);
      }
    };
  private static final List<Listener> listeners = new Vector<>();
  private static Radios radios = null;
  private static Radio currentRadio = null;
  private DrawerLayout drawerLayout;
  private ActionBarDrawerToggle drawerToggle;
  private FloatingActionButton floatingActionButton;
  private Menu navigationMenu;
  private AlertDialog upnpAlertDialog;
  private View devicesDefaultView;
  private AlertDialog aboutAlertDialog;
  private CollapsingToolbarLayout actionBarLayout;
  private PlayerController playerController;
  private SharedPreferences sharedPreferences;
  // TODO
  //private ImportController importController;
  private RadioGardenController radioGardenController;
  private boolean gotItRadioGarden = false;
  private int navigationMenuCheckedId;
  private AndroidUpnpService.UpnpService upnpService = null;
  private UpnpDevicesAdapter upnpDevicesAdapter = null;
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
      upnpService = (AndroidUpnpService.UpnpService) service;
      if (upnpService != null) {
        // Add all devices to the list we already know about
        upnpDevicesAdapter.resetRemoteDevices();
        upnpService.getAliveDevices().forEach(upnpDevicesAdapter::onDeviceAdd);
        // Get ready for future device advertisements
        upnpService.addListener(upnpDevicesAdapter);
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      if (upnpService != null) {
        // Clear UPnP stuff
        upnpService.clearListeners();
        upnpService = null;
      }
      // No more devices
      upnpDevicesAdapter.resetRemoteDevices();
    }
  };
  private Intent newIntent = null;
  private NetworkProxy networkProxy = null;

  @NonNull
  public static Bitmap iconResize(@NonNull Bitmap bitmap) {
    return Radio.createScaledBitmap(bitmap, RADIO_ICON_SIZE);
  }

  @NonNull
  public static Bitmap iconHalfResize(@NonNull Bitmap bitmap) {
    return Radio.createScaledBitmap(bitmap, RADIO_ICON_SIZE / 2);
  }

  @NonNull
  public static Radios getRadios() {
    assert radios != null;
    return radios;
  }

  @Nullable
  public static Radio getCurrentRadio() {
    return currentRadio;
  }

  public static void setCurrentRadio(@Nullable String radioId) {
    currentRadio = (radioId == null) ? null : radios.getRadioFrom(radioId);
    listeners.forEach(listener -> listener.onNewCurrentRadio(currentRadio));
  }

  public static boolean isCurrentRadio(@NonNull Radio radio) {
    return (currentRadio == radio);
  }

  public static void addListener(@NonNull Listener listener) {
    listeners.add(listener);
  }

  public static void removeListener(@NonNull Listener listener) {
    listeners.remove(listener);
  }

  public static void setNotification(@NonNull Context context, @NonNull String packageName) {
    final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
    context.startActivity(intent);
  }

  @Nullable
  public AndroidUpnpService.UpnpService getUpnpService() {
    return upnpService;
  }

  @NonNull
  public Bitmap getDefaultIcon() {
    return BitmapFactory.decodeResource(getResources(), R.drawable.ic_radio_gray);
  }

  @SuppressLint("NonConstantResourceId")
  @Override
  public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
    final Integer id = menuItem.getItemId();
    // Note: switch not to use as id not final
    switch (id) {
      case R.id.action_radio_garden:
        radioGardenController.launchRadioGarden(gotItRadioGarden);
        break;
      case R.id.action_import:
        // TODO
        //importController.showAlertDialog();
        break;
      case R.id.action_about:
        aboutAlertDialog.show();
        break;
      case R.id.action_log:
        sendLogcatMail();
        break;
      case R.id.action_export:
        dumpRadios();
        break;
      default:
        // Shall not fail to find!
        for (Class<? extends Fragment> fragment : FRAGMENT_MENU_IDS.keySet()) {
          if (id.equals(FRAGMENT_MENU_IDS.get(fragment))) {
            setFragment(fragment);
          }
        }
    }
    drawerLayout.closeDrawers();
    return true;
  }

  public void onFragmentResume(@NonNull MainActivityFragment mainActivityFragment) {
    invalidateOptionsMenu();
    actionBarLayout.setTitle(getResources().getString(mainActivityFragment.getTitle()));
    floatingActionButton.setOnClickListener(
      mainActivityFragment.getFloatingActionButtonOnClickListener());
    floatingActionButton.setOnLongClickListener(
      mainActivityFragment.getFloatingActionButtonOnLongClickListener());
    int resource = mainActivityFragment.getFloatingActionButtonResource();
    if (resource != MainActivityFragment.DEFAULT_RESOURCE) {
      floatingActionButton.setImageResource(resource);
    }
    final Integer menuId = FRAGMENT_MENU_IDS.get(mainActivityFragment.getClass());
    // Change checked menu if necessary
    if (menuId != null) {
      checkNavigationMenu(menuId);
    }
    // Back button?
    final boolean isMainFragment = getCurrentFragment() instanceof MainFragment;
    drawerToggle.setDrawerIndicatorEnabled(isMainFragment);
    drawerLayout.setDrawerLockMode(
      isMainFragment ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
  }

  public void tell(int message) {
    Snackbar.make(getWindow().getDecorView().getRootView(), message, Snackbar.LENGTH_LONG).show();
  }

  public void tell(@NonNull String message) {
    Snackbar.make(getWindow().getDecorView().getRootView(), message, Snackbar.LENGTH_LONG).show();
  }

  public void onUpnpReset() {
    if (upnpService == null) {
      tell(R.string.service_not_available);
    } else {
      upnpService.getDevices().clear();
      upnpDevicesAdapter.resetRemoteDevices();
      tell(R.string.dlna_search_reset);
    }
  }

  // radio is null for current
  public void startReading(@Nullable Radio radio) {
    final Device chosenDevice = upnpDevicesAdapter.getChosenDevice();
    playerController.startReading(
      radio,
      ((upnpService != null) &&
        (chosenDevice != null) &&
        networkProxy.hasWifiIpAddress()) ?
        chosenDevice.getUUID() : null);
  }

  @NonNull
  public Fragment setFragment(@NonNull Class<? extends Fragment> fragmentClass) {
    final Fragment currentFragment = getCurrentFragment();
    final Fragment fragment;
    try {
      fragment = fragmentClass.getConstructor().newInstance();
    } catch (Exception exception) {
      // Should not happen
      Log.e(LOG_TAG, "setFragment: internal failure", exception);
      throw new RuntimeException();
    }
    final FragmentManager fragmentManager = getSupportFragmentManager();
    // Register back if fragment exists
    if (currentFragment != null) {
      fragmentManager
        .beginTransaction()
        .remove(currentFragment) // Avoid two ScrollView at same time
        .addToBackStack(null)
        .commit();
    }
    // Set
    fragmentManager.beginTransaction().replace(R.id.content_frame, fragment).commit();
    return fragment;
  }

  @NonNull
  public NetworkProxy getNetworkProxy() {
    assert networkProxy != null;
    return networkProxy;
  }

  public void checkNavigationMenu() {
    checkNavigationMenu(navigationMenuCheckedId);
  }

  @NonNull
  public UpnpDevicesAdapter getUpnpDevicesAdapter() {
    assert upnpDevicesAdapter != null;
    return upnpDevicesAdapter;
  }

  public void onUpnp() {
    upnpAlertDialog.show();
  }

  public boolean setRadioGardenGotIt() {
    return gotItRadioGarden = true;
  }

  @NonNull
  public Intent getNewSendIntent() {
    return new Intent(Intent.ACTION_SEND)
      .setType("message/rfc822")
      .putExtra(Intent.EXTRA_EMAIL, new String[]{"fr.watea@gmail.com"});
  }

  @Override
  public boolean onCreateOptionsMenu(@NonNull Menu menu) {
    final MainActivityFragment currentFragment = (MainActivityFragment) getCurrentFragment();
    if (currentFragment == null) {
      Log.e(LOG_TAG, "onCreateOptionsMenu: currentFragment not defined");
    } else {
      final int menuId = currentFragment.getMenuId();
      if (menuId != MainActivityFragment.DEFAULT_RESOURCE) {
        getMenuInflater().inflate(menuId, menu);
        currentFragment.onCreateOptionsMenu(menu);
      }
    }
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    // Pass the event to ActionBarDrawerToggle, if it returns
    // true, then it has handled the app icon touch event
    assert getCurrentFragment() != null;
    return
      drawerToggle.onOptionsItemSelected(item) ||
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        getCurrentFragment().onOptionsItemSelected(item) ||
        // If we got here, the user's action was not recognized
        // Invoke the superclass to handle it
        super.onOptionsItemSelected(item);
  }

  // Is called also when coming back after a "Back" exit
  @Override
  @SuppressLint("InflateParams")
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Fetch preferences
    sharedPreferences = getPreferences(Context.MODE_PRIVATE);
    // Init radios only if needed; a session may still be there with a radioId
    if (radios == null) {
      radios = new Radios(this);
    }
    if (sharedPreferences.getBoolean(getString(R.string.key_first_start), true)) {
      if (radios.addAll(DefaultRadios.get(this, RADIO_ICON_SIZE))) {
        // Robustness: store immediately to avoid bad user experience in case of app crash
        sharedPreferences
          .edit()
          .putBoolean(getString(R.string.key_first_start), false)
          // false: no legacy processing needed
          .putBoolean(getString(R.string.key_legacy_processed), false)
          .apply();
      } else {
        Log.e(LOG_TAG, "Internal failure; unable to init radios");
      }
    } else if (sharedPreferences.getBoolean(getString(R.string.key_legacy_processed), true)) {
      // Legacy support; this code should be removed after some time....
      // Robustness: store immediately to avoid bad user experience in case of app crash.
      storeBooleanPreference(R.string.key_legacy_processed, !processLegacy());
    }
    // Init connexion
    networkProxy = new NetworkProxy(this);
    // UPnP adapter (order matters)
    upnpDevicesAdapter = new UpnpDevicesAdapter(
      (savedInstanceState == null) ?
        null : savedInstanceState.getString(getString(R.string.key_selected_device)),
      new UpnpDevicesAdapter.Listener() {
        @Override
        public void onRowClick(@NonNull Device device, boolean isChosen) {
          if (isChosen) {
            startReading(null);
            tell(getResources().getString(R.string.dlna_selection) + device.getDisplayString());
          } else {
            tell(R.string.no_dlna_selection);
          }
          upnpAlertDialog.dismiss();
        }

        @Override
        public void onCountChange(boolean isEmpty) {
          devicesDefaultView.setVisibility(isEmpty ? View.VISIBLE : View.INVISIBLE);
        }
      });
    // Inflate view
    setContentView(R.layout.activity_main);
    drawerLayout = findViewById(R.id.main_activity);
    // ActionBar
    setSupportActionBar(findViewById(R.id.actionbar));
    actionBarLayout = findViewById(R.id.actionbar_layout);
    playerController = new PlayerController(this, actionBarLayout);
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    // Radio Garden
    radioGardenController = new RadioGardenController(this);
    // Import function
    // TODO
    //importController = new ImportController(this);
    // Set navigation drawer toggle (according to documentation)
    drawerToggle = new ActionBarDrawerToggle(
      this, drawerLayout, R.string.drawer_open, R.string.drawer_close);
    // Set the drawer toggle as the DrawerListener
    drawerLayout.addDrawerListener(drawerToggle);
    // Navigation drawer
    final NavigationView navigationView = findViewById(R.id.navigation_view);
    navigationView.setNavigationItemSelectedListener(this);
    navigationMenu = navigationView.getMenu();
    // Build alert about dialog
    final View aboutView = getLayoutInflater().inflate(R.layout.view_about, null);
    ((TextView) aboutView.findViewById(R.id.version_name_text_view))
      .setText(BuildConfig.VERSION_NAME);
    aboutAlertDialog = new AlertDialog.Builder(this, R.style.AlertDialogStyle)
      .setView(aboutView)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> checkNavigationMenu())
      .create();
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
      new AlertDialog.Builder(this, R.style.AlertDialogStyle)
        .setMessage(R.string.notification_needed)
        .setPositiveButton(
          R.string.action_go,
          (dialogInterface, i) -> setNotification(this, getPackageName()))
        .create()
        .show();
    }
    // Specific UPnP devices dialog
    final View contentUpnp = getLayoutInflater().inflate(R.layout.content_upnp, null);
    final RecyclerView upnpRecyclerView = contentUpnp.findViewById(R.id.devices_recycler_view);
    upnpRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    upnpRecyclerView.setAdapter(upnpDevicesAdapter);
    devicesDefaultView = contentUpnp.findViewById(R.id.view_devices_default);
    upnpAlertDialog = new AlertDialog.Builder(this, R.style.AlertDialogStyle)
      .setView(contentUpnp)
      .create();
    // FAB
    floatingActionButton = findViewById(R.id.floating_action_button);
    // Set fragment if context is not restored by Android
    if (savedInstanceState == null) {
      setFragment(MainFragment.class);
    }
    // Back
    getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        final FragmentManager fragmentManager = getSupportFragmentManager();
        // Avoid two ScrollView at same time
        final Fragment currentFragment = getCurrentFragment();
        if (currentFragment != null) {
          fragmentManager.beginTransaction().remove(currentFragment).commit();
        }
        // Back or finish
        fragmentManager.popBackStack();
        if (fragmentManager.getBackStackEntryCount() == 0) {
          finish();
        }
      }
    });
    // Store intent
    newIntent = getIntent();

//    SoapRequest soapRequest = new SoapRequest();
//    soapRequest.call();
//
//    UpnpService upnpService = new UpnpService(new UpnpService.Callback() {
//      @Override
//      public void onNewDevice(@NonNull Device device) {
//        Service service = device.getService("urn:upnp-org:serviceId:AVTransport");
//        UpnpRequest soapRequest = new UpnpRequest();
//        try {
//          soapRequest.call(
//            service.getActualControlURI().toString(),
//            "urn:schemas-upnp-org:service:AVTransport:1",
//            "GetTransportInfo",
//            new Hashtable<String, String>() {
//              {
//                put("InstanceId", "0");
//              }
//            });
//        } catch (URISyntaxException e) {
//          throw new RuntimeException(e);
//        }
//      }
//
//      @Override
//      public void onRemoveDevice(@NonNull Device device) {
//
//      }
//    });
//    upnpService.searchAll();
  }

  @Override
  protected void onPause() {
    super.onPause();
    Log.d(LOG_TAG, "onPause");
    // Shared preferences
    storeBooleanPreference(R.string.key_radio_garden_got_it, gotItRadioGarden);
    // Release UPnP service
    unbindService(upnpConnection);
    // Force disconnection to release resources
    upnpConnection.onServiceDisconnected(null);
    // Clear PlayerController call
    playerController.onActivityPause();
    Log.d(LOG_TAG, "onPause done!");
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    newIntent = intent;
  }

  @Override
  protected void onResume() {
    super.onResume();
    Log.d(LOG_TAG, "onResume");
    // Fetch preferences
    gotItRadioGarden = sharedPreferences.getBoolean(getString(R.string.key_radio_garden_got_it), false);
    // Bind to UPnP service
    if (!bindService(
      new Intent(this, AndroidUpnpService.class), upnpConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AndroidUpnpService not bound");
    }
    // Radio Garden share?
    if (newIntent != null) {
      radioGardenController.onNewIntent(newIntent);
      newIntent = null;
    }
    // PlayerController init
    playerController.onActivityResume();
    Log.d(LOG_TAG, "onResume done!");
  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    // Sync the toggle state after onRestoreInstanceState has occurred
    drawerToggle.syncState();
  }

  @Override
  public void onConfigurationChanged(@NonNull Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    drawerToggle.onConfigurationChanged(newConfig);
  }

  @Override
  protected void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    final Fragment currentFragment = getCurrentFragment();
    // Stored to tag activity has been disposed
    if (currentFragment != null) {
      outState.putString(
        getString(R.string.key_current_fragment), currentFragment.getClass().getSimpleName());
    }
    // May not exist
    if (upnpDevicesAdapter != null) {
      Device chosenDevice = upnpDevicesAdapter.getChosenDevice();
      if (chosenDevice != null) {
        outState.putString(getString(R.string.key_selected_device), chosenDevice.getUUID());
      }
    }
  }

  // Add all legacy radio if any, returns true if success
  private boolean processLegacy() {
    final RadioLibrary radioLibrary = new RadioLibrary(this);
    radioLibrary.getAllRadioIds().forEach(radioId -> {
      final com.watea.radio_upnp.model.legacy.Radio legacyRadio = radioLibrary.getFrom(radioId);
      // Robustness: something went wrong?
      if (legacyRadio != null) {
        // Robustness: catch any exception
        try {
          radios.add(new Radio(
              legacyRadio.getName(),
              legacyRadio.getIcon(),
              legacyRadio.getURL(),
              legacyRadio.getWebPageURL()),
            false);
        } catch (Exception exception) {
          Log.e(LOG_TAG, "Internal failure; reading legacy radio: " + legacyRadio.getName());
        }
      }
    });
    return radios.write();
  }

  private void storeBooleanPreference(int key, boolean value) {
    sharedPreferences.edit().putBoolean(getString(key), value).apply();
  }

  private void sendLogcatMail() {
    // File is stored at root place for app
    final File logFile = new File(getFilesDir(), "logcat.txt");
    final String packageName = getPackageName();
    final String[] command =
      new String[]{"logcat", "-d", "-f", logFile.toString(), packageName + ":D"};
    try {
      Runtime.getRuntime().exec(command);
      // Prepare mail
      startActivity(getNewSendIntent()
        .putExtra(
          Intent.EXTRA_SUBJECT,
          "RadioUPnP report " + BuildConfig.VERSION_NAME + " / " + Calendar.getInstance().getTime())
        .putExtra(
          Intent.EXTRA_STREAM,
          FileProvider.getUriForFile(this, packageName + ".fileprovider", logFile)));
    } catch (Exception exception) {
      Log.e(LOG_TAG, "sendLogcatMail: internal failure", exception);
      tell(R.string.report_error);
    }
  }

  private void checkNavigationMenu(int id) {
    navigationMenuCheckedId = id;
    navigationMenu.findItem(navigationMenuCheckedId).setChecked(true);
  }

  private void dumpRadios() {
    final File dumpFile = new File(getExternalFilesDir(null), "radios.csv");
    final AlertDialog.Builder alertDialogBuilder =
      new AlertDialog.Builder(this, R.style.AlertDialogStyle)
        // Restore checked item
        .setOnDismissListener(dialogInterface -> checkNavigationMenu());
    try (Writer writer = new OutputStreamWriter(Files.newOutputStream(dumpFile.toPath()))) {
      writer.write(radios.export());
      alertDialogBuilder.setMessage(R.string.export_done);
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "dumpRadios: internal failure", iOException);
      alertDialogBuilder.setMessage(R.string.dump_error);
    }
    alertDialogBuilder.create().show();
  }

  @Nullable
  private Fragment getCurrentFragment() {
    return getSupportFragmentManager().findFragmentById(R.id.content_frame);
  }

  public interface Listener {
    default void onNewCurrentRadio(@Nullable Radio radio) {
    }
  }

  public class UserHint {
    private final AlertDialog alertDialog;
    private final int delay;
    private boolean gotIt;
    private int count = 0;

    public UserHint(int key, int message, int delay) {
      this.delay = delay;
      gotIt = sharedPreferences.getBoolean(getString(key), false);
      alertDialog = new AlertDialog.Builder(MainActivity.this, R.style.AlertDialogStyle)
        .setMessage(message)
        .setPositiveButton(
          R.string.action_got_it,
          (dialogInterface, i) ->
            sharedPreferences.edit().putBoolean(getString(key), gotIt = true).apply())
        .create();
    }

    public UserHint(int key, int message) {
      this(key, message, 0);
    }

    public void show() {
      if (!alertDialog.isShowing() && !gotIt && (++count > delay)) {
        alertDialog.show();
      }
    }
  }
}