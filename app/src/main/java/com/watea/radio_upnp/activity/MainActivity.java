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

import static com.watea.radio_upnp.R.id;
import static com.watea.radio_upnp.R.string;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
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
import com.watea.radio_upnp.service.NetworkProxy;
import com.watea.radio_upnp.upnp.AndroidUpnpService;
import com.watea.radio_upnp.upnp.Device;

import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity
  extends AppCompatActivity
  implements NavigationView.OnNavigationItemSelectedListener {
  private static final int RADIO_ICON_SIZE = 300;
  private static final int CSV_EXPORT_PERMISSION_REQUEST_CODE = 1;
  private static final int JSON_EXPORT_PERMISSION_REQUEST_CODE = 2;
  private static final int JSON_IMPORT_PERMISSION_REQUEST_CODE = 3;
  private static final String CSV = "csv";
  private static final String MIME_CSV = "text/csv";
  private static final String JSON = "json";
  private static final String MIME_JSON = "application/json";
  private static final String LOG_TAG = MainActivity.class.getSimpleName();
  private static final Map<Class<? extends Fragment>, Integer> FRAGMENT_MENU_IDS =
    new HashMap<Class<? extends Fragment>, Integer>() {
      {
        put(MainFragment.class, R.id.action_home);
        put(SearchFragment.class, R.id.action_search);
        put(ItemAddFragment.class, R.id.action_add_item);
        put(ModifyFragment.class, R.id.action_modify);
        put(DonationFragment.class, R.id.action_donate);
      }
    };
  private static final List<Listener> listeners = new ArrayList<>();
  private static Radios radios = null;
  private static Radio currentRadio = null;
  private DrawerLayout drawerLayout;
  private ActionBarDrawerToggle drawerToggle;
  private FloatingActionButton floatingActionButton;
  private Menu navigationMenu;
  private AlertDialog upnpAlertDialog;
  private AlertDialog parametersAlertDialog;
  private View devicesDefaultView;
  private AlertDialog aboutAlertDialog;
  private CollapsingToolbarLayout actionBarLayout;
  private PlayerController playerController;
  private SharedPreferences sharedPreferences;
  private RadioGardenController radioGardenController;
  private boolean gotItRadioGarden = false;
  private int navigationMenuCheckedId;
  private Theme theme = Theme.DARK;
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
  private ActivityResultLauncher<Intent> openDocumentLauncher;

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
        if (noRequestPermission(
          Manifest.permission.READ_EXTERNAL_STORAGE, JSON_EXPORT_PERMISSION_REQUEST_CODE)) {
          importJson();
        }
        break;
      case R.id.action_export:
        if (noRequestPermission(
          Manifest.permission.WRITE_EXTERNAL_STORAGE, JSON_EXPORT_PERMISSION_REQUEST_CODE)) {
          exportJson();
        }
        break;
      case R.id.action_export_csv:
        if (noRequestPermission(
          Manifest.permission.WRITE_EXTERNAL_STORAGE, CSV_EXPORT_PERMISSION_REQUEST_CODE)) {
          exportCsv();
        }
        break;
      case R.id.action_parameters:
        parametersAlertDialog.show();
        break;
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
    tell(Snackbar.make(getWindow().getDecorView().getRootView(), message, Snackbar.LENGTH_LONG));
  }

  public void tell(@NonNull String message) {
    tell(Snackbar.make(getWindow().getDecorView().getRootView(), message, Snackbar.LENGTH_LONG));
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
        // If we got here, the user's action was not recognized.
        // Invoke the superclass to handle it.
        super.onOptionsItemSelected(item);
  }

  /**
   * @noinspection resource, SameParameterValue
   */
  public int getThemeAttributeColor(int attr) {
    final int[] attrs = {attr};
    final TypedArray typedArray = obtainStyledAttributes(attrs);
    final int result = typedArray.getColor(0, 0); // Get the color value
    typedArray.recycle(); // Always recycle TypedArray
    return result;
  }

  // Is called also when coming back after a "Back" exit
  @Override
  @SuppressLint({"InflateParams", "NonConstantResourceId"})
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
    }
    // Theme
    theme = Theme.valueOf(sharedPreferences.getString(getString(R.string.key_theme), theme.toString()));
    setTheme(getCurrentTheme());
    // Init connexion
    networkProxy = new NetworkProxy(this);
    // UPnP adapter (order matters)
    upnpDevicesAdapter = new UpnpDevicesAdapter(
      getThemeAttributeColor(android.R.attr.textColorHighlight),
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
    openDocumentLauncher = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(),
      result -> {
        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
          final Uri uri = result.getData().getData();
          if (uri != null) {
            importJsonFrom(result.getData().getData());
          }
        }
      }
    );
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
    aboutAlertDialog = new AlertDialog.Builder(this)
      .setView(aboutView)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> checkNavigationMenu())
      .create();
    // Check notification
    if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
      new AlertDialog.Builder(this)
        .setMessage(R.string.notification_needed)
        .setPositiveButton(
          R.string.action_go,
          (dialogInterface, i) -> setNotification(this, getPackageName()))
        .create()
        .show();
    }
    // Specific UPnP devices dialog
    final View contentUpnp = getLayoutInflater().inflate(R.layout.content_upnp, null);
    final RecyclerView devicesRecyclerView = contentUpnp.findViewById(R.id.devices_recycler_view);
    devicesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    devicesRecyclerView.setAdapter(upnpDevicesAdapter);
    devicesDefaultView = contentUpnp.findViewById(R.id.devices_default_linear_layout);
    upnpAlertDialog = new AlertDialog.Builder(this)
      .setView(contentUpnp)
      .create();
    // Parameters dialog
    final View parametersView = getLayoutInflater().inflate(R.layout.view_parameters, null);
    final RadioGroup themeRadioGroup = parametersView.findViewById(R.id.theme_radio_group);
    themeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
      final Theme previousTheme = theme;
      switch (group.getCheckedRadioButtonId()) {
        case R.id.dark_radio_button:
          theme = Theme.DARK;
          break;
        case id.light_radio_button:
          theme = Theme.LIGHT;
          break;
        default:
          theme = Theme.SYSTEM;
      }
      if (theme != previousTheme) {
        sharedPreferences
          .edit()
          .putString(getString(string.key_theme), theme.toString())
          .apply();
        recreate();
      }
    });
    RadioButton radioButton;
    switch (theme) {
      case DARK:
        radioButton = parametersView.findViewById(id.dark_radio_button);
        break;
      case LIGHT:
        radioButton = parametersView.findViewById(id.light_radio_button);
        break;
      default:
        radioButton = parametersView.findViewById(id.system_radio_button);
    }
    radioButton.setChecked(true);
    parametersAlertDialog = new AlertDialog.Builder(this)
      .setView(parametersView)
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
  }

  @Override
  protected void onPause() {
    super.onPause();
    Log.d(LOG_TAG, "onPause");
    // Shared preferences
    sharedPreferences
      .edit()
      .putBoolean(getString(R.string.key_radio_garden_got_it), gotItRadioGarden)
      .apply();
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
  public void onRequestPermissionsResult(
    int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      switch (requestCode) {
        case JSON_EXPORT_PERMISSION_REQUEST_CODE:
          exportJson();
          break;
        case CSV_EXPORT_PERMISSION_REQUEST_CODE:
          exportCsv();
          break;
        case JSON_IMPORT_PERMISSION_REQUEST_CODE:
          importJson();
          break;
        default:
          Log.e(LOG_TAG, "Internal failure; unknown permission result");
      }
    }
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

  // Customize snackbar for both dark and light theme
  private void tell(@NonNull Snackbar snackbar) {
    snackbar.setTextColor(getThemeAttributeColor(R.attr.colorAccent)).show();
  }

  @SuppressLint("DiscouragedApi")
  private int getCurrentTheme() {
    // Check the actual system setting
    final int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    return getResources().getIdentifier(
      (((theme == Theme.SYSTEM) && (uiMode == Configuration.UI_MODE_NIGHT_NO)) ||
        (theme == Theme.LIGHT)) ?
        "AppTheme.Light" : "AppTheme.Dark",
      "style",
      getPackageName());
  }

  private boolean noRequestPermission(@NonNull String permission, int requestCode) {
    if ((Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) &&
      (ContextCompat.checkSelfPermission(this, permission)
        != PackageManager.PERMISSION_GRANTED)) {
      ActivityCompat.requestPermissions(this, new String[]{permission}, requestCode);
      return false;
    }
    return true;
  }

  @RequiresApi(api = Build.VERSION_CODES.Q)
  @Nullable
  private Uri insertContentResolverUri(@NonNull String fileName, @NonNull String mimeType) {
    final ContentValues values = new ContentValues();
    values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
    values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
    return getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
  }

  // Returns name of created file
  private String copyFileToDownloads(
    @NonNull byte[] input,
    @NonNull String fileType,
    @NonNull String mimeType) throws IOException {
    final String fileName = getString(R.string.app_name) + "." + fileType;
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) {
      final Uri uri = insertContentResolverUri(fileName, mimeType);
      if (uri == null) {
        Log.e(LOG_TAG, "copyFileToDownloads: unable to find uri");
      } else {
        try (FileOutputStream fileOutputStream =
               (FileOutputStream) getContentResolver().openOutputStream(uri)) {
          assert fileOutputStream != null;
          fileOutputStream.write(input);
        }
      }
    } else {
      final File destinationDir =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
      final File destinationFile = new File(destinationDir, fileName);
      if (destinationDir.exists() || destinationDir.mkdirs()) {
        try (FileOutputStream fileOutputStream = new FileOutputStream(destinationFile)) {
          fileOutputStream.write(input);
        }
      } else {
        Log.e(LOG_TAG, "copyFileToDownloads: unable to find directory");
      }
    }
    return fileName;
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

  private void export(
    @NonNull byte[] input,
    @NonNull String fileType,
    @NonNull String mimeType) {
    final AlertDialog.Builder alertDialogBuilder =
      new AlertDialog.Builder(this)
        // Restore checked item
        .setOnDismissListener(dialogInterface -> checkNavigationMenu());
    try {
      alertDialogBuilder.setMessage(
        getString(R.string.export_done) + copyFileToDownloads(input, fileType, mimeType));
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "export: internal failure", iOException);
      alertDialogBuilder.setMessage(R.string.dump_error);
    }
    alertDialogBuilder.create().show();
  }

  private void exportCsv() {
    export(radios.export().getBytes(), CSV, MIME_CSV);
  }

  private void exportJson() {
    export(radios.toString().getBytes(), JSON, MIME_JSON);
  }

  private void importJsonFrom(@NonNull Uri uri) {
    try (InputStream inputStream = getContentResolver().openInputStream(uri);
         BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
      final StringBuilder stringBuilder = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        stringBuilder.append(line);
      }
      tell(radios.addFrom(new JSONArray(stringBuilder.toString())) ?
        R.string.import_successful : R.string.import_no_data);
    } catch (Exception exception) {
      Log.e(LOG_TAG, "importJsonFrom: exception", exception);
      tell(R.string.import_failed);
    }
  }

  private void importJson() {
    new AlertDialog.Builder(this)
      .setTitle(R.string.title_import)
      .setIcon(R.drawable.ic_baseline_exit_to_app_white_24dp)
      .setMessage(R.string.import_message)
      .setPositiveButton(R.string.action_go, (dialog, which) ->
        openDocumentLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE)
          .setType("*/*")))
      // Restore checked item
      .setOnDismissListener(dialogInterface -> this.checkNavigationMenu())
      .create()
      .show();
  }

  @Nullable
  private Fragment getCurrentFragment() {
    return getSupportFragmentManager().findFragmentById(R.id.content_frame);
  }

  private enum Theme {
    SYSTEM, DARK, LIGHT
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
      alertDialog = new AlertDialog.Builder(MainActivity.this)
        .setMessage(message)
        .setPositiveButton(
          R.string.action_got_it,
          (dialogInterface, i) ->
            sharedPreferences.edit().putBoolean(getString(key), (gotIt = true)).apply())
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