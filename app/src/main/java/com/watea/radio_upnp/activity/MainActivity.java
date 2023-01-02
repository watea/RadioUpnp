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

import static com.watea.radio_upnp.adapter.UpnpPlayerAdapter.RENDERER_DEVICE_TYPE;

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
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.RadiosAdapter;
import com.watea.radio_upnp.adapter.UpnpDevicesAdapter;
import com.watea.radio_upnp.adapter.UpnpRegistryAdapter;
import com.watea.radio_upnp.cling.UpnpService;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;
import com.watea.radio_upnp.model.UpnpDevice;
import com.watea.radio_upnp.service.HttpService;
import com.watea.radio_upnp.service.NetworkProxy;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.message.header.DeviceTypeHeader;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.registry.Registry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URL;
import java.util.Calendar;
import java.util.Hashtable;
import java.util.Map;

public class MainActivity
  extends AppCompatActivity
  implements NavigationView.OnNavigationItemSelectedListener {
  private static final int SEARCH_TIMEOUT = 10;
  private static final int RADIO_ICON_SIZE = 300;
  private static final String LOG_TAG = MainActivity.class.getName();
  private static final Map<Class<? extends Fragment>, Integer> FRAGMENT_MENU_IDS =
    new Hashtable<Class<? extends Fragment>, Integer>() {
      {
        put(MainFragment.class, R.id.action_home);
        put(ItemAddFragment.class, R.id.action_add_item);
        put(ModifyFragment.class, R.id.action_modify);
        put(DonationFragment.class, R.id.action_donate);
      }
    };
  private static final DefaultRadio[] DEFAULT_RADIOS = {
    new DefaultRadio(
      "FRANCE INTER",
      R.drawable.logo_france_inter,
      "http://direct.franceinter.fr/live/franceinter-midfi.mp3",
      "https://www.franceinter.fr/"),
    new DefaultRadio(
      "FRANCE CULTURE",
      R.drawable.logo_france_culture,
      "http://direct.franceculture.fr/live/franceculture-midfi.mp3",
      "https://www.franceculture.fr/"),
    new DefaultRadio(
      "OUI FM",
      R.drawable.logo_oui_fm,
      "http://target-ad-2.cdn.dvmr.fr/ouifm-high.mp3",
      "https://www.ouifm.fr/"),
    new DefaultRadio(
      "EUROPE1",
      R.drawable.logo_europe1,
      "http://ais-live.cloud-services.paris:8000/europe1.mp3",
      "https://www.europe1.fr/"),
    new DefaultRadio(
      "RFM",
      R.drawable.logo_rfm,
      "http://ais-live.cloud-services.paris:8000/rfm.mp3",
      "http://www.rfm.fr/"),
    new DefaultRadio(
      "SKYROCK",
      R.drawable.logo_skyrock,
      "http://icecast.skyrock.net/s/natio_mp3_128k",
      "https://www.skyrock.com/"),
    new DefaultRadio(
      "VIRGIN",
      R.drawable.logo_virgin,
      "http://ais-live.cloud-services.paris:8000/virgin.mp3",
      "https://www.virginradio.fr/"),
    new DefaultRadio(
      "FUN",
      R.drawable.logo_fun,
      "http://icecast.funradio.fr/fun-1-44-128?listen=webCwsBCggNCQgLDQUGBAcGBg",
      "https://www.funradio.fr/"),
    new DefaultRadio(
      "RADIO PARADISE",
      R.drawable.logo_radio_paradise,
      "http://stream.radioparadise.com/flacm",
      "https://www.radioparadise.com/"),
    new DefaultRadio(
      "PBB",
      R.drawable.logo_pbb_radio,
      "https://pbbradio.com:8443/128",
      "https://www.allzicradio.com/en/player/listen/2579/pbb-laurent-garnier"),
    new DefaultRadio(
      "FIP",
      R.drawable.logo_fip,
      "http://icecast.radiofrance.fr/fip-hifi.aac",
      "https://www.fip.fr/"),
    new DefaultRadio(
      "DAVIDE",
      R.drawable.logo_davide,
      "https://streaming01.zfast.co.uk/proxy/davideof",
      "http://www.davideofmimic.com/")
  };
  private static final DeviceTypeHeader RENDERER_DEVICE_TYPE_HEADER =
    new DeviceTypeHeader(RENDERER_DEVICE_TYPE);
  private NetworkProxy networkProxy = null;
  // <HMI assets
  private DrawerLayout drawerLayout;
  private ActionBarDrawerToggle drawerToggle;
  private FloatingActionButton floatingActionButton;
  private Menu navigationMenu;
  private AlertDialog upnpAlertDialog;
  private AlertDialog aboutAlertDialog;
  private CollapsingToolbarLayout actionBarLayout;
  private PlayerController playerController;
  private ImportController importController;
  private RadioGardenController radioGardenController;
  // />
  private RadioLibrary radioLibrary = null;
  private boolean gotItRadioGarden = false;
  private int navigationMenuCheckedId;
  private AndroidUpnpService androidUpnpService = null;
  private UpnpRegistryAdapter upnpRegistryAdapter = null;
  private UpnpDevicesAdapter upnpDevicesAdapter = null;
  private HttpService.HttpServer httpServer = null;
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
      androidUpnpService = (AndroidUpnpService) service;
      // Init the service
      assert httpServer != null;
      ((UpnpService.Binder) service).init(httpServer);
      // So registry is defined
      final Registry registry = androidUpnpService.getRegistry();
      // Add local export device
      importController.addExportService(registry);
      // Define adapters
      upnpRegistryAdapter = new UpnpRegistryAdapter(upnpDevicesAdapter);
      // Add all devices to the list we already know about
      for (RemoteDevice remoteDevice : registry.getRemoteDevices()) {
        upnpRegistryAdapter.remoteDeviceAdded(registry, remoteDevice);
        importController.getRegistryListener().remoteDeviceAdded(registry, remoteDevice);
      }
      // Get ready for future device advertisements
      registry.addListener(upnpRegistryAdapter);
      registry.addListener(importController.getRegistryListener());
      // Now we can launch HTTP Server
      startService(new Intent(MainActivity.this, HttpService.class));
      // Ask for devices
      upnpSearch();
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
      // Robustness, shall be defined here
      if (androidUpnpService != null) {
        final Registry registry = androidUpnpService.getRegistry();
        if (upnpRegistryAdapter != null) {
          registry.removeListener(upnpRegistryAdapter);
          upnpRegistryAdapter = null;
        }
        registry.removeListener(importController.getRegistryListener());
        androidUpnpService = null;
      }
      upnpDevicesAdapter.onResetRemoteDevices();
    }
  };
  private final ServiceConnection httpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
      httpServer = (HttpService.HttpServer) iBinder;
      // Now we can bind to UPnP service
      if (!bindService(
        new Intent(MainActivity.this, UpnpService.class), upnpConnection, BIND_AUTO_CREATE)) {
        Log.e(LOG_TAG, "Internal failure; UpnpService not bound");
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName componentName) {
      assert httpServer != null;
      httpServer.stop();
      httpServer = null;
    }
  };
  private Intent newIntent = null;

  @NonNull
  public static Bitmap createScaledBitmap(@NonNull Bitmap bitmap) {
    return RadiosAdapter.createScaledBitmap(bitmap, RADIO_ICON_SIZE);
  }

  public static int getSmallIconSize() {
    return RADIO_ICON_SIZE / 2;
  }

  @Nullable
  public AndroidUpnpService getAndroidUpnpService() {
    return androidUpnpService;
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
        importController.showAlertDialog();
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

  @Nullable
  public RadioLibrary getRadioLibrary() {
    return radioLibrary;
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

  public boolean upnpSearch() {
    return upnpSearch(RENDERER_DEVICE_TYPE_HEADER);
  }

  public boolean upnpSearch(@NonNull DeviceTypeHeader deviceTypeHeader) {
    if (androidUpnpService == null) {
      tell(R.string.service_not_available);
      return false;
    }
    androidUpnpService.getControlPoint().search(deviceTypeHeader, SEARCH_TIMEOUT);
    return true;
  }

  public void onUpnpReset() {
    if (androidUpnpService == null) {
      tell(R.string.service_not_available);
    } else {
      androidUpnpService.getRegistry().removeAllRemoteDevices();
      upnpDevicesAdapter.onResetRemoteDevices();
      tell(R.string.dlna_search_reset);
    }
  }

  // radio is null for current
  public void startReading(@Nullable Radio radio) {
    final UpnpDevice chosenUpnpDevice = upnpDevicesAdapter.getChosenUpnpDevice();
    playerController.startReading(
      radio,
      ((androidUpnpService != null) &&
        (chosenUpnpDevice != null) &&
        networkProxy.hasWifiIpAddress()) ?
        chosenUpnpDevice.getIdentity() : null);
  }

  @NonNull
  public Fragment setFragment(@NonNull Class<? extends Fragment> fragmentClass) {
    final FragmentManager fragmentManager = getSupportFragmentManager();
    final String tag = fragmentClass.getSimpleName();
    Fragment fragment = fragmentManager.findFragmentByTag(tag);
    if (fragment == null) {
      try {
        fragment = fragmentClass.getConstructor().newInstance();
      } catch (Exception exception) {
        // Should not happen
        Log.e(LOG_TAG, "setFragment: internal failure", exception);
        throw new RuntimeException();
      }
    }
    final FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
    fragmentTransaction.replace(R.id.content_frame, fragment, tag);
    // First fragment transaction not saved to enable back leaving the app
    if (getCurrentFragment() != null) {
      fragmentTransaction.addToBackStack(null);
    }
    fragmentTransaction.commit();
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

  public boolean setRadioGardenGotIt() {
    return gotItRadioGarden = true;
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    final Fragment fragment = getCurrentFragment();
    if (fragment != null) {
      fragment.onActivityResult(requestCode, resultCode, data);
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    Log.d(LOG_TAG, "onPause");
    // Shared preferences
    getPreferences(Context.MODE_PRIVATE)
      .edit()
      .putBoolean(getString(R.string.key_radio_garden), gotItRadioGarden)
      .apply();
    // Stop UPnP service
    unbindService(upnpConnection);
    // Stop HTTP service
    unbindService(httpConnection);
    // Forced disconnection
    httpConnection.onServiceDisconnected(null);
    upnpConnection.onServiceDisconnected(null);
    // PlayerController call
    playerController.onActivityPause();
    // Close radios database
    radioLibrary.close();
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
    // Create radio database (order matters)
    radioLibrary = new RadioLibrary(this);
    // Create default radios on first start
    final SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
    gotItRadioGarden = sharedPreferences.getBoolean(getString(R.string.key_radio_garden), false);
    // Init database
    if (sharedPreferences.getBoolean(getString(R.string.key_first_start), true)) {
      setDefaultRadios();
      // Robustness: store immediately to avoid bad user experience in case of app crash
      sharedPreferences.edit().putBoolean(getString(R.string.key_first_start), false).apply();
    }
    // Bind to HTTP Service, connection will launch UPnP service
    if (!bindService(new Intent(this, HttpService.class), httpConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; HttpService not bound");
    }
    // PlayerController call
    playerController.onActivityResume(radioLibrary);
    // Radio Garden share?
    if (newIntent != null) {
      radioGardenController.onNewIntent(newIntent);
      newIntent = null;
    }
    Log.d(LOG_TAG, "onResume done!");
  }

  @Override
  @SuppressLint("InflateParams")
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Init connexion
    networkProxy = new NetworkProxy(this);
    // UPnP adapter (order matters)
    upnpDevicesAdapter = new UpnpDevicesAdapter(
      (savedInstanceState == null) ?
        null : savedInstanceState.getString(getString(R.string.key_selected_device)),
      (upnpDevice, isChosen) -> {
        if (isChosen) {
          startReading(null);
          tell(getResources().getString(R.string.dlna_selection) + upnpDevice);
        } else {
          tell(R.string.no_dlna_selection);
        }
        upnpAlertDialog.dismiss();
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
    importController = new ImportController(this);
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
        .setPositiveButton(R.string.action_go, (dialogInterface, i) -> setNotification())
        .create()
        .show();
    }
    // Specific UPnP devices dialog
    final RecyclerView upnpRecyclerView = new RecyclerView(this);
    upnpRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    upnpRecyclerView.setAdapter(upnpDevicesAdapter);
    upnpAlertDialog = new AlertDialog.Builder(this, R.style.AlertDialogStyle)
      .setView(upnpRecyclerView)
      .create();
    // FAB
    floatingActionButton = findViewById(R.id.floating_action_button);
    // Set fragment if context is not restored by Android
    if (savedInstanceState == null) {
      setFragment(MainFragment.class);
    }
    // Store intent
    newIntent = getIntent();
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
      UpnpDevice chosenUpnpDevice = upnpDevicesAdapter.getChosenUpnpDevice();
      if (chosenUpnpDevice != null) {
        outState.putString(getString(R.string.key_selected_device), chosenUpnpDevice.getIdentity());
      }
    }
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
      startActivity(new Intent(Intent.ACTION_SEND)
        .setType("message/rfc822")
        .putExtra(Intent.EXTRA_EMAIL, new String[]{"fr.watea@gmail.com"})
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
    try (Writer writer = new OutputStreamWriter(new FileOutputStream(dumpFile))) {
      writer.write(radioLibrary.marshall(true));
      alertDialogBuilder.setMessage(R.string.export_done);
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "dumpRadios: internal failure", iOException);
      alertDialogBuilder.setMessage(R.string.dump_error);
    }
    alertDialogBuilder.create().show();
  }

  private void setNotification() {
    final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
    startActivity(intent);
  }

  private void setDefaultRadios() {
    for (DefaultRadio defaultRadio : DEFAULT_RADIOS) {
      try {
        final Radio radio = new Radio(
          defaultRadio.name,
          new URL(defaultRadio.uRL),
          new URL(defaultRadio.webPageURL),
          false,
          resourceToBitmap(defaultRadio.drawable));
        if (!radioLibrary.add(radio)) {
          Log.e(LOG_TAG, "setDefaultRadios: internal failure on: " + radio.getName());
        }
      } catch (Exception exception) {
        Log.e(LOG_TAG, "setDefaultRadios: internal failure", exception);
      }
    }
  }

  @NonNull
  private Bitmap resourceToBitmap(int resource) {
    return createScaledBitmap(BitmapFactory.decodeResource(getResources(), resource));
  }

  @Nullable
  private Fragment getCurrentFragment() {
    return getSupportFragmentManager().findFragmentById(R.id.content_frame);
  }

  private static class DefaultRadio {
    @NonNull
    private final String name;
    private final int drawable;
    @NonNull
    private final String uRL;
    @NonNull
    private final String webPageURL;

    private DefaultRadio(
      @NonNull String name,
      int drawable,
      @NonNull String uRL,
      @NonNull String webPageURL) {
      this.name = name;
      this.drawable = drawable;
      this.uRL = uRL;
      this.webPageURL = webPageURL;
    }
  }
}