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
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
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
import androidx.appcompat.widget.SwitchCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.navigation.NavigationView;
import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import java.net.URL;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Vector;

public class MainActivity
  extends
  AppCompatActivity
  implements
  MainActivityFragment.Provider,
  NavigationView.OnNavigationItemSelectedListener {
  private static final String LOG_TAG = MainActivity.class.getName();
  private static final Map<Class<? extends Fragment>, Integer> FRAGMENT_MENU_IDS =
    new Hashtable<Class<? extends Fragment>, Integer>() {{
      put(MainFragment.class, R.id.action_home);
      put(ItemModifyFragment.class, R.id.action_add_item);
      put(ModifyFragment.class, R.id.action_modify);
      put(DonationFragment.class, R.id.action_donate);
    }};
  private static final List<Class<? extends Fragment>> ALWAYS_NEW_FRAGMENTS =
    new Vector<Class<? extends Fragment>>() {{
      add(ItemModifyFragment.class);
    }};
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
      "https://www.funradio.fr/")
  };
  // <HMI assets
  private DrawerLayout drawerLayout;
  private ActionBarDrawerToggle drawerToggle;
  private ActionBar actionBar;
  private FloatingActionButton floatingActionButton;
  private Menu navigationMenu;
  private AlertDialog aboutAlertDialog;
  // />
  private RadioLibrary radioLibrary;
  private Integer navigationMenuCheckedId;
  private MainFragment mainFragment;

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
    return
      drawerToggle.onOptionsItemSelected(item) ||
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        Objects.requireNonNull(getCurrentFragment()).onOptionsItemSelected(item) ||
        // If we got here, the user's action was not recognized
        // Invoke the superclass to handle it
        super.onOptionsItemSelected(item);
  }

  @Override
  public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
    Integer id = menuItem.getItemId();
    // Note: switch not to use as id not final
    if (id == R.id.action_about) {
      aboutAlertDialog.show();
    } else if (id == R.id.action_dark) {
      // Nothing to do here
      return true;
    } else {
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

  @Override
  @NonNull
  public RadioLibrary getRadioLibrary() {
    return radioLibrary;
  }

  @Override
  public void onFragmentResume(@NonNull MainActivityFragment mainActivityFragment) {
    invalidateOptionsMenu();
    actionBar.setTitle(mainActivityFragment.getTitle());
    floatingActionButton.setOnClickListener(
      mainActivityFragment.getFloatingActionButtonOnClickListener());
    floatingActionButton.setOnLongClickListener(
      mainActivityFragment.getFloatingActionButtonOnLongClickListener());
    int resource = mainActivityFragment.getFloatingActionButtonResource();
    if (resource != MainActivityFragment.DEFAULT_RESOURCE) {
      floatingActionButton.setImageResource(resource);
    }
    checkNavigationMenu(
      Objects.requireNonNull(FRAGMENT_MENU_IDS.get(mainActivityFragment.getClass())));
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
    mainFragment.onActivityPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    // Retrieve main fragment
    mainFragment = (MainFragment) ((getCurrentFragment() == null) ?
      setFragment(MainFragment.class) :
      // Shall exists as MainFragment always created
      getSupportFragmentManager().findFragmentByTag(MainFragment.class.getSimpleName()));
    Objects.requireNonNull(mainFragment).onActivityResume(this);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    // Create radio database (order matters)
    radioLibrary = new RadioLibrary(this);
    // Shared preferences
    final SharedPreferences sharedPreferences = getPreferences(Context.MODE_PRIVATE);
    // Create default radios on first start
    if (sharedPreferences.getBoolean(getString(R.string.key_first_start), true) &&
      setDefaultRadios()) {
      // To do just one time, store a flag
      sharedPreferences
        .edit()
        .putBoolean(getString(R.string.key_first_start), false)
        .apply();
    }
    // Set theme
    final boolean darkMode = sharedPreferences.getBoolean(getString(R.string.key_dark_mode), false);
    setTheme(darkMode ? R.style.AppThemeDark : R.style.BaseAppTheme);
    // Inflate view
    setContentView(R.layout.activity_main);
    drawerLayout = findViewById(R.id.main_activity);
    // Toolbar
    setSupportActionBar(findViewById(R.id.toolbar));
    actionBar = getSupportActionBar();
    if (actionBar == null) {
      // Should not happen
      Log.e(LOG_TAG, "onCreate: ActionBar is null");
    } else {
      actionBar.setDisplayHomeAsUpEnabled(true);
      actionBar.setHomeButtonEnabled(true);
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
    SwitchCompat darkSwitch =
      (SwitchCompat) navigationMenu.findItem(R.id.action_dark).getActionView();
    darkSwitch.setChecked(darkMode);
    darkSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
      // Store theme in preferences and recreate activity
      sharedPreferences
        .edit()
        .putBoolean(getString(R.string.key_dark_mode), isChecked)
        .apply();
      recreate();
    });
    // Build alert about dialog
    @SuppressLint("InflateParams")
    View aboutView = getLayoutInflater().inflate(R.layout.view_about, null);
    ((TextView) aboutView.findViewById(R.id.version_name_text_view))
      .setText(BuildConfig.VERSION_NAME);
    aboutAlertDialog = new AlertDialog.Builder(this)
      .setView(aboutView)
      .setOnDismissListener(dialogInterface -> {
        // Restore checked item
        checkNavigationMenu(navigationMenuCheckedId);
      })
      .create();
    // FAB
    floatingActionButton = findViewById(R.id.floating_action_button);
  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    // Sync the toggle state after onRestoreInstanceState has occurred.
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

  private void checkNavigationMenu(@NonNull Integer id) {
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