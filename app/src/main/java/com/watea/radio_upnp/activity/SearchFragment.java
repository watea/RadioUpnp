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
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.BuildConfig;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class SearchFragment extends MainActivityFragment {
  private static final String LOG_TAG = SearchFragment.class.getSimpleName();
  private static final int MAX_RADIOS = 200;
  private static final String COUNTRIES = "json/countries";
  private static final String RADIO_TAGS = "json/tags";
  private static final String NOTHING_TAG = "";
  private static final int MIN_STATION_COUNT = MAX_RADIOS / 10;
  private static final int DEFAULT_COUNT = 1;
  private static final String RADIO_BROWSER_SERVER = new HttpUrl.Builder()
    .scheme("https")
    .host("all.api.radio-browser.info")
    .build()
    .toString();
  private final OkHttpClient httpClient = new OkHttpClient();
  private final List<String> countries = new ArrayList<>();
  private final List<String> radioTags = new ArrayList<>();
  private final List<String> bitrates = new ArrayList<>();
  private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
  private Future<?> currentSearchFuture = null;
  private FrameLayout defaultFrameLayout;
  private AlertDialog searchAlertDialog;
  private EditText nameEditText;
  private RadiosSearchAdapter radiosSearchAdapter;
  private Spinner countrySpinner;
  private Spinner radioTagSpinner;
  private Spinner bitrateSpinner;
  private ProgressBar progressBar;
  private LinearLayout linearLayout;
  private boolean isServerAvailable = false;
  private int searchSessionId = 0;

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
      .putString(getString(R.string.key_radio_tag), getRadioTag())
      .putString(getString(R.string.key_bitrate), getBitrate())
      .apply();
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return v -> {
      // If search dialog already built, use it, or else build it
      if (isServerAvailable) {
        searchAlertDialog.show();
      } else {
        handleSearchAlertDialog();
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
    // Build search dialog if not yet done
    if (!isServerAvailable) {
      handleSearchAlertDialog();
    }
  }

  @SuppressLint("InflateParams")
  @Override
  public void onCreateView(@NonNull View view, @Nullable ViewGroup container) {
    final RecyclerView radiosRecyclerView = view.findViewById(R.id.radios_recycler_view);
    radiosRecyclerView.setLayoutManager(new LinearLayoutManager(view.getContext()));
    defaultFrameLayout = view.findViewById(R.id.default_frame_layout);
    assert getActivity() != null;
    final View searchView = getActivity().getLayoutInflater().inflate(R.layout.view_search, null);
    progressBar = searchView.findViewById(R.id.progressBar);
    linearLayout = searchView.findViewById(R.id.linearLayout);
    nameEditText = searchView.findViewById(R.id.name_edit_text);
    countrySpinner = searchView.findViewById(R.id.country_spinner);
    radioTagSpinner = searchView.findViewById(R.id.radio_tag_spinner);
    bitrateSpinner = searchView.findViewById(R.id.rate_spinner);
    radiosSearchAdapter = new RadiosSearchAdapter(radiosRecyclerView);
    assert getContext() != null;
    searchAlertDialog = new AlertDialog.Builder(getContext())
      .setView(searchView)
      .setPositiveButton(R.string.action_go, (dialogInterface, i) -> search())
      .create();
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    cancelSearch();
    searchExecutor.shutdown();
  }

  private String getCountry() {
    return getSpinnerValue(countrySpinner);
  }

  private String getRadioTag() {
    return getSpinnerValue(radioTagSpinner);
  }

  private String getBitrate() {
    return getSpinnerValue(bitrateSpinner);
  }

  private String getSpinnerValue(@NonNull Spinner spinner) {
    return (spinner.getSelectedItemPosition() > 0) ? spinner.getSelectedItem().toString() : "";
  }

  private void fetchList(
    @NonNull List<String> list,
    @NonNull String url,
    @NonNull String countTag,
    int countLimit,
    @NonNull String select) throws IOException, JSONException {
    final Request request = getRequestBuilder()
      .url(RADIO_BROWSER_SERVER + url)
      .build();
    final JSONArray array = getJSONArray(request);
    for (int i = 0; i < array.length(); i++) {
      final String name = array.getJSONObject(i).optString("name", "");
      final int count = countTag.isEmpty() ? DEFAULT_COUNT : array.getJSONObject(i).getInt(countTag);
      if (!name.isEmpty() && !list.contains(name) && (count >= countLimit)) {
        list.add(name);
      }
    }
    list.sort(Comparator.naturalOrder());
    list.add(0, select);
  }

  private void setSpinner(
    @NonNull Spinner spinner,
    @NonNull ArrayAdapter<String> arrayAdapter,
    @NonNull List<String> list,
    int key) {
    spinner.setAdapter(arrayAdapter);
    final int position = list.indexOf(getSharedPreferences().getString(getString(key), ""));
    spinner.setSelection(Math.max(position, 0));
  }

  private void search() {
    final int currentSession = ++searchSessionId;
    final String search = nameEditText.getText().toString();
    final String country = getCountry();
    final String radioTag = getRadioTag();
    tell(R.string.wait_search);
    radiosSearchAdapter.clear();
    defaultFrameLayout.setVisibility(View.VISIBLE);
    cancelSearch();
    currentSearchFuture = searchExecutor.submit(() -> {
      final String searchQuery = buildQuery(search, "name");
      final String countryQuery = buildQuery(country, "country");
      final String radioTagQuery = buildQuery(radioTag, "tag");
      final Request request = getRequestBuilder()
        .url(RADIO_BROWSER_SERVER + "json/stations/search?limit=" + MAX_RADIOS +
          prefixIfNotEmptyWithAmp(countryQuery, radioTagQuery, searchQuery))
        .build();
      final JSONArray stations;
      try {
        stations = getJSONArray(request);
      } catch (IOException | JSONException exception) {
        protectedRunOnUiThread(() -> tell(R.string.radio_search_failure));
        return;
      }
      final String bitrate = getBitrate();
      int selectedBitrate = 0;
      try {
        selectedBitrate = Integer.parseInt(bitrate.replace(getString(R.string.kbs), ""));
      } catch (NumberFormatException numberFormatException) {
        Log.w(LOG_TAG, "Invalid bitrate format: " + bitrate);
      }
      for (int i = 0; i < stations.length(); i++) {
        if (Thread.currentThread().isInterrupted()) {
          return;
        }
        try {
          final JSONObject station = stations.getJSONObject(i);
          final String name = station.getString("name");
          final String streamUrl = station.getString("url_resolved");
          final String homepage = station.optString("homepage", "");
          final String favicon = station.optString("favicon", "");
          final int stationBitrate = station.optInt("bitrate", 0);
          // Bitrate shall be filtered by client
          if (stationBitrate >= selectedBitrate) {
            final AtomicReference<Bitmap> icon = new AtomicReference<>(null);
            try {
              icon.set(new RadioURL(new URL(favicon)).getBitmap());
            } catch (MalformedURLException e) {
              Log.d(LOG_TAG, "search: icon fetch error");
            }
            protectedRunOnUiThread(() -> {
              // Ignore old search results
              if (currentSession != searchSessionId) {
                return;
              }
              try {
                radiosSearchAdapter.add(new Radio(
                  name,
                  (icon.get() == null) ? getMainActivity().getDefaultIcon() : icon.get(),
                  new URL(streamUrl),
                  homepage.isEmpty() ? null : new URL(homepage)));
                if (defaultFrameLayout.getVisibility() == View.VISIBLE) {
                  defaultFrameLayout.setVisibility(View.INVISIBLE);
                }
              } catch (MalformedURLException e) {
                Log.d(LOG_TAG, "Radio could not be created");
              }
            });
          }
        } catch (JSONException e) {
          Log.d(LOG_TAG, "Malformed JSON for radio");
        }
      }
      protectedRunOnUiThread(() -> {
        if ((currentSession == searchSessionId) && (radiosSearchAdapter.getItemCount() == 0)) {
          tell(R.string.no_radio_found);
        }
      });
    });
  }

  @NonNull
  private String buildQuery(@NonNull String query, @NonNull String key) {
    return query.isEmpty() ? "" : (key + "=" + query);
  }

  @NonNull
  private String prefixIfNotEmptyWithAmp(@NonNull String... querys) {
    final StringBuilder result = new StringBuilder();
    for (String query : querys) {
      if (!query.isEmpty()) {
        result.append("&").append(query);
      }
    }
    return result.toString();
  }

  @NonNull
  private Request.Builder getRequestBuilder() {
    return new Request.Builder().header("User-Agent", getString(R.string.app_name) + "/" + BuildConfig.VERSION_NAME);
  }

  @NonNull
  private JSONArray getJSONArray(@NonNull Request request) throws IOException, JSONException {
    try (final Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
      return (response.body() == null) ? new JSONArray() : new JSONArray(response.body().string());
    }
  }

  private void cancelSearch() {
    if (currentSearchFuture != null && !currentSearchFuture.isDone()) {
      currentSearchFuture.cancel(true);
      protectedRunOnUiThread(() -> radiosSearchAdapter.clear());
    }
  }

  private void handleSearchAlertDialog() {
    countries.clear();
    radioTags.clear();
    bitrates.clear();
    linearLayout.setVisibility(View.INVISIBLE);
    progressBar.setVisibility(View.VISIBLE);
    searchAlertDialog.show();
    searchAlertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
    currentSearchFuture = searchExecutor.submit(() -> {
      try {
        fetchList(countries, COUNTRIES, NOTHING_TAG, DEFAULT_COUNT, getString(R.string.country));
        fetchList(radioTags, RADIO_TAGS, "stationcount", MIN_STATION_COUNT, getString(R.string.radio_tag));
        isServerAvailable = true;
      } catch (IOException | JSONException exception) {
        Log.d(LOG_TAG, "onResume: radioBrowserServer fetch error", exception);
        isServerAvailable = false;
      }
      bitrates.addAll(Arrays.asList(getResources().getStringArray(R.array.bitrates_array)));
      bitrates.replaceAll(s -> s + getString(R.string.kbs));
      bitrates.add(0, getString(R.string.bitrate));
      protectedRunOnUiThread(() -> {
        if (isServerAvailable) {
          assert getContext() != null;
          final ArrayAdapter<String> countriesAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, countries);
          final ArrayAdapter<String> radioTagsAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, radioTags);
          final ArrayAdapter<String> bitratesAdapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_dropdown_item, bitrates);
          setSpinner(countrySpinner, countriesAdapter, countries, R.string.key_country);
          setSpinner(radioTagSpinner, radioTagsAdapter, radioTags, R.string.key_radio_tag);
          setSpinner(bitrateSpinner, bitratesAdapter, bitrates, R.string.key_bitrate);
          linearLayout.setVisibility(View.VISIBLE);
          progressBar.setVisibility(View.INVISIBLE);
          searchAlertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.VISIBLE);
        } else {
          tell(R.string.server_not_available);
          searchAlertDialog.hide();
        }
      });
    });
  }
}