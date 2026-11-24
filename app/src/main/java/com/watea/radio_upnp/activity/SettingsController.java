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
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.service.RadioService;

class SettingsController {
  @NonNull
  private final MainActivity mainActivity;
  @NonNull
  private final SharedPreferences sharedPreferences;
  // Built on first show, once activity theme is set
  @Nullable
  private AlertDialog alertDialog = null;

  SettingsController(@NonNull MainActivity mainActivity) {
    this.mainActivity = mainActivity;
    sharedPreferences = mainActivity.getSharedPreferences();
  }

  int getThemeResId() {
    final Theme theme = getTheme();
    final int uiMode = mainActivity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
    return (((theme == Theme.SYSTEM) && (uiMode == Configuration.UI_MODE_NIGHT_NO)) || (theme == Theme.LIGHT)) ?
      R.style.AppTheme_Light : R.style.AppTheme_Dark;
  }

  boolean isTileLayout() {
    return getLayout() == Layout.TILE;
  }

  boolean isPlayLastRadio() {
    return sharedPreferences.getBoolean(mainActivity.getString(R.string.key_play_last_radio), true);
  }

  void show() {
    if (alertDialog == null) {
      alertDialog = build();
    }
    alertDialog.show();
  }

  @NonNull
  private Layout getLayout() {
    return Layout.valueOf(sharedPreferences.getString(mainActivity.getString(R.string.key_layout), Layout.TILE.toString()));
  }

  @NonNull
  private Theme getTheme() {
    return Theme.valueOf(sharedPreferences.getString(mainActivity.getString(R.string.key_theme), Theme.DARK.toString()));
  }

  @NonNull
  @SuppressLint("InflateParams")
  private AlertDialog build() {
    final AlertDialog.Builder builder = new AlertDialog.Builder(mainActivity);
    final View view = LayoutInflater.from(builder.getContext()).inflate(R.layout.view_settings, null);
    // Theme
    ((RadioButton) view.findViewById(getTheme().radioButtonId)).setChecked(true);
    ((RadioGroup) view.findViewById(R.id.theme_radio_group)).setOnCheckedChangeListener((group, checkedId) -> {
      final Theme theme = Theme.fromRadioButtonId(checkedId);
      if (theme != getTheme()) {
        sharedPreferences.edit().putString(mainActivity.getString(R.string.key_theme), theme.toString()).apply();
        assert alertDialog != null;
        alertDialog.dismiss();
        mainActivity.recreate();
      }
    });
    // Layout
    ((RadioButton) view.findViewById((getLayout() == Layout.TILE) ? R.id.tile_radio_button : R.id.row_radio_button)).setChecked(true);
    ((RadioGroup) view.findViewById(R.id.layout_radio_group)).setOnCheckedChangeListener((group, checkedId) -> {
      final Layout layout = (checkedId == R.id.row_radio_button) ? Layout.ROW : Layout.TILE;
      if (layout != getLayout()) {
        sharedPreferences.edit().putString(mainActivity.getString(R.string.key_layout), layout.toString()).apply();
        mainActivity.getSupportFragmentManager().getFragments().stream()
          .filter(fragment -> fragment instanceof MainFragment)
          .findFirst()
          .ifPresent(mainFragment -> ((MainFragment) mainFragment).setLayoutManager());
      }
    });
    // PCM
    final boolean isPcm = sharedPreferences.getBoolean(mainActivity.getString(R.string.key_pcm_mode), RadioService.KEY_PCM_MODE_DEFAULT);
    ((RadioButton) view.findViewById(isPcm ? R.id.pcm_radio_button : R.id.passthrough_radio_button)).setChecked(true);
    ((RadioGroup) view.findViewById(R.id.pcm_radio_group)).setOnCheckedChangeListener((group, checkedId) ->
      sharedPreferences.edit().putBoolean(mainActivity.getString(R.string.key_pcm_mode), (checkedId == R.id.pcm_radio_button)).commit());
    // Play last radio
    final CheckBox playLastRadioCheckBox = view.findViewById(R.id.play_last_radio_check_box);
    playLastRadioCheckBox.setChecked(isPlayLastRadio());
    playLastRadioCheckBox.setOnCheckedChangeListener((buttonView, isChecked) ->
      sharedPreferences.edit().putBoolean(mainActivity.getString(R.string.key_play_last_radio), isChecked).apply());
    return builder
      .setTitle(R.string.title_settings)
      .setIcon(R.drawable.ic_settings_white_24dp)
      .setView(view)
      // Restore checked item
      .setOnDismissListener(dialogInterface -> mainActivity.checkNavigationMenu())
      .create();
  }

  private enum Layout {
    TILE, ROW
  }

  private enum Theme {
    SYSTEM(R.id.system_radio_button), DARK(R.id.dark_radio_button), LIGHT(R.id.light_radio_button);

    final int radioButtonId;

    Theme(int radioButtonId) {
      this.radioButtonId = radioButtonId;
    }

    @NonNull
    static Theme fromRadioButtonId(int radioButtonId) {
      for (final Theme theme : values()) {
        if (theme.radioButtonId == radioButtonId) {
          return theme;
        }
      }
      return SYSTEM;
    }
  }
}