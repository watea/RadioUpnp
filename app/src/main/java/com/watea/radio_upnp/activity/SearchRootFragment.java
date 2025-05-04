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
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.watea.radio_upnp.BuildConfig;
import com.watea.radio_upnp.R;
import com.watea.radio_upnp.adapter.RadiosSearchAdapter;
import com.watea.radio_upnp.model.Radio;
import com.watea.radio_upnp.model.Radios;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public abstract class SearchRootFragment extends MainActivityFragment {
  private static final String LOG_TAG = SearchRootFragment.class.getSimpleName();
  protected final OkHttpClient httpClient = new OkHttpClient();
  private final ExecutorService searchExecutor = Executors.newSingleThreadExecutor();
  protected String name;
  protected String stream;
  protected String homepage;
  protected Bitmap icon;
  private FrameLayout defaultFrameLayout;
  private ProgressBar progressBar;
  private LinearLayout linearLayout;
  private int searchSessionId = 0;
  private Future<?> currentSearchFuture = null;
  private AlertDialog searchAlertDialog;
  private RadiosSearchAdapter radiosSearchAdapter;
  private boolean isServerAvailable = false;

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
  public int getMenuId() {
    return R.menu.menu_search;
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_search_white_24dp;
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
  @NonNull
  public View onCreateView(@NonNull View view, int viewId) {
    final RecyclerView radiosRecyclerView = view.findViewById(R.id.radios_recycler_view);
    radiosRecyclerView.setLayoutManager(new LinearLayoutManager(view.getContext()));
    defaultFrameLayout = view.findViewById(R.id.default_frame_layout);
    assert getActivity() != null;
    final View searchView = getActivity().getLayoutInflater().inflate(viewId, null);
    progressBar = searchView.findViewById(R.id.progressBar);
    linearLayout = searchView.findViewById(R.id.linearLayout);
    radiosSearchAdapter = new RadiosSearchAdapter(radiosRecyclerView);
    assert getContext() != null;
    searchAlertDialog = new AlertDialog.Builder(getContext())
      .setView(searchView)
      .setPositiveButton(R.string.action_go, (dialogInterface, i) -> search())
      .create();
    return searchView;
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    cancelSearch();
    searchExecutor.shutdown();
  }

  @NonNull
  protected JSONArray getJSONArray(@NonNull Request request) throws IOException, JSONException {
    try (final Response response = httpClient.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("Unexpected code " + response);
      return (response.body() == null) ? new JSONArray() : buildJSONArray(response.body().string());
    }
  }

  @NonNull
  protected Request.Builder getRequestBuilder() {
    return new Request.Builder().header("User-Agent", getString(R.string.app_name) + "/" + BuildConfig.VERSION_NAME);
  }

  protected Radio buildRadio() throws MalformedURLException {
    return new Radio(
      name,
      (icon == null) ? getMainActivity().getDefaultIcon() : icon,
      new URL(stream),
      homepage.isEmpty() ? null : new URL(homepage));
  }

  @NonNull
  protected JSONArray buildJSONArray(@NonNull String string) throws JSONException {
    return new JSONArray(string);
  }

  @NonNull
  protected HttpUrl.Builder buildQuery(@NonNull HttpUrl.Builder builder, @NonNull String key, @NonNull String query) {
    return query.isEmpty() ? builder : builder.addQueryParameter(key, query);
  }

  protected abstract void clearDialog();

  @NonNull
  protected abstract Request getRequest();

  protected abstract void fetchDialogItems() throws IOException, JSONException;

  protected abstract void setDialogItems();

  // Shall prepare Radio items if needed
  protected abstract boolean validCurrentRadio(@NonNull JSONObject station) throws JSONException;

  private void search() {
    final int currentSession = ++searchSessionId;
    radiosSearchAdapter.clear();
    defaultFrameLayout.setVisibility(View.VISIBLE);
    tell(R.string.wait_search);
    cancelSearch();
    currentSearchFuture = searchExecutor.submit(() -> {
      final JSONArray stations;
      try {
        stations = getJSONArray(getRequest());
      } catch (IOException | JSONException exception) {
        protectedRunOnUiThread(() -> tell(R.string.radio_search_failure));
        return;
      }
      for (int i = 0; i < stations.length(); i++) {
        if (Thread.currentThread().isInterrupted()) {
          return;
        }
        try {
          if (validCurrentRadio(stations.getJSONObject(i))) {
            final Radio radio = buildRadio();
            protectedRunOnUiThread(() -> {
              // Ignore old search results
              if (currentSession != searchSessionId) {
                return;
              }
              radiosSearchAdapter.add(radio);
              if (defaultFrameLayout.getVisibility() == View.VISIBLE) {
                defaultFrameLayout.setVisibility(View.INVISIBLE);
              }
            });
          }
        } catch (JSONException | MalformedURLException exception) {
          Log.w(LOG_TAG, "search: malformed JSON for radio");
        }
      }
      protectedRunOnUiThread(() ->
        tell(((currentSession == searchSessionId) && (radiosSearchAdapter.getItemCount() == 0)) ? R.string.no_radio_found : R.string.search_done));
    });
  }

  private void cancelSearch() {
    if (currentSearchFuture != null && !currentSearchFuture.isDone()) {
      currentSearchFuture.cancel(true);
      protectedRunOnUiThread(() -> radiosSearchAdapter.clear());
    }
  }

  private void handleSearchAlertDialog() {
    clearDialog();
    linearLayout.setVisibility(View.GONE);
    progressBar.setVisibility(View.VISIBLE);
    searchAlertDialog.show();
    searchAlertDialog.getButton(AlertDialog.BUTTON_POSITIVE).setVisibility(View.GONE);
    currentSearchFuture = searchExecutor.submit(() -> {
      try {
        fetchDialogItems();
        isServerAvailable = true;
      } catch (IOException | JSONException exception) {
        Log.d(LOG_TAG, "handleSearchAlertDialog: radioBrowserServer fetch error", exception);
        isServerAvailable = false;
      }
      protectedRunOnUiThread(() -> {
        if (isServerAvailable) {
          setDialogItems();
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