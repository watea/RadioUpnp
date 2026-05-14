/*
 * Copyright (c) 2018-2026. Stephane Treuchot
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
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.LayoutInflater;
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
import com.watea.radio_upnp.cast.CastManager;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.service.AndroidUpnpService;
import com.watea.radio_upnp.service.NetworkProxy;
import com.watea.radio_upnp.service.RadioService;
import com.watea.radio_upnp.upnp.Device;

import org.json.JSONException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class MainActivity
  extends AppCompatActivity
  implements NavigationView.OnNavigationItemSelectedListener {
  public static final int SLEEP_MIN = 5;
  private static final String LOG_TAG = MainActivity.class.getSimpleName();
  private static final int SLEEP_MAX = 90;
  private static final int REQUEST_CODE_POST_NOTIFICATIONS = 101;
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
  private AlertDialog loadingAlertDialog = null; // null when not initialized
  private AlertDialog upnpAlertDialog;
  private AlertDialog settingsAlertDialog;
  private AlertDialog aboutAlertDialog;
  private AlertDialog sleepAlertDialog;
  private UserHint toolbarUserHint;
  private CollapsingToolbarLayout actionBarLayout;
  private AppBarLayout appBarLayout;
  private PlayerController playerController;
  private SharedPreferences sharedPreferences;
  private AlarmController alarmController;
  private boolean isToolbarExpanded = true;
  private int navigationMenuCheckedId;
  private Theme theme = Theme.DARK;
  private Layout layout = Layout.TILE;
  @Nullable
  private AndroidUpnpService.UpnpService upnpService = null;
  private UpnpDevicesAdapter upnpDevicesAdapter;
  private NetworkProxy networkProxy;
  private ActivityResultLauncher<Intent> importExportLauncher;
  @Nullable
  private ImportExportAction importExportAction = null;
  @Nullable
  private Consumer<Bitmap> upnpIconConsumer = null;
  private final ServiceConnection upnpConnection = new ServiceConnection() {
    private final AndroidUpnpService.Listener upnpListener = new AndroidUpnpService.Listener() {
      @Override
      public void onFatalError() {
        runOnUiThread(() -> tell(R.string.upnp_error));
      }

      @Override
      public void onDeviceAdd(@NonNull Device device) {
        if (upnpService.getSelectedDevice() == device) {
          consumeIcon(device);
        }
      }

      @Override
      public void onDeviceRemove(@NonNull Device device) {
        if (upnpService.getSelectedDevice() == device) {
          consumeIcon(null);
        }
      }

      @Override
      public void onSelectedDeviceChange(@Nullable Device previousDevice, @Nullable Device device) {
        consumeIcon(device);
        if (upnpAlertDialog.isShowing()) {
          if (device == null) {
            tell(R.string.no_dlna_selection);
          } else {
            tell(getResources().getString(R.string.dlna_selection) + device.getDisplayString());
            final Radio radio = playerController.getCurrentRadio();
            if (radio != null) {
              startReading(radio);
            }
          }
          upnpAlertDialog.dismiss();
        }
      }

      private void consumeIcon(@Nullable Device device) {
        runOnUiThread(() -> MainActivity.this.consumeIcon(device));
      }
    };

    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
      upnpService = (AndroidUpnpService.UpnpService) service;
      if (upnpService != null) {
        // Set listeners
        upnpDevicesAdapter.setUpnpService(upnpService);
        upnpService.addListener(upnpListener);
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

  public static int getThemeAttributeColor(@NonNull Context context, int attr) {
    final int[] attrs = {attr};
    try (final TypedArray typedArray = context.obtainStyledAttributes(attrs)) {
      return typedArray.getColor(0, 0);
    }
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

  @Override
  public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
    final int id = menuItem.getItemId();
    if (id == R.id.action_alarm) {
      alarmController.launch();
    } else if (id == R.id.action_sleep) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
          requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_CODE_POST_NOTIFICATIONS);
        }
      }
      sleepAlertDialog.show();
    } else if (id == R.id.action_export) {
      exportFile();
    } else if (id == R.id.action_import) {
      importFile();
    } else if (id == R.id.action_settings) {
      settingsAlertDialog.show();
    } else if (id == R.id.action_about) {
      aboutAlertDialog.show();
    } else if (id == R.id.action_log) {
      sendLogcatMail();
    } else {
      // Shall not fail to find!
      for (final Map.Entry<Class<? extends Fragment>, Integer> entry : FRAGMENT_MENU_IDS.entrySet()) {
        if (id == entry.getValue()) {
          setFragment(entry.getKey());
        }
      }
    }
    drawerLayout.closeDrawers();
    return true;
  }

  public void onFragmentResume(@NonNull MainActivityFragment mainActivityFragment) {
    invalidateOptionsMenu();
    actionBarLayout.setTitle(getResources().getString(mainActivityFragment.getTitle()));
    floatingActionButton.setOnClickListener(mainActivityFragment.getFloatingActionButtonOnClickListener());
    floatingActionButton.setOnLongClickListener(mainActivityFragment.getFloatingActionButtonOnLongClickListener());
    final int resource = mainActivityFragment.getFloatingActionButtonResource();
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
    drawerLayout.setDrawerLockMode(isMainFragment ? DrawerLayout.LOCK_MODE_UNLOCKED : DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
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
    playerController.startReading(radio);
  }

  @Nullable
  public Radio getLastPlayedRadio() {
    final String id = getLastPlayedRadioId();
    return id.isEmpty() ? null : Radios.getInstance().getRadioFromId(id);
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
    if (CastManager.getInstance().hasCastSession()) {
      tell(R.string.cast_already_running);
    } else {
      upnpAlertDialog.show();
    }
  }

  public void setUpnpIconConsumer(@Nullable Consumer<Bitmap> upnpIconConsumer) {
    this.upnpIconConsumer = upnpIconConsumer;
    // Update icon if needed
    if (upnpService != null) {
      consumeIcon(upnpService.getSelectedDevice());
    }
  }

  @NonNull
  public Consumer<Consumer<Radio>> getCurrentRadioSupplier() {
    assert playerController != null;
    return playerController;
  }

  public void resetSelectedDevice() {
    if (upnpService == null) {
      Log.e(LOG_TAG, "resetSelectedDevice: internal failure, upnpService not defined");
    } else {
      upnpService.setSelectedDeviceIdentity(null);
    }
  }

  @NonNull
  public Intent getNewSendIntent() {
    return new Intent(Intent.ACTION_SEND)
      .setType("message/rfc822")
      .putExtra(Intent.EXTRA_EMAIL, new String[]{"fr.watea@gmail.com"});
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    // Pass the event to ActionBarDrawerToggle, if it returns
    // true, then it has handled the app icon touch event
    return drawerToggle.onOptionsItemSelected(item) || super.onOptionsItemSelected(item);
  }

  @Override
  public void onConfigurationChanged(@NonNull Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    drawerToggle.onConfigurationChanged(newConfig);
  }

  public Layout getLayout() {
    return layout;
  }

  // Builds load dialog if necessary
  public AlertDialog getLoadingAlertDialog() {
    return (loadingAlertDialog == null) ?
      loadingAlertDialog = new AlertDialog.Builder(this)
        .setCancelable(false)
        .setView(R.layout.view_loading)
        .create() :
      loadingAlertDialog;
  }

  // Is called also when coming back after a "Back" exit
  @Override
  @SuppressLint({"InflateParams", "NonConstantResourceId"})
  protected void onCreate(Bundle savedInstanceState) {
    Log.d(LOG_TAG, "onCreate");
    // Must be before super.onCreate: fragment restoration calls Radios.getInstance()
    Radios.setInstance(this, getLoadingAlertDialog());
    super.onCreate(savedInstanceState);
    // Fetch preferences
    sharedPreferences = getPreferences(Context.MODE_PRIVATE);
    // Theme
    theme = Theme.valueOf(sharedPreferences.getString(getString(R.string.key_theme), theme.toString()));
    setTheme(getCurrentTheme());
    // Layout
    layout = Layout.valueOf(sharedPreferences.getString(getString(R.string.key_layout), layout.toString()));
    // PCM
    final boolean isPcm = sharedPreferences.getBoolean(getString(string.key_pcm_mode), RadioService.KEY_PCM_MODE_DEFAULT);
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
    // Alarm
    alarmController = new AlarmController(this);
    // Import/export function
    importExportLauncher = registerForActivityResult(
      new ActivityResultContracts.StartActivityForResult(),
      result -> {
        if (result.getResultCode() == RESULT_OK) {
          final Intent data = result.getData();
          if (data != null) {
            final Uri uri = data.getData();
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
    drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, R.string.drawer_open, R.string.drawer_close);
    // Set the drawer toggle as the DrawerListener
    drawerLayout.addDrawerListener(drawerToggle);
    // Navigation drawer
    final NavigationView navigationView = findViewById(R.id.navigation_view);
    navigationView.setNavigationItemSelectedListener(this);
    navigationMenu = navigationView.getMenu();
    // Build about dialog
    final AlertDialog.Builder aboutAlertDialogBuilder = new AlertDialog.Builder(this);
    final View aboutView = LayoutInflater.from(aboutAlertDialogBuilder.getContext()).inflate(R.layout.view_about, null);
    ((TextView) aboutView.findViewById(R.id.version_name_text_view))
      .setText(BuildConfig.VERSION_NAME);
    aboutAlertDialog = aboutAlertDialogBuilder
      .setView(aboutView)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> checkNavigationMenu())
      .create();
    // Build sleep dialog
    final AlertDialog.Builder sleepAlertDialogBuilder = new AlertDialog.Builder(this);
    final View sleepView = LayoutInflater.from(sleepAlertDialogBuilder.getContext()).inflate(R.layout.view_sleep, null);
    final NumberPicker minutePicker = sleepView.findViewById(id.numberPicker);
    minutePicker.setMinValue(SLEEP_MIN);
    minutePicker.setMaxValue(SLEEP_MAX);
    minutePicker.setValue(sharedPreferences.getInt(getString(R.string.key_sleep), SLEEP_MIN));
    minutePicker.setOnValueChangedListener((picker, oldVal, newVal) -> sharedPreferences.edit().putInt(getString(R.string.key_sleep), newVal).apply());
    sleepAlertDialog = sleepAlertDialogBuilder
      .setTitle(R.string.title_sleep)
      .setIcon(R.drawable.ic_hourglass_bottom_white_24dp)
      .setView(sleepView)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> checkNavigationMenu())
      .create();
    // Specific UPnP devices dialog
    final AlertDialog.Builder upnpAlertDialogBuilder = new AlertDialog.Builder(this);
    final View upnpView = LayoutInflater.from(upnpAlertDialogBuilder.getContext()).inflate(R.layout.view_upnp, null);
    final RecyclerView devicesRecyclerView = upnpView.findViewById(R.id.devices_recycler_view);
    devicesRecyclerView.setLayoutManager(new LinearLayoutManager(this));
    upnpDevicesAdapter = new UpnpDevicesAdapter(
      getThemeAttributeColor(this, android.R.attr.textColorHighlight),
      upnpView.findViewById(R.id.devices_default_linear_layout));
    devicesRecyclerView.setAdapter(upnpDevicesAdapter);
    upnpAlertDialog = upnpAlertDialogBuilder
      .setView(upnpView)
      .create();
    // Settings dialog
    final AlertDialog.Builder settingsAlertDialogBuilder = new AlertDialog.Builder(this);
    final View settingsView = LayoutInflater.from(settingsAlertDialogBuilder.getContext()).inflate(R.layout.view_settings, null);
    // Settings dialog: theme
    final RadioGroup themeRadioGroup = settingsView.findViewById(R.id.theme_radio_group);
    themeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
      final Theme previousTheme = theme;
      if (checkedId == R.id.dark_radio_button) {
        theme = Theme.DARK;
      } else if (checkedId == R.id.light_radio_button) {
        theme = Theme.LIGHT;
      } else {
        theme = Theme.SYSTEM;
      }
      if (theme != previousTheme) {
        recreate();
      }
    });
    int themeRadioButtonId;
    switch (theme) {
      case DARK:
        themeRadioButtonId = R.id.dark_radio_button;
        break;
      case LIGHT:
        themeRadioButtonId = R.id.light_radio_button;
        break;
      default:
        themeRadioButtonId = R.id.system_radio_button;
    }
    ((RadioButton) settingsView.findViewById(themeRadioButtonId)).setChecked(true);
    // Settings dialog: layout
    final RadioGroup layoutRadioGroup = settingsView.findViewById(R.id.layout_radio_group);
    layoutRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
      final Layout previousLayout = layout;
      layout = (group.getCheckedRadioButtonId() == id.row_radio_button) ? Layout.ROW : Layout.TILE;
      if (layout != previousLayout) {
        final List<Fragment> fragments = getSupportFragmentManager().getFragments();
        fragments.stream().filter(fragment -> fragment instanceof MainFragment).findFirst().ifPresent(mainFragment -> ((MainFragment) mainFragment).setLayoutManager());
      }
    });
    final int layoutRadioButtonId = (layout == Layout.TILE) ? R.id.tile_radio_button : R.id.row_radio_button;
    ((RadioButton) settingsView.findViewById(layoutRadioButtonId)).setChecked(true);
    // Settings dialog: PCM
    final RadioGroup pcmRadioGroup = settingsView.findViewById(R.id.pcm_radio_group);
    pcmRadioGroup.setOnCheckedChangeListener((group, checkedId) ->
      sharedPreferences.edit().putBoolean(getString(R.string.key_pcm_mode), (group.getCheckedRadioButtonId() == id.pcm_radio_button)).commit());
    final int pcmRadioButtonId = isPcm ? R.id.pcm_radio_button : id.relay_radio_button;
    ((RadioButton) settingsView.findViewById(pcmRadioButtonId)).setChecked(true);
    settingsAlertDialog = settingsAlertDialogBuilder
      .setTitle(string.title_settings)
      .setIcon(R.drawable.ic_settings_white_24dp)
      .setView(settingsView)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> checkNavigationMenu())
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
    // PlayerController init
    playerController.onActivityCreate();
    // Auto-restart last played radio (fresh start or process kill recovery)
    if (!getLastPlayedRadioId().isEmpty()) {
      playerController.enableAutoPlay();
    }
    // Intent
    handleIntent(getIntent());
  }

  @Override
  protected void onNewIntent(@NonNull Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleIntent(intent);
  }

  @Override
  protected void onStop() {
    super.onStop();
    // Shared preferences
    sharedPreferences.edit()
      .putString(getString(string.key_theme), theme.toString())
      .putString(getString(string.key_layout), layout.toString())
      .apply();
    // Release UPnP service
    unbindService(upnpConnection);
    // Force disconnection to release resources
    upnpConnection.onServiceDisconnected(null);
    // Clear AlarmController call
    alarmController.onActivityStop();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    Log.d(LOG_TAG, "onDestroy");
    // Clear PlayerController call
    playerController.onActivityDestroy();
  }

  @Override
  protected void onStart() {
    super.onStart();
    // Bind to UPnP service
    if (!bindService(new Intent(this, AndroidUpnpService.class), upnpConnection, BIND_AUTO_CREATE)) {
      Log.e(LOG_TAG, "Internal failure; AndroidUpnpService not bound");
    }
    // AlarmController init
    alarmController.onActivityStart();
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
      outState.putString(getString(R.string.key_current_fragment), currentFragment.getClass().getSimpleName());
    }
  }

  @NonNull
  private String getLastPlayedRadioId() {
    return sharedPreferences.getString(getString(R.string.key_last_played_radio), "");
  }

  private void tell(@NonNull Snackbar snackbar) {
    final Context dialogContext = new AlertDialog.Builder(this).getContext();
    snackbar.setTextColor(getThemeAttributeColor(dialogContext, android.R.attr.textColorPrimary)).show();
  }

  private void handleIntent(@NonNull Intent intent) {
    final String radioName = intent.getStringExtra(getString(R.string.key_radio_name));
    if (radioName != null) {
      playerController.startReadingFromName(radioName);
    }
  }

  private void consumeIcon(@Nullable Device device) {
    if (upnpIconConsumer != null) {
      if (device == null) {
        upnpIconConsumer.accept(null);
      } else {
        final Bitmap icon = device.getIcon();
        upnpIconConsumer.accept((icon == null) ? getDefaultIcon(R.drawable.ic_cast_warm) : icon);
      }
    }
  }

  private int getCurrentTheme() {
    // Check the actual system setting
    final int uiMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    return (((theme == Theme.SYSTEM) && (uiMode == Configuration.UI_MODE_NIGHT_NO)) || (theme == Theme.LIGHT)) ?
      R.style.AppTheme_Light : R.style.AppTheme_Dark;
  }

  private void tellReportError() {
    tell(R.string.report_error);
    checkNavigationMenu();
  }

  private void sendLogcatMail() {
    final Handler handler = new Handler(Looper.getMainLooper());
    new Thread(() -> {
      final File logFile = new File(getFilesDir(), "logcat.txt");
      final String[] command = {"logcat", "-d", "-b", "all", "-v", "threadtime", "*:V"};
      try {
        final Process process = Runtime.getRuntime().exec(command);
        try (final PrintWriter writer = new PrintWriter(new FileOutputStream(logFile));
             final BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
          // --- Device info ---
          writer.println("===== DEVICE INFO =====");
          writer.println("App version : " + BuildConfig.VERSION_NAME);
          writer.println("Android     : " + Build.VERSION.RELEASE);
          writer.println("SDK         : " + Build.VERSION.SDK_INT);
          writer.println("Device      : " + Build.MANUFACTURER + " " + Build.MODEL);
          writer.println("Brand       : " + Build.BRAND);
          writer.println("Board       : " + Build.BOARD);
          writer.println("========================");
          writer.println();
          // --- Logcat ---
          String line;
          while ((line = reader.readLine()) != null) {
            writer.println(line);
          }
        }
        process.waitFor();
        if (logFile.length() > 0) {
          handler.post(() -> {
            try {
              startActivity(getNewSendIntent(logFile, getPackageName()));
            } catch (Exception exception) {
              Log.e(LOG_TAG, "sendLogcatMail failure", exception);
              tellReportError();
            }
          });
        } else {
          handler.post(this::tellReportError);
        }
      } catch (Exception exception) {
        Log.e(LOG_TAG, "sendLogcatMail failure", exception);
        handler.post(this::tellReportError);
      }
    }).start();
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

  private void safeLaunch(@NonNull Intent intent, @NonNull ActivityResultLauncher<Intent> launcher) {
    try {
      launcher.launch(intent);
    } catch (ActivityNotFoundException activityNotFoundException) {
      tell(R.string.no_file_picker_found);
    }
  }

  private void exportFile() {
    final android.content.DialogInterface.OnClickListener listener =
      (dialog, which) -> {
        importExportAction = (which == DialogInterface.BUTTON_NEUTRAL) ?
          ImportExportAction.CSV_EXPORT : ImportExportAction.JSON_EXPORT;
        safeLaunch(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), importExportLauncher);
      };
    new AlertDialog.Builder(this)
      .setTitle(string.title_export)
      .setIcon(R.drawable.ic_output_white_24dp)
      .setMessage(R.string.export_message)
      .setNeutralButton(R.string.action_csv_export, listener)
      .setPositiveButton(R.string.action_json_export, listener)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> checkNavigationMenu())
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
        safeLaunch(intent, importExportLauncher);
      };
    new AlertDialog.Builder(this)
      .setTitle(string.title_import)
      .setIcon(R.drawable.ic_exit_to_app_white_24dp)
      .setMessage(R.string.import_message)
      .setNeutralButton(R.string.action_csv_export, listener)
      .setPositiveButton(R.string.action_json_export, listener)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> checkNavigationMenu())
      .create()
      .show();
  }

  private void exportTo(@NonNull Uri treeUri, @NonNull String type) {
    final List<Radio> snapshot = new ArrayList<>(Radios.getInstance());
    new Thread(() -> {
      try {
        final Uri fileUri = createFileInTree(treeUri, getString(R.string.app_name), type);
        if (fileUri == null) {
          Log.e(LOG_TAG, "exportTo: internal failure, file not created");
          runSafelyOnUiThread(() -> tell(R.string.export_failed_not_created));
          return;
        }
        try (final OutputStream outputStream = getContentResolver().openOutputStream(fileUri)) {
          if (outputStream == null) {
            Log.e(LOG_TAG, "exportTo: internal failure, file not created");
            runSafelyOnUiThread(() -> tell(R.string.export_failed));
          } else {
            Radios.write(snapshot, outputStream, type);
            runSafelyOnUiThread(() -> tell(getString(R.string.export_done) + getString(R.string.app_name)));
          }
        }
      } catch (JSONException | IOException exception) {
        Log.e(LOG_TAG, "exportTo: internal failure", exception);
        runSafelyOnUiThread(() -> tell(R.string.export_failed));
      }
    }).start();
  }

  private void runSafelyOnUiThread(@NonNull Runnable runnable) {
    runOnUiThread(() -> {
      if (!isFinishing() && !isDestroyed()) {
        runnable.run();
      }
    });
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
    // Important: use the document URI as the parent for the new file
    return DocumentsContract.createDocument(getContentResolver(), docUri, type, fileName);
  }

  private void importFrom(@NonNull Uri uri, @NonNull ImportExportAction importExportAction) {
    new Thread(() -> {
      try (final InputStream inputStream = getContentResolver().openInputStream(uri)) {
        if (inputStream == null) {
          Log.e(LOG_TAG, "importFrom: internal failure");
        } else {
          Radios.getInstance().importFrom(
            (importExportAction == ImportExportAction.JSON_IMPORT),
            inputStream,
            () -> true,
            result -> runSafelyOnUiThread(() -> tell(result ? R.string.import_successful : string.import_failed)));
        }
      } catch (Exception exception) {
        Log.e(LOG_TAG, "importFrom: I/O failure", exception);
        runSafelyOnUiThread(() -> tell(R.string.import_failed));
      }
    }).start();
  }

  @Nullable
  private Fragment getCurrentFragment() {
    return getSupportFragmentManager().findFragmentById(R.id.content_frame);
  }

  public enum Layout {
    TILE, ROW
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