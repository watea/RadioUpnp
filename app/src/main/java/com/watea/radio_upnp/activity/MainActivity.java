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

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
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

import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.UiUtils;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.service.NetworkProxy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
  private static final String LOG_TAG = MainActivity.class.getSimpleName();
  private static final Handler HANDLER = new Handler(Looper.getMainLooper());
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
  @Nullable
  private AlertDialog loadingAlertDialog = null;
  private AlertDialog aboutAlertDialog;
  private UserHint toolbarUserHint;
  private CollapsingToolbarLayout actionBarLayout;
  private AppBarLayout appBarLayout;
  private PlayerController playerController;
  private SleepController sleepController;
  private AlarmController alarmController;
  private SettingsController settingsController;
  private ImportExportController importExportController;
  private UpnpController upnpController;
  private boolean isToolbarExpanded = true;
  private int navigationMenuCheckedId;
  private NetworkProxy networkProxy;

  @NonNull
  static Bitmap getDefaultIcon(@NonNull Context context, int id) {
    return BitmapFactory.decodeResource(context.getResources(), id);
  }

  @NonNull
  static Bitmap getDefaultIcon(@NonNull Context context) {
    return getDefaultIcon(context, R.drawable.ic_radio_gray);
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

  // Is called also when coming back after a "Back" exit
  @Override
  @SuppressLint({"InflateParams", "NonConstantResourceId"})
  protected void onCreate(Bundle savedInstanceState) {
    Log.d(LOG_TAG, "onCreate");
    // Must be before super.onCreate: fragment restoration calls Radios.getInstance()
    Radios.setInstance(this, getLoadingAlertDialog());
    super.onCreate(savedInstanceState);
    // Settings
    settingsController = new SettingsController(this);
    setTheme(settingsController.getThemeResId());
    // Init connexion
    networkProxy = new NetworkProxy(this);
    // Inflate view
    setContentView(R.layout.activity_main);
    drawerLayout = findViewById(R.id.main_activity);
    // ActionBar
    setSupportActionBar(findViewById(R.id.actionbar));
    actionBarLayout = findViewById(R.id.actionbar_layout);
    sleepController = new SleepController(this);
    playerController = new PlayerController(this, actionBarLayout, sleepController);
    assert getSupportActionBar() != null;
    getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    // AppBarLayout and Toolbar
    toolbarUserHint = new UserHint(this, R.string.key_toolbar_got_it, R.string.toolbar_press, 50);
    (appBarLayout = findViewById(R.id.appbar_layout)).addOnOffsetChangedListener(
      (localAppBarLayout, verticalOffset) -> {
        isToolbarExpanded = (verticalOffset == 0);
        toolbarUserHint.show();
      });
    // Toggle expansion state, animate the transition
    findViewById(R.id.actionbar).setOnClickListener(v -> setToolbarExpanded(!isToolbarExpanded));
    // Alarm
    alarmController = new AlarmController(this);
    // Import/export
    importExportController = new ImportExportController(this);
    // Set navigation drawer toggle (according to documentation)
    drawerToggle = new ActionBarDrawerToggle(this, drawerLayout, R.string.drawer_open, R.string.drawer_close);
    // Set the drawer toggle as the DrawerListener
    drawerLayout.addDrawerListener(drawerToggle);
    // Navigation drawer
    final NavigationView navigationView = findViewById(R.id.navigation_view);
    navigationView.setNavigationItemSelectedListener(this::onNavigationItemSelected);
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
    // UPnP
    upnpController = new UpnpController(this, playerController);
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
    if (settingsController.isPlayLastRadio() && (Radios.getLastPlayedRadioId(this) != null)) {
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
    // Release UPnP service
    upnpController.onActivityStop();
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
    // Finalize donations whose payment completed while app was not listening
    DonationFragment.consumePendingPurchases(this);
    // Bind to UPnP service
    upnpController.onActivityStart();
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

  // With animation
  void setToolbarExpanded(boolean isExpanded) {
    appBarLayout.setExpanded(isExpanded, true);
  }

  boolean isTileLayout() {
    return settingsController.isTileLayout();
  }

  void onFragmentResume(@NonNull MainActivityFragment mainActivityFragment) {
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

  void tell(int message) {
    tell(Snackbar.make(getWindow().getDecorView().getRootView(), message, Snackbar.LENGTH_LONG));
  }

  void tell(@NonNull String message) {
    tell(Snackbar.make(getWindow().getDecorView().getRootView(), message, Snackbar.LENGTH_LONG));
  }

  @NonNull
  Fragment setFragment(@NonNull Class<? extends Fragment> fragmentClass) {
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
  NetworkProxy getNetworkProxy() {
    assert networkProxy != null;
    return networkProxy;
  }

  @NonNull
  PlayerController getPlayerController() {
    assert playerController != null;
    return playerController;
  }

  @NonNull
  UpnpController getUpnpController() {
    return upnpController;
  }

  @NonNull
  Intent getNewSendIntent() {
    return new Intent(Intent.ACTION_SEND)
      .setType("message/rfc822")
      .putExtra(Intent.EXTRA_EMAIL, new String[]{"fr.watea@gmail.com"});
  }

  // Builds load dialog if necessary
  @NonNull
  AlertDialog getLoadingAlertDialog() {
    return (loadingAlertDialog == null) ?
      loadingAlertDialog = new AlertDialog.Builder(this)
        .setCancelable(false)
        .setView(R.layout.view_loading)
        .create() :
      loadingAlertDialog;
  }

  void showWarningOverlay(@NonNull String message) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
  }

  void runSafelyOnUiThread(@NonNull Runnable runnable) {
    runOnUiThread(() -> {
      if (!isFinishing() && !isDestroyed()) {
        runnable.run();
      }
    });
  }

  void checkNavigationMenu() {
    checkNavigationMenu(navigationMenuCheckedId);
  }

  private void tell(@NonNull Snackbar snackbar) {
    final Context dialogContext = new AlertDialog.Builder(this).getContext();
    snackbar.setTextColor(UiUtils.getThemeAttributeColor(dialogContext, android.R.attr.textColorPrimary)).show();
  }

  // Voice search (empty query matches first radio) or shortcut
  private void handleIntent(@NonNull Intent intent) {
    final String radioName = MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH.equals(intent.getAction()) ?
      intent.getStringExtra(SearchManager.QUERY) :
      intent.getStringExtra(getString(R.string.key_radio_name));
    if (radioName != null) {
      playerController.startReadingFromName(radioName);
    }
  }

  private boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
    final int id = menuItem.getItemId();
    if (id == R.id.action_alarm) {
      alarmController.launch();
    } else if (id == R.id.action_sleep) {
      sleepController.show();
    } else if (id == R.id.action_export) {
      importExportController.launchExport();
    } else if (id == R.id.action_import) {
      importExportController.launchImport();
    } else if (id == R.id.action_settings) {
      settingsController.show();
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

  private void tellReportError() {
    tell(R.string.report_error);
    checkNavigationMenu();
  }

  private void sendLogcatMail() {
    new Thread(() -> {
      final File logFile = new File(getFilesDir(), "logcat.txt");
      final String[] command = {"logcat", "-d", "-b", "all", "-v", "threadtime", "*:V"};
      try {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
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
        } finally {
          process.destroy();
        }
        if (logFile.length() > 0) {
          HANDLER.post(() -> {
            try {
              startActivity(getNewSendIntent(logFile, getPackageName()));
            } catch (Exception exception) {
              Log.e(LOG_TAG, "sendLogcatMail failure", exception);
              tellReportError();
            }
          });
        } else {
          HANDLER.post(this::tellReportError);
        }
      } catch (Exception exception) {
        Log.e(LOG_TAG, "sendLogcatMail failure", exception);
        HANDLER.post(this::tellReportError);
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

  @Nullable
  private Fragment getCurrentFragment() {
    return getSupportFragmentManager().findFragmentById(R.id.content_frame);
  }
}