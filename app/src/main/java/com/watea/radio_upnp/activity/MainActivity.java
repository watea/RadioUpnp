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
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.NavigationView;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBar;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.RadioLibrary;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Hashtable;
import java.util.Map;
import java.util.Objects;

public class MainActivity
  extends
  AppCompatActivity
  implements
  MainActivityFragment.Provider,
  ModifyFragment.Callback,
  NavigationView.OnNavigationItemSelectedListener {
  private static final String LOG_TAG = MainActivity.class.getName();
  private static final Map<Class<? extends Fragment>, Integer> FRAGMENT_MENU_IDS =
    new Hashtable<Class<? extends Fragment>, Integer>() {{
      put(MainFragment.class, R.id.action_home);
      put(ItemModifyFragment.class, R.id.action_add_item);
      put(ModifyFragment.class, R.id.action_modify);
      put(DonationFragment.class, R.id.action_donate);
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
      "http://mp3lg4.tdf-cdn.com/9240/lag_180945.mp3",
      "https://www.europe1.fr/"),
    new DefaultRadio(
      "RFM",
      R.drawable.logo_rfm,
      "http://rfm-live-mp3-128.scdn.arkena.com/rfm.mp3",
      "http://www.rfm.fr/"),
    new DefaultRadio(
      "SKYROCK",
      R.drawable.logo_skyrock,
      "http://icecast.skyrock.net/s/natio_mp3_128k",
      "https://www.skyrock.com/"),
    new DefaultRadio(
      "VIRGIN",
      R.drawable.logo_virgin,
      "http://vr-live-mp3-128.scdn.arkena.com/virginradio.mp3",
      "https://www.virginradio.fr/"),
    new DefaultRadio(
      "FUN",
      R.drawable.logo_fun,
      "http://streaming.radio.funradio.fr/fun-1-48-192",
      "https://www.funradio.fr/")
  };
  // <HMI assets
  private DrawerLayout mDrawerLayout;
  private ActionBarDrawerToggle mDrawerToggle;
  private ActionBar mActionBar;
  private FloatingActionButton mFloatingActionButton;
  private Menu mNavigationMenu;
  private AlertDialog mAboutAlertDialog;
  // />
  private RadioLibrary mRadioLibrary;
  private Integer mNavigationMenuCheckedId;
  private MainFragment mMainFragment;

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    // MainActivityFragment instantiates the menu
    //noinspection ConstantConditions
    getCurrentFragment().onCreateOptionsMenu(getMenuInflater(), menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    // Pass the event to ActionBarDrawerToggle, if it returns
    // true, then it has handled the app icon touch event
    //noinspection ConstantConditions
    return
      mDrawerToggle.onOptionsItemSelected(item) ||
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        getCurrentFragment().onOptionsItemSelected(item) ||
        // If we got here, the user's action was not recognized
        // Invoke the superclass to handle it
        super.onOptionsItemSelected(item);
  }

  @Override
  public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
    int id = menuItem.getItemId();
    if (id == R.id.action_about) {
      mAboutAlertDialog.show();
    } else {
      setFragment(getFragmentClassFromMenuId(id));
    }
    mDrawerLayout.closeDrawers();
    return true;
  }

  // radioId is null if ADD mode
  @Override
  public void onModifyRequest(@Nullable Long radioId) {
    ItemModifyFragment itemModifyFragment = new ItemModifyFragment();
    if (radioId != null) {
      itemModifyFragment.set(Objects.requireNonNull(mRadioLibrary.getFrom(radioId)));
    }
    setFragment(itemModifyFragment);
  }

  @Override
  @NonNull
  public RadioLibrary getRadioLibrary() {
    return mRadioLibrary;
  }

  @Override
  public void onFragmentResume(MainActivityFragment mainActivityFragment) {
    invalidateOptionsMenu();
    mActionBar.setTitle(mainActivityFragment.getTitle());
    mFloatingActionButton.setOnClickListener(
      mainActivityFragment.getFloatingActionButtonOnClickListener());
    mFloatingActionButton.setOnLongClickListener(
      mainActivityFragment.getFloatingActionButtonOnLongClickListener());
    int resource = mainActivityFragment.getFloatingActionButtonResource();
    if (resource != MainActivityFragment.DEFAULT_RESOURCE) {
      mFloatingActionButton.setImageResource(resource);
    }
    checkNavigationMenu(
      Objects.requireNonNull(FRAGMENT_MENU_IDS.get(mainActivityFragment.getClass())));
  }

  @Override
  public void setFloatingActionButtonResource(int resource) {
    mFloatingActionButton.setImageResource(resource);
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
    mMainFragment.onActivityPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
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
    // Retrieve main fragment
    mMainFragment = (MainFragment) ((getCurrentFragment() == null) ?
      setFragment(MainFragment.class) :
      // Shall exists as MainFragment always created
      getFragmentManager().findFragmentByTag(MainFragment.class.getSimpleName()));
    mMainFragment.onActivityResume(this);
  }

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    // First create library as may be needed in fragments
    mRadioLibrary = new RadioLibrary(this);
    // Then create hierarchy
    super.onCreate(savedInstanceState);
    // Inflate view
    setContentView(R.layout.activity_main);
    mDrawerLayout = findViewById(R.id.main_activity);
    // Toolbar
    setSupportActionBar((Toolbar) findViewById(R.id.toolbar));
    mActionBar = getSupportActionBar();
    if (mActionBar == null) {
      // Should not happen
      Log.e(LOG_TAG, "onCreate: ActionBar is null");
    } else {
      mActionBar.setDisplayHomeAsUpEnabled(true);
      mActionBar.setHomeButtonEnabled(true);
    }
    // Set navigation drawer toggle (according to documentation)
    mDrawerToggle = new ActionBarDrawerToggle(
      this, mDrawerLayout, R.string.drawer_open, R.string.drawer_close);
    // Set the drawer toggle as the DrawerListener
    mDrawerLayout.addDrawerListener(mDrawerToggle);
    // Navigation drawer
    NavigationView navigationView = findViewById(R.id.navigation_view);
    mNavigationMenu = navigationView.getMenu();
    navigationView.setNavigationItemSelectedListener(this);
    // Build alert about dialog
    @SuppressLint("InflateParams")
    View aboutView = getLayoutInflater().inflate(R.layout.view_about, null);
    ((TextView) aboutView.findViewById(R.id.version_name)).setText(BuildConfig.VERSION_NAME);
    mAboutAlertDialog = new AlertDialog.Builder(this)
      .setView(aboutView)
      .setOnDismissListener(new DialogInterface.OnDismissListener() {
        @Override
        public void onDismiss(DialogInterface dialogInterface) {
          // Restore checked item
          checkNavigationMenu(mNavigationMenuCheckedId);
        }
      })
      .create();
    // FAB
    mFloatingActionButton = findViewById(R.id.fab);
  }

  @Override
  protected void onPostCreate(Bundle savedInstanceState) {
    super.onPostCreate(savedInstanceState);
    // Sync the toggle state after onRestoreInstanceState has occurred.
    mDrawerToggle.syncState();
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {
    super.onConfigurationChanged(newConfig);
    mDrawerToggle.onConfigurationChanged(newConfig);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    // Close radios database
    mRadioLibrary.close();
  }

  private Class<? extends Fragment> getFragmentClassFromMenuId(@NonNull Integer menuId) {
    for (Class<? extends Fragment> fragment : FRAGMENT_MENU_IDS.keySet()) {
      if (menuId.equals(FRAGMENT_MENU_IDS.get(fragment))) {
        return fragment;
      }
    }
    // Should not happen
    Log.e(LOG_TAG, "getFragmentIdFrom: internal failure, wrong menu id");
    throw new RuntimeException();
  }

  private boolean setDefaultRadios() {
    boolean result = false;
    for (DefaultRadio defaultRadio : DEFAULT_RADIOS) {
      try {
        //noinspection ConstantConditions
        result = (mRadioLibrary.insertAndSaveIcon(
          new Radio(
            defaultRadio.name,
            null, // Filename not known yet
            Radio.Type.MISC,
            Radio.Language.OTHER,
            new URL(defaultRadio.uRL),
            new URL(defaultRadio.webPageURL),
            Radio.Quality.LOW),
          mRadioLibrary.resourceToBitmap(defaultRadio.drawable)) >= 0) || result;
      } catch (MalformedURLException malformedURLException) {
        Log.e(LOG_TAG, "setDefaultRadios: internal error, bad URL definition", malformedURLException);
      }
    }
    return result;
  }

  @Nullable
  private MainActivityFragment getCurrentFragment() {
    return (MainActivityFragment) getFragmentManager().findFragmentById(R.id.content_frame);
  }

  @NonNull
  private Fragment setFragment(@NonNull Class<? extends Fragment> fragmentClass) {
    Fragment fragment = getFragmentManager().findFragmentByTag(fragmentClass.getSimpleName());
    // ItemModifyFragment always a new fragment
    if ((fragment == null) || (fragmentClass == ItemModifyFragment.class)) {
      try {
        fragment = fragmentClass.getConstructor().newInstance();
      } catch (Exception exception) {
        // Should not happen
        Log.e(LOG_TAG, "getFragmentFromClass: internal failure, wrong fragment id");
        throw new RuntimeException();
      }
    }
    return setFragment(fragment);
  }

  @NonNull
  private Fragment setFragment(@NonNull Fragment fragment) {
    // Replace fragment setting tag to retrieve it later
    FragmentTransaction fragmentTransaction = getFragmentManager()
      .beginTransaction()
      .replace(R.id.content_frame, fragment, fragment.getClass().getSimpleName());
    // First fragment transaction not saved to enable back leaving the app
    if (getCurrentFragment() != null) {
      // Works properly only with AndroidManifest options:
      // android:configChanges="keyboardHidden|orientation|screenSize".
      // Otherwise back state added in case of orientation change
      fragmentTransaction.addToBackStack(null);
    }
    fragmentTransaction.commit();
    return fragment;
  }

  private void checkNavigationMenu(@NonNull Integer id) {
    mNavigationMenuCheckedId = id;
    mNavigationMenu.findItem(mNavigationMenuCheckedId).setChecked(true);
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