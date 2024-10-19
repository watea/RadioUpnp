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

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
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

import com.google.android.material.appbar.AppBarLayout;
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

import org.json.JSONException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity
  extends AppCompatActivity
  implements NavigationView.OnNavigationItemSelectedListener {
  private static final int RADIO_ICON_SIZE = 300;
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
  private static Radios radios = null;
  private final List<Listener> listeners = new ArrayList<>();
  private Radio currentRadio = null;
  private DrawerLayout drawerLayout;
  private ActionBarDrawerToggle drawerToggle;
  private FloatingActionButton floatingActionButton;
  private Menu navigationMenu;
  private AlertDialog upnpAlertDialog;
  private AlertDialog parametersAlertDialog;
  private AlertDialog aboutAlertDialog;
  private UserHint toolbarUserHint;
  private CollapsingToolbarLayout actionBarLayout;
  private AppBarLayout appBarLayout;
  private PlayerController playerController;
  private SharedPreferences sharedPreferences;
  private RadioGardenController radioGardenController;
  private boolean isToolbarExpanded = true;
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
  private ActivityResultLauncher<Intent> importExportLauncher;
  private ImportExportAction importExportAction;
  private String selectedDeviceIdentity;
  private final UpnpDevicesAdapter.Listener upnpDevicesAdapterListener =
    new UpnpDevicesAdapter.Listener() {
      @Override
      public void onRowClick(@NonNull Device device, boolean isSelected) {
        if (isSelected) {
          startReading(null);
          tell(getResources().getString(R.string.dlna_selection) + device.getDisplayString());
        } else {
          tell(R.string.no_dlna_selection);
        }
        upnpAlertDialog.dismiss();
      }

      @Override
      public void onSelectedDeviceChange(@Nullable String deviceIdentity, @Nullable Bitmap icon) {
        selectedDeviceIdentity = deviceIdentity;
        listeners.forEach(listener -> listener.onChosenDeviceChange(icon));
      }
    };

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

  public static void setNotification(@NonNull Context context, @NonNull String packageName) {
    final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
    context.startActivity(intent);
  }

  @NonNull
  public SharedPreferences getSharedPreferences() {
    return sharedPreferences;
  }

  @Nullable
  public Radio getCurrentRadio() {
    return currentRadio;
  }

  public void setCurrentRadio(@Nullable String radioId) {
    currentRadio = (radioId == null) ? null : radios.getRadioFrom(radioId);
    listeners.forEach(listener -> listener.onNewCurrentRadio(currentRadio));
  }

  public boolean isCurrentRadio(@NonNull Radio radio) {
    return (currentRadio == radio);
  }

  public void addListener(@NonNull Listener listener) {
    listeners.add(listener);
  }

  public void removeListener(@NonNull Listener listener) {
    listeners.remove(listener);
  }

  @NonNull
  public Bitmap getDefaultIcon() {
    return BitmapFactory.decodeResource(getResources(), R.drawable.ic_radio_gray);
  }

  public void removeChosenUpnpDevice() {
    upnpDevicesAdapter.removeSelectedUpnpDevice();
  }

  @Nullable
  public Bitmap getChosenUpnpDeviceIcon() {
    return upnpDevicesAdapter.getSelectedUpnpDeviceIcon();
  }

  // With animation
  public void setToolbarExpanded(boolean isExpanded) {
    appBarLayout.setExpanded(isExpanded, true);
  }

  @SuppressLint("NonConstantResourceId")
  @Override
  public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
    final Integer id = menuItem.getItemId();
    // Note: switch not to use as id not final
    switch (id) {
      case R.id.action_radio_garden:
        radioGardenController.launch(false);
        break;
      case R.id.action_export:
        exportFile();
        break;
      case R.id.action_import:
        importFile();
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

  // radio is null for current
  public void startReading(@Nullable Radio radio) {
    playerController.startReading(
      radio,
      ((upnpService != null) &&
        (selectedDeviceIdentity != null) &&
        networkProxy.hasWifiIpAddress()) ?
        selectedDeviceIdentity : null);
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

  public void onUpnp() {
    upnpAlertDialog.show();
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
    // Inflate view
    setContentView(R.layout.activity_main);
    drawerLayout = findViewById(R.id.main_activity);
    // ActionBar
    setSupportActionBar(findViewById(R.id.actionbar));
    actionBarLayout = findViewById(R.id.actionbar_layout);
    playerController = new PlayerController(this, actionBarLayout);
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    // AppBarLayout and Toolbar
    toolbarUserHint = new UserHint(R.string.key_toolbar_got_it, R.string.toolbar_press, 50);
    (appBarLayout = findViewById(R.id.appbar_layout)).addOnOffsetChangedListener(
      (localAppBarLayout, verticalOffset) -> {
        isToolbarExpanded = (verticalOffset == 0);
        toolbarUserHint.show();
      });
    // Toggle expansion state, animate the transition
    findViewById(R.id.actionbar).setOnClickListener(v -> setToolbarExpanded(!isToolbarExpanded));
    // Radio Garden
    radioGardenController = new RadioGardenController(this);
    // Import/export function
    importExportLauncher = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(),
      result -> {
        if (result.getResultCode() == RESULT_OK) {
          final Intent data = result.getData();
          if (data != null && data.getData() != null) {
            final Uri uri = result.getData().getData();
            if (uri != null) {
              switch (importExportAction) {
                case CSV_EXPORT:
                  exportTo(uri, Radios.MIME_CSV);
                  break;
                case CSV_IMPORT:
                  // Not implemented; shall not happen
                  Log.e(LOG_TAG, "Internal failure; .csv import is not implemented");
                  break;
                case JSON_EXPORT:
                  exportTo(uri, Radios.MIME_JSON);
                  break;
                case JSON_IMPORT:
                  importFrom(uri);
              }
            }
          }
        }
      });
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
    selectedDeviceIdentity = (savedInstanceState == null) ?
      null : savedInstanceState.getString(getString(R.string.key_selected_device));
    upnpDevicesAdapter = new UpnpDevicesAdapter(
      getThemeAttributeColor(android.R.attr.textColorHighlight),
      contentUpnp.findViewById(R.id.devices_default_linear_layout),
      upnpDevicesAdapterListener,
      selectedDeviceIdentity);
    devicesRecyclerView.setAdapter(upnpDevicesAdapter);
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
        recreate();
      }
    });
    int radioButtonId;
    switch (theme) {
      case DARK:
        radioButtonId = R.id.dark_radio_button;
        break;
      case LIGHT:
        radioButtonId = R.id.light_radio_button;
        break;
      default:
        radioButtonId = R.id.system_radio_button;
    }
    ((RadioButton) parametersView.findViewById(radioButtonId)).setChecked(true);
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
    sharedPreferences.edit().putString(getString(string.key_theme), theme.toString()).apply();
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
    if (selectedDeviceIdentity != null) {
      outState.putString(getString(R.string.key_selected_device), selectedDeviceIdentity);
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

  private void exportFile() {
    final android.content.DialogInterface.OnClickListener listener =
      (dialog, which) -> {
        importExportLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE));
        importExportAction = (which == DialogInterface.BUTTON_NEUTRAL) ?
          ImportExportAction.CSV_EXPORT : ImportExportAction.JSON_EXPORT;
      };
    new AlertDialog.Builder(this)
      .setTitle(R.string.title_import)
      .setIcon(R.drawable.ic_baseline_exit_to_app_white_24dp)
      .setMessage(R.string.export_message)
      .setNeutralButton(R.string.action_csv_export, listener)
      .setPositiveButton(R.string.action_json_export, listener)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> this.checkNavigationMenu())
      .create()
      .show();
  }

  // Only JSON supported
  private void importFile() {
    final android.content.DialogInterface.OnClickListener listener =
      (dialog, which) -> {
        importExportLauncher.launch(new Intent(Intent.ACTION_OPEN_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE)
          .setType(Radios.MIME_JSON));
        importExportAction = ImportExportAction.JSON_IMPORT;
      };
    new AlertDialog.Builder(this)
      .setTitle(R.string.title_import)
      .setIcon(R.drawable.ic_baseline_exit_to_app_white_24dp)
      .setMessage(R.string.import_message)
      .setPositiveButton(R.string.action_go, listener)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> this.checkNavigationMenu())
      .create()
      .show();
  }

  private void exportTo(@NonNull Uri treeUri, @NonNull String type) {
    try {
      // Create the file using the tree URI
      final Uri fileUri = createFileInTree(treeUri, getString(R.string.app_name), type);
      // Write the data to the file
      if (fileUri == null) {
        Log.e(LOG_TAG, "exportTo: internal failure, file not created");
        tell(R.string.export_failed_not_created);
        return;
      }
      try (final OutputStream outputStream = getContentResolver().openOutputStream(fileUri)) {
        if (outputStream == null) {
          Log.e(LOG_TAG, "exportTo: internal failure, file not created");
          tell(R.string.export_failed);
        } else {
          radios.write(outputStream, type);
        }
      }
      tell(getString(R.string.export_done) + getString(R.string.app_name));
    } catch (JSONException | IOException iOException) {
      Log.e(LOG_TAG, "exportTo: internal failure", iOException);
      tell(R.string.export_failed);
    }
  }

  @Nullable
  private Uri createFileInTree(
    @NonNull Uri treeUri,
    @NonNull String fileName,
    @NonNull String type) throws FileNotFoundException {
    // Get the document ID of the tree URI
    final String docId = DocumentsContract.getTreeDocumentId(treeUri);
    // Build the document URI for the file
    final Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
    // Create the file using MediaStore API
    final ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
    values.put(MediaStore.MediaColumns.MIME_TYPE, type);
    // Important: use the document URI as the parent for the new file
    return DocumentsContract.createDocument(getContentResolver(), docUri, type, fileName);
  }

  // Only JSON supported
  private void importFrom(@NonNull Uri uri) {
    try (final InputStream inputStream = getContentResolver().openInputStream(uri)) {
      if (inputStream == null) {
        Log.e(LOG_TAG, "importFrom: internal failure");
      } else {
        tell(radios.read(inputStream) ? R.string.import_successful : R.string.import_no_data);
        return;
      }
    } catch (IOException iOException) {
      Log.e(LOG_TAG, "importFrom: I/O failure", iOException);
    } catch (JSONException jSONException) {
      Log.e(LOG_TAG, "importFrom: JSON failure", jSONException);
    }
    tell(R.string.import_failed);
  }

  @Nullable
  private Fragment getCurrentFragment() {
    return getSupportFragmentManager().findFragmentById(R.id.content_frame);
  }

  private enum Theme {
    SYSTEM, DARK, LIGHT
  }

  private enum ImportExportAction {
    JSON_IMPORT, CSV_IMPORT, JSON_EXPORT, CSV_EXPORT
  }

  public interface Listener {
    default void onNewCurrentRadio(@Nullable Radio radio) {
    }

    default void onChosenDeviceChange(@Nullable Bitmap icon) {
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
      count = (count < delay) ? count + 1 : count;
      if (!alertDialog.isShowing() && !gotIt && (count >= delay)) {
        alertDialog.show();
      }
    }
  }
}