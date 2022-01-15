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
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.UpnpRegistryAdapter;
import com.watea.radio_upnp.model.DlnaDevice;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;
import com.watea.radio_upnp.service.NetworkProxy;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceImpl;
import org.fourthline.cling.model.message.header.DeviceTypeHeader;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.registry.Registry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

public class MainActivity
  extends AppCompatActivity
  implements NavigationView.OnNavigationItemSelectedListener {
  public static final int RADIO_ICON_SIZE = 300;
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
  private static final List<Class<? extends Fragment>> ALWAYS_NEW_FRAGMENTS =
    Arrays.asList(
      ItemAddFragment.class,
      ItemModifyFragment.class);
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
  private final NetworkProxy networkProxy = new NetworkProxy(this);
  // <HMI assets
  private DrawerLayout drawerLayout;
  private ActionBarDrawerToggle drawerToggle;
  private FloatingActionButton floatingActionButton;
  private Menu navigationMenu;
  private AlertDialog aboutAlertDialog;
  private CollapsingToolbarLayout actionBarLayout;
  private PlayerController playerController;
  // />
  private RadioLibrary radioLibrary;
  private int navigationMenuCheckedId;
  private MainFragment mainFragment;
  private AndroidUpnpService androidUpnpService = null;
  private UpnpRegistryAdapter upnpRegistryAdapter = null;
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    @Override
    public void onServiceConnected(ComponentName className, IBinder service) {
      androidUpnpService = (AndroidUpnpService) service;
      // Do nothing if we were disposed
      if (mainFragment != null) {
        Registry registry = androidUpnpService.getRegistry();
        upnpRegistryAdapter = new UpnpRegistryAdapter(mainFragment);
        // Add all devices to the list we already know about
        for (RemoteDevice remoteDevice : registry.getRemoteDevices()) {
          upnpRegistryAdapter.remoteDeviceAdded(registry, remoteDevice);
        }
        // Get ready for future device advertisements
        registry.addListener(upnpRegistryAdapter);
        // Ask for devices
        upnpSearch();
      }
    }

    @Override
    public void onServiceDisconnected(ComponentName className) {
      // Robustness, shall be defined here
      if (androidUpnpService != null) {
        if (upnpRegistryAdapter != null) {
          androidUpnpService.getRegistry().removeListener(upnpRegistryAdapter);
          upnpRegistryAdapter = null;
        }
        androidUpnpService = null;
      }
      // Tell MainFragment
      if (mainFragment != null) {
        mainFragment.onResetRemoteDevices();
      }
    }
  };

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    MainActivityFragment currentFragment = (MainActivityFragment) getCurrentFragment();
    if (currentFragment == null) {
      Log.e(LOG_TAG, "onCreateOptionsMenu: currentFragment not defined");
    } else {
      int menuId = currentFragment.getMenuId();
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

  @SuppressLint("NonConstantResourceId")
  @Override
  public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
    Integer id = menuItem.getItemId();
    // Note: switch not to use as id not final
    switch (id) {
      case R.id.action_about:
        aboutAlertDialog.show();
        break;
      case R.id.action_log:
        sendLogcatMail();
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

  @NonNull
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
    Integer menuId = FRAGMENT_MENU_IDS.get(mainActivityFragment.getClass());
    // Change checked menu if necessary
    if (menuId != null) {
      checkNavigationMenu(menuId);
    }
  }

  public void tell(int message) {
    Snackbar.make(getWindow().getDecorView().getRootView(), message, Snackbar.LENGTH_LONG).show();
  }

  public void tell(@NonNull String message) {
    Snackbar.make(getWindow().getDecorView().getRootView(), message, Snackbar.LENGTH_LONG).show();
  }

  // Search for Renderer devices, 10s timeout
  public boolean upnpSearch() {
    if (androidUpnpService == null) {
      tell(R.string.device_no_device_yet);
      return false;
    }
    androidUpnpService.getControlPoint().search(new DeviceTypeHeader(RENDERER_DEVICE_TYPE), 10);
    return true;
  }

  public boolean upnpReset() {
    if (androidUpnpService == null) {
      tell(R.string.device_no_device_yet);
      return false;
    }
    androidUpnpService.getRegistry().removeAllRemoteDevices();
    return true;
  }

  // radio is null for current
  public void startReading(@Nullable Radio radio) {
    DlnaDevice chosenDlnaDevice = mainFragment.getChosenDlnaDevice();
    playerController.startReading(
      radio,
      ((androidUpnpService != null) &&
        (chosenDlnaDevice != null) &&
        networkProxy.hasWifiIpAddress()) ?
        chosenDlnaDevice.getIdentity() : null);
  }

  @NonNull
  public Fragment setFragment(@NonNull Class<? extends Fragment> fragmentClass) {
    Fragment fragment;
    String tag = fragmentClass.getSimpleName();
    if (ALWAYS_NEW_FRAGMENTS.contains(fragmentClass) ||
      ((fragment = getSupportFragmentManager().findFragmentByTag(tag)) == null)) {
      try {
        fragment = fragmentClass.getConstructor().newInstance();
      } catch (Exception exception) {
        // Should not happen
        Log.e(LOG_TAG, "setFragment: internal failure", exception);
        throw new RuntimeException();
      }
    }
    FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
    fragmentTransaction.replace(R.id.content_frame, fragment, tag);
    // First fragment transaction not saved to enable back leaving the app
    if (getCurrentFragment() != null) {
      fragmentTransaction.addToBackStack(null);
    }
    fragmentTransaction.commit();
    return fragment;
  }

  @Nullable
  public Radio getCurrentRadio() {
    return playerController.getCurrentRadio();
  }

  public void sendLogcatMail() {
    // File is stored at root
    File logFile = new File(getFilesDir(), "logcat.txt");
    // Write log filtered on PID
    String pid = String.valueOf(android.os.Process.myPid());
    try (FileWriter out = new FileWriter(logFile)) {
      Process process = Runtime.getRuntime().exec("logcat -d");
      BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
      String currentLine;
      while ((currentLine = reader.readLine()) != null) {
        if (currentLine.contains(pid)) {
          out.write(currentLine + "\n");
        }
      }
      // Prepare mail
      startActivity(new Intent(Intent.ACTION_SEND)
        .setType("message/rfc822")
        .putExtra(Intent.EXTRA_EMAIL, new String[]{"fr.watea@gmail.com"})
        .putExtra(
          Intent.EXTRA_SUBJECT,
          "RadioUPnP report " + BuildConfig.VERSION_NAME + " / " + Calendar.getInstance().getTime())
        .putExtra(
          Intent.EXTRA_STREAM,
          FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", logFile)));
    } catch (Exception exception) {
      Log.e(LOG_TAG, "SendLogcatMail: internal failure", exception);
      tell(R.string.report_error);
    }
  }

  @NonNull
  public NetworkProxy getNetworkProxy() {
    return networkProxy;
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    // Must be done for Donation
    Fragment fragment = getCurrentFragment();
    if (fragment != null) {
      fragment.onActivityResult(requestCode, resultCode, data);
    }
  }

  @Override
  protected void onPause() {
    super.onPause();
    // Stop UPnP service
    unbindService(upnpConnection);
    // Forced disconnection
    upnpConnection.onServiceDisconnected(null);
    // PlayerController call
    playerController.onActivityPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    // Retrieve main fragment
    mainFragment = (MainFragment) ((getCurrentFragment() == null) ?
      setFragment(MainFragment.class) :
      // Shall exists as MainFragment always created
      getSupportFragmentManager().findFragmentByTag(MainFragment.class.getSimpleName()));
    // Start the UPnP service
    if (!bindService(
      new Intent(this, AndroidUpnpServiceImpl.class),
      upnpConnection,
      BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "onActivityResume: internal failure; AndroidUpnpService not bound");
    }
    // PlayerController call
    playerController.onActivityResume();
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Create radio database (order matters)
    radioLibrary = new RadioLibrary(this);
    // Shared preferences
    SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
    // Create default radios on first start
    if (sharedPreferences.getBoolean(getString(R.string.key_first_start), true) &&
      setDefaultRadios()) {
      // To do just one time, store a flag
      sharedPreferences
        .edit()
        .putBoolean(getString(R.string.key_first_start), false)
        .apply();
    }
    // Inflate view
    setContentView(R.layout.activity_main);
    drawerLayout = findViewById(R.id.main_activity);
    // ActionBar
    setSupportActionBar(findViewById(R.id.actionbar));
    actionBarLayout = findViewById(R.id.actionbar_layout);
    playerController = new PlayerController(this);
    playerController.onActivityCreated(actionBarLayout);
    ActionBar actionBar = getSupportActionBar();
    if (actionBar == null) {
      // Should not happen
      Log.e(LOG_TAG, "onCreate: ActionBar is null");
    } else {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setDisplayShowHomeEnabled(true);
    }
    // Set navigation drawer toggle (according to documentation)
    drawerToggle = new ActionBarDrawerToggle(
      this, drawerLayout, R.string.drawer_open, R.string.drawer_close);
    // Set the drawer toggle as the DrawerListener
    drawerLayout.addDrawerListener(drawerToggle);
    // Navigation drawer
    NavigationView navigationView = findViewById(R.id.navigation_view);
    navigationView.setNavigationItemSelectedListener(this);
    navigationMenu = navigationView.getMenu();
    // Build alert about dialog
    @SuppressLint("InflateParams")
    View aboutView = getLayoutInflater().inflate(R.layout.view_about, null);
    ((TextView) aboutView.findViewById(R.id.version_name_text_view))
      .setText(BuildConfig.VERSION_NAME);
    aboutAlertDialog = new AlertDialog.Builder(this, R.style.AlertDialogStyle)
      .setView(aboutView)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> checkNavigationMenu(navigationMenuCheckedId))
      .create();
    // FAB
    floatingActionButton = findViewById(R.id.floating_action_button);
    // Fragments
    MainActivityFragment.onActivityCreated(this);
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
  protected void onDestroy() {
    super.onDestroy();
    // Close radios database
    radioLibrary.close();
  }

  private boolean setDefaultRadios() {
    boolean result = false;
    for (DefaultRadio defaultRadio : DEFAULT_RADIOS) {
      try {
        result = (radioLibrary.insertAndSaveIcon(
          new Radio(defaultRadio.name, new URL(defaultRadio.uRL), new URL(defaultRadio.webPageURL)),
          radioLibrary.resourceToBitmap(defaultRadio.drawable)) >= 0) || result;
      } catch (Exception exception) {
        Log.e(LOG_TAG, "setDefaultRadios: internal failure", exception);
      }
    }
    return result;
  }

  @Nullable
  private Fragment getCurrentFragment() {
    return getSupportFragmentManager().findFragmentById(R.id.content_frame);
  }

  private void checkNavigationMenu(int id) {
    navigationMenuCheckedId = id;
    navigationMenu.findItem(navigationMenuCheckedId).setChecked(true);
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