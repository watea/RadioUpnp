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
import android.graphics.Bitmap;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.RadiosSearchAdapter;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;
import com.watea.radio_upnp.service.RadioURL;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SearchFragment extends MainActivityFragment {
  private static final String LOG_TAG = SearchFragment.class.getSimpleName();
  private static final String[] RADIO_BROWSER_SERVERS = {
    "https://de1.api.radio-browser.info",
    "https://fr1.api.radio-browser.info",
    "https://nl1.api.radio-browser.info",
    "https://at1.api.radio-browser.info"
  };
  private static final int MAX_RADIOS = 200;
  private final OkHttpClient httpClient = new OkHttpClient();
  private String radioBrowserServer = null;
  private FrameLayout defaultFrameLayout;
  private AlertDialog searchAlertDialog;
  private EditText nameEditText;
  private RadiosSearchAdapter radiosSearchAdapter;
  private Spinner countrySpinner;
  private int searchId = 0;
  private String appName; // Must be set in onResume()

  @SuppressLint("NonConstantResourceId")
  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    switch (item.getItemId()) {
      case R.id.action_all:
        radiosSearchAdapter.selectAll();
        return true;
      case R.id.action_done:
        Radios.getInstance().addAll(radiosSearchAdapter.getSelectedRadios());
        onBackPressed();
        return true;
      default:
        // If we got here, the user's action was not recognized.
        // Invoke the superclass to handle it.
        return super.onOptionsItemSelected(item);
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    getSharedPreferences()
      .edit()
      .putString(getString(R.string.key_country), getCountry())
      .apply();
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return v -> {
      if (radioBrowserServer == null) {
        tell(R.string.server_not_available);
      } else {
        searchAlertDialog.show();
      }
    };
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_search_white_24dp;
  }

  @Override
  public int getMenuId() {
    return R.menu.menu_search;
  }

  @Override
  public int getTitle() {
    return R.string.title_search;
  }

  @Override
  protected int getLayout() {
    return R.layout.content_main;
  }

  @Override
  public void onResume() {
    super.onResume();
    radioBrowserServer = null;
    appName = getMainActivity().getString(R.string.app_name);
    new Thread(() -> {
      for (String radioBrowserServer : RADIO_BROWSER_SERVERS) {
        try {
          final Request request = getRequestBuilder()
            .url(radioBrowserServer + "/json/countries")
            .build();
          final JSONArray countriesArray = getJSONArray(request);
          final List<String> countries = new ArrayList<>();
          for (int i = 0; i < countriesArray.length(); i++) {
            final String name = countriesArray.getJSONObject(i).getString("name");
            if (!name.isEmpty() && !countries.contains(name)) {
              countries.add(name);
            }
          }
          countries.sort(Comparator.naturalOrder());
          countries.add(0, getMainActivity().getString(R.string.Country));
          protectedRunOnUiThread(() -> {
            countrySpinner.setAdapter(
              new ArrayAdapter<>(getMainActivity(), android.R.layout.simple_spinner_dropdown_item, countries));
            final int position = countries.indexOf(getSharedPreferences().getString(getString(R.string.key_country), ""));
            countrySpinner.setSelection(Math.max(position, 0));
            this.radioBrowserServer = radioBrowserServer;
            // Show search dialog first time
            if (searchId == 0) {
              searchAlertDialog.show();
            }
          });
          // Done!
          return;
        } catch (IOException | JSONException exception) {
          Log.d(LOG_TAG, "onResume: radioBrowserServer fetch error");
        }
      }
      protectedRunOnUiThread(() -> {
        tell(R.string.server_not_available);
        searchAlertDialog.hide();
      });
    }).start();
  }

  @SuppressLint("InflateParams")
  @Override
  public void onCreateView(@NonNull View view, @Nullable ViewGroup container) {
    final RecyclerView radiosRecyclerView = view.findViewById(R.id.radios_recycler_view);
    radiosRecyclerView.setLayoutManager(new LinearLayoutManager(view.getContext()));
    defaultFrameLayout = view.findViewById(R.id.default_frame_layout);
    final View searchView = getMainActivity().getLayoutInflater().inflate(R.layout.view_search, null);
    nameEditText = searchView.findViewById(R.id.name_edit_text);
    countrySpinner = searchView.findViewById(R.id.country_spinner);
    radiosSearchAdapter = new RadiosSearchAdapter(getMainActivity(), radiosRecyclerView);
    searchAlertDialog = new AlertDialog.Builder(getMainActivity())
      .setView(searchView)
      .setPositiveButton(R.string.action_go, (dialogInterface, i) -> search())
      .create();
  }

  private String getCountry() {
    return (countrySpinner.getSelectedItemPosition() > 0) ? countrySpinner.getSelectedItem().toString() : "";
  }

  private void search() {
    final int threadSearchId = ++searchId; // New search disables preceding
    final String search = nameEditText.getText().toString();
    final String country = getCountry();
    tell(R.string.wait_search);
    radiosSearchAdapter.clear();
    defaultFrameLayout.setVisibility(View.VISIBLE);
    new Thread(() -> {
      final String searchQuery = search.isEmpty() ? "" : "name=" + search;
      final String countryQuery = country.isEmpty() ? "" : "country=" + country;
      final Request request = getRequestBuilder()
        .url(radioBrowserServer + "/json/stations/search?limit=" + MAX_RADIOS + "&" +
          countryQuery + (countryQuery.isEmpty() ? "" : "&") + searchQuery)
        .build();
      final JSONArray stations;
      try {
        stations = getJSONArray(request);
      } catch (IOException | JSONException exception) {
        tell(R.string.radio_search_failure);
        return;
      }
      for (int i = 0; i < stations.length(); i++) {
        try {
          final JSONObject station = stations.getJSONObject(i);
          final String name = station.getString("name");
          final String streamUrl = station.getString("url");
          final String homepage = station.optString("homepage", "");
          final String favicon = station.optString("favicon", "");
          final AtomicReference<Bitmap> icon = new AtomicReference<>(null);
          try {
            icon.set(new RadioURL(new URL(favicon)).getBitmap());
          } catch (MalformedURLException malformedURLException) {
            Log.d(LOG_TAG, "search: icon fetch error");
          }
          // We can now add radio if we are not disposed
          protectedRunOnUiThread(() -> {
            try {
              if (threadSearchId == searchId) {
                radiosSearchAdapter.add(new Radio(
                  name,
                  (icon.get() == null) ? getMainActivity().getDefaultIcon() : icon.get(),
                  new URL(streamUrl),
                  homepage.isEmpty() ? null : new URL(homepage)));
                if (defaultFrameLayout.getVisibility() == View.VISIBLE) {
                  defaultFrameLayout.setVisibility(View.INVISIBLE);
                }
              }
            } catch (MalformedURLException malformedURLException) {
              Log.d(LOG_TAG, "Radio could not be created");
            }
          });
        } catch (JSONException jSONException) {
          Log.d(LOG_TAG, "Malformed JSON for radio");
        }
      }
      protectedRunOnUiThread(() -> {
        if (defaultFrameLayout.getVisibility() == View.VISIBLE) {
          tell(R.string.no_radio_found);
        }
      });
    }).start();
  }

  @NonNull
  private Request.Builder getRequestBuilder() {
    return new Request.Builder().header("User-Agent", appName);
  }

  @NonNull
  private JSONArray getJSONArray(@NonNull Request request) throws IOException, JSONException {
    try (final Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
      return (response.body() == null) ? new JSONArray() : new JSONArray(response.body().string());
    }
  }
}