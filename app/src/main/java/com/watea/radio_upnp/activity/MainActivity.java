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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

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
import androidx.fragment.app.FragmentTransaction;
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
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class MainActivity
  extends AppCompatActivity
  implements NavigationView.OnNavigationItemSelectedListener {
  public static final int SLEEP_MIN = 5;
  private static final String LOG_TAG = MainActivity.class.getSimpleName();
  private static final int SLEEP_MAX = 90;
  private static final Map<Class<? extends Fragment>, Integer> FRAGMENT_MENU_IDS =
    new HashMap<>() {
      {
        put(MainFragment.class, R.id.action_home);
        put(SearchFragment.class, R.id.action_search);
        put(ItemAddFragment.class, R.id.action_add_item);
        put(ModifyFragment.class, R.id.action_modify);
        put(DonationFragment.class, R.id.action_donate);
      }
    };
  private DrawerLayout drawerLayout;
  private ActionBarDrawerToggle drawerToggle;
  private FloatingActionButton floatingActionButton;
  private Menu navigationMenu;
  private AlertDialog upnpAlertDialog;
  private AlertDialog parametersAlertDialog;
  private AlertDialog aboutAlertDialog;
  private AlertDialog sleepAlertDialog;
  private UserHint toolbarUserHint;
  private CollapsingToolbarLayout actionBarLayout;
  private AppBarLayout appBarLayout;
  private PlayerController playerController;
  private SharedPreferences sharedPreferences;
  private RadioGardenController radioGardenController;
  private AlarmController alarmController;
  private boolean isToolbarExpanded = true;
  private int navigationMenuCheckedId;
  private Theme theme = Theme.DARK;
  @Nullable
  private AndroidUpnpService.UpnpService upnpService = null;
  private UpnpDevicesAdapter upnpDevicesAdapter;
  private Intent newIntent;
  private NetworkProxy networkProxy;
  private ActivityResultLauncher<Intent> importExportLauncher;
  @Nullable
  private ImportExportAction importExportAction = null;
  private String savedSelectedDeviceIdentity;
  private Consumer<Bitmap> upnpIconConsumer = null;
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    private final AndroidUpnpService.Listener upnpListener = new AndroidUpnpService.Listener() {
      private void consumeIcon(@Nullable Device device) {
        if (upnpService.getSelectedDevice() == device) {
          runOnUiThread(() -> {
            if (upnpIconConsumer != null) {
              Bitmap icon = (device == null) ? null : device.getIcon();
              icon = (icon == null) ? getDefaultIcon(R.drawable.ic_cast_blue) : icon;
              upnpIconConsumer.accept((device == null) ? null : icon);
            }
          });
        }
      }

      @Override
      public void onFatalError() {
        tell(R.string.upnp_error);
      }

      @Override
      public void onDeviceAdd(@NonNull Device device) {
        consumeIcon(device);
      }

      @Override
      public void onDeviceRemove(@NonNull Device device) {
        consumeIcon(null);
      }

      @Override
      public void onSelectedDeviceChange(@Nullable Device previousDevice, @Nullable Device device) {
        consumeIcon(device);
        if (upnpAlertDialog.isShowing()) {
          if (device == null) {
            tell(R.string.no_dlna_selection);
          } else {
            tell(getResources().getString(R.string.dlna_selection) + device.getDisplayString());
            playerController.startReading();
          }
          upnpAlertDialog.dismiss();
        }
      }
    };

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
      upnpService = (AndroidUpnpService.UpnpService) service;
      if (upnpService != null) {
        // Set listeners
        upnpDevicesAdapter.setUpnpService(upnpService);
        upnpService.addListener(upnpListener);
        // Init selected device
        upnpService.setSelectedDeviceIdentity(savedSelectedDeviceIdentity, true);
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
      upnpDevicesAdapter.setUpnpService(null);
    }
  };

  public static void setNotification(@NonNull Context context, @NonNull String packageName) {
    final Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
      .putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
    context.startActivity(intent);
  }

  @NonNull
  public SharedPreferences getSharedPreferences() {
    return sharedPreferences;
  }

  @NonNull
  public Bitmap getDefaultIcon(int id) {
    return BitmapFactory.decodeResource(getResources(), id);
  }

  @NonNull
  public Bitmap getDefaultIcon() {
    return getDefaultIcon(R.drawable.ic_radio_gray);
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
//      case R.id.action_alarm:
//        alarmController.launch();
//        break;
      case R.id.action_sleep:
        sleepAlertDialog.show();
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

  public void showWarningOverlay(@NonNull String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  public void startReading(@NonNull Radio radio) {
    sharedPreferences.edit().putString(getString(string.key_last_played_radio), radio.getURL().toString()).apply();
    playerController.startReading(radio);
  }

  @Nullable
  public Radio getLastPlayedRadio() {
    final String url = sharedPreferences.getString(getString(R.string.key_last_played_radio), "");
    return url.isEmpty() ? null : Radios.getInstance().getRadioFromURL(url);
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
    final FragmentTransaction fragmentTransaction = getSupportFragmentManager().beginTransaction();
    // Register back if fragment exists
    if (currentFragment != null) {
      fragmentTransaction.addToBackStack(null);
    }
    // Set
    fragmentTransaction.replace(R.id.content_frame, fragment).commit();
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

  public void setActionsConsumers(
    @Nullable Consumer<Radio> currentRadioConsumer,
    @Nullable Consumer<Bitmap> upnpIconConsumer) {
    playerController.setListener(currentRadioConsumer);
    this.upnpIconConsumer = upnpIconConsumer;
  }

  public void resetSelectedDevice() {
    if (upnpService == null) {
      Log.e(LOG_TAG, "resetSelectedDevice: internal failure, upnpService not defined");
    } else {
      upnpService.setSelectedDeviceIdentity(null, false);
    }
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

  @Override
  public void onConfigurationChanged(@NonNull Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    drawerToggle.onConfigurationChanged(newConfig);
  }

  // Is called also when coming back after a "Back" exit
  @Override
  @SuppressLint({"InflateParams", "NonConstantResourceId"})
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Log.d(LOG_TAG, "onCreate");
    // Create radios if needed
    Radios.setInstance(this);
    // Fetch preferences
    sharedPreferences = getPreferences(Context.MODE_PRIVATE);
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
    // Alarm
    alarmController = new AlarmController(this);
    // Import/export function
    importExportLauncher = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(),
      result -> {
        if (result.getResultCode() == RESULT_OK) {
          final Intent data = result.getData();
          if (data != null && data.getData() != null) {
            final Uri uri = result.getData().getData();
            if (uri != null) {
              assert importExportAction != null;
              switch (importExportAction) {
                case CSV_EXPORT:
                  exportTo(uri, Radios.MIME_CSV);
                  break;
                case JSON_EXPORT:
                  exportTo(uri, Radios.MIME_JSON);
                  break;
                case JSON_IMPORT:
                case CSV_IMPORT:
                  importFrom(uri, importExportAction);
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
    // Build about dialog
    final View aboutView = getLayoutInflater().inflate(R.layout.view_about, null);
    ((TextView) aboutView.findViewById(R.id.version_name_text_view))
      .setText(BuildConfig.VERSION_NAME);
    aboutAlertDialog = new AlertDialog.Builder(this)
      .setView(aboutView)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> checkNavigationMenu())
      .create();
    // Build sleep dialog
    final View sleepView = getLayoutInflater().inflate(R.layout.view_sleep, null);
    final NumberPicker minutePicker = sleepView.findViewById(id.numberPicker);
    minutePicker.setMinValue(SLEEP_MIN);
    minutePicker.setMaxValue(SLEEP_MAX);
    minutePicker.setValue(sharedPreferences.getInt(getString(R.string.key_sleep), SLEEP_MIN));
    minutePicker.setOnValueChangedListener((picker, oldVal, newVal) -> sharedPreferences.edit().putInt(getString(R.string.key_sleep), newVal).apply());
    sleepAlertDialog = new AlertDialog.Builder(this)
      .setTitle(R.string.title_sleep)
      .setIcon(R.drawable.ic_baseline_hourglass_bottom_white_24dp)
      .setView(sleepView)
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
    savedSelectedDeviceIdentity = (savedInstanceState == null) ?
      null : savedInstanceState.getString(getString(R.string.key_selected_device));
    upnpDevicesAdapter = new UpnpDevicesAdapter(
      getThemeAttributeColor(android.R.attr.textColorHighlight),
      contentUpnp.findViewById(R.id.devices_default_linear_layout));
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
    final Device selectedDevice = (upnpService == null) ? null : upnpService.getSelectedDevice();
    savedSelectedDeviceIdentity = (selectedDevice == null) ? null : selectedDevice.getUUID();
    unbindService(upnpConnection);
    // Force disconnection to release resources
    upnpConnection.onServiceDisconnected(null);
    // Clear PlayerController call
    playerController.onActivityPause();
    // Clear AlarmController call
    alarmController.onActivityPause();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    Log.d(LOG_TAG, "onNewIntent");
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
    if ((newIntent != null) && Intent.ACTION_SEND.equals(newIntent.getAction())) {
      radioGardenController.onNewIntent(newIntent);
      newIntent = null;
    }
    // PlayerController init
    playerController.onActivityResume();
    // AlarmController init
    alarmController.onActivityResume();
  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    // Sync the toggle state after onRestoreInstanceState has occurred
    drawerToggle.syncState();
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
    if (savedSelectedDeviceIdentity != null) {
      outState.putString(getString(R.string.key_selected_device), savedSelectedDeviceIdentity);
    }
  }

  @SuppressWarnings({"resource", "SameParameterValue"})
  private int getThemeAttributeColor(int attr) {
    final int[] attrs = {attr};
    final TypedArray typedArray = obtainStyledAttributes(attrs);
    final int result = typedArray.getColor(0, 0); // Get the color value
    typedArray.recycle(); // Always recycle TypedArray
    return result;
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
    // Use a background thread for logcat execution
    final Handler handler = new Handler(Looper.getMainLooper());
    Executors.newSingleThreadExecutor().execute(() -> {
      final File logFile = new File(getFilesDir(), "logcat.txt");
      final String packageName = getPackageName();
      final String[] command = new String[]{
        "logcat",
        "-d",
        "-v",
        "threadtime",
        "-f",
        logFile.toString(),
        "com.watea.*:D"
      };
      Process process = null;
      try {
        // Execute logcat command
        process = Runtime.getRuntime().exec(command);
        // Wait for logcat to finish
        process.waitFor();
        // Check if logcat was successful
        if (logFile.exists() && logFile.length() > 0) {
          // Prepare mail on the main thread
          handler.post(() -> {
            try {
              startActivity(getNewSendIntent(logFile, packageName));
            } catch (Exception exception) {
              Log.e(LOG_TAG, "sendLogcatMail: internal failure", exception);
              tell(R.string.report_error);
            }
          });
        } else {
          handler.post(() -> tell(R.string.report_error));
        }
      } catch (IOException | InterruptedException exception) {
        Log.e(LOG_TAG, "sendLogcatMail: internal failure", exception);
        handler.post(() -> tell(R.string.report_error));
      } finally {
        if (process != null) {
          process.destroy();
        }
      }
    });
  }

  @NonNull
  private Intent getNewSendIntent(@NonNull File logFile, @NonNull String packageName) {
    final Uri logFileUri = FileProvider.getUriForFile(this, packageName + ".fileprovider", logFile);
    return getNewSendIntent()
      .putExtra(Intent.EXTRA_SUBJECT, "RadioUPnP report " + BuildConfig.VERSION_NAME + " / " + Calendar.getInstance().getTime())
      .putExtra(Intent.EXTRA_STREAM, logFileUri)
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
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
      .setTitle(string.title_export)
      .setIcon(R.drawable.ic_baseline_output_white_24dp)
      .setMessage(R.string.export_message)
      .setNeutralButton(R.string.action_csv_export, listener)
      .setPositiveButton(R.string.action_json_export, listener)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> this.checkNavigationMenu())
      .create()
      .show();
  }

  private void importFile() {
    final android.content.DialogInterface.OnClickListener listener =
      (dialog, which) -> {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE);
        if (which == DialogInterface.BUTTON_NEUTRAL) {
          intent.setType("*/*"); // Radios.MIME_CSV MIME type is not supported
          importExportAction = ImportExportAction.CSV_IMPORT;
        } else {
          intent.setType(Radios.MIME_JSON);
          importExportAction = ImportExportAction.JSON_IMPORT;
        }
        importExportLauncher.launch(intent);
      };
    new AlertDialog.Builder(this)
      .setTitle(string.title_import)
      .setIcon(R.drawable.ic_baseline_exit_to_app_white_24dp)
      .setMessage(R.string.import_message)
      .setNeutralButton(R.string.action_csv_export, listener)
      .setPositiveButton(R.string.action_json_export, listener)
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
          Radios.getInstance().write(outputStream, type);
          tell(getString(R.string.export_done) + getString(R.string.app_name));
        }
      }
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

  private void importFrom(@NonNull Uri uri, @NonNull ImportExportAction importExportAction) {
    try (final InputStream inputStream = getContentResolver().openInputStream(uri)) {
      if (inputStream == null) {
        Log.e(LOG_TAG, "importFrom: internal failure");
      } else {
        final boolean result = (importExportAction == ImportExportAction.JSON_IMPORT) ?
          Radios.getInstance().importFrom(inputStream) : Radios.getInstance().importCsvFrom(inputStream);
        tell(result ? R.string.import_successful : R.string.import_no_data);
        return;
      }
    } catch (Exception exception) {
      Log.e(LOG_TAG, "importFrom: I/O failure", exception);
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

  public class UserHint {
    @NonNull
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