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

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.SkuDetails;
import com.android.billingclient.api.SkuDetailsParams;
import com.watea.radio_upnp.R;

import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

public class DonationFragment
  extends MainActivityFragment
  implements ConsumeResponseListener, PurchasesUpdatedListener {
  private static final String LOG_TAG = DonationFragment.class.getName();
  private static final long RECONNECT_TIMER_START_MILLISECONDS = 1000L; // 1s
  private static final long RECONNECT_TIMER_MAX_TIME_MILLISECONDS = 1000L * 60L * 15L; // 15 mins
  private static final List<String> GOOGLE_CATALOG = Arrays.asList(
    "radio_upnp.donation.1",
    "radio_upnp.donation.2",
    "radio_upnp.donation.3");
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, SkuDetails> skuDetailss = new Hashtable<>();
  private BillingClient billingClient;
  // <HMI assets
  private Spinner googleSpinner;
  private AlertDialog.Builder paymentAlertDialogBuilder;
  // />

  @Override
  public void onConsumeResponse(@NonNull BillingResult billingResult, @NonNull String s) {
    logBillingResult("onConsumeResponse", billingResult);
    Log.d(LOG_TAG, "End consumption flow: " + s);
  }

  @Override
  public void onPurchasesUpdated(
    @NonNull BillingResult billingResult,
    @Nullable List<com.android.billingclient.api.Purchase> list) {
    logBillingResult("onPurchasesUpdated", billingResult);
    switch (billingResult.getResponseCode()) {
      case BillingClient.BillingResponseCode.OK:
        // Consume purchase
        if (list == null) {
          Log.e(LOG_TAG, "onPurchasesUpdated: purchase list is null");
        } else {
          for (com.android.billingclient.api.Purchase purchase : list) {
            Log.d(LOG_TAG, "onPurchasesUpdated: " + purchase);
            billingClient.consumeAsync(
              ConsumeParams.newBuilder()
                .setPurchaseToken(purchase.getPurchaseToken())
                .build(),
              this);
          }
        }
        break;
      case BillingClient.BillingResponseCode.USER_CANCELED:
        // Nothing to do
        break;
      default:
        paymentAlertDialogBuilder.show();
    }
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    // Context exists
    assert getContext() != null;
    // BillingClient, new each time
    billingClient = BillingClient.newBuilder(getContext())
      .enablePendingPurchases()
      .setListener(this)
      .build();
    billingClient.startConnection(new BillingClientStateListener() {
      private long reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS;

      @Override
      public void onBillingServiceDisconnected() {
        skuDetailss.clear();
        retryBillingServiceConnectionWithExponentialBackoff();
      }

      @Override
      public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        logBillingResult("onBillingSetupFinished", billingResult);
        if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
          // The billing client is ready. You can query purchases here.
          // This doesn't mean that your app is set up correctly in the console -- it just
          // means that you have a connection to the Billing service.
          reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS;
          // Query SKU details asynchronously
          billingClient.querySkuDetailsAsync(
            SkuDetailsParams.newBuilder()
              .setType(BillingClient.SkuType.INAPP)
              .setSkusList(GOOGLE_CATALOG)
              .build(),
            (skuDetailsBillingResult, skuDetailsAnss) -> {
              logBillingResult("onSkuDetailsResponse", skuDetailsBillingResult);
              if (skuDetailsBillingResult.getResponseCode() ==
                BillingClient.BillingResponseCode.OK) {
                if (skuDetailsAnss == null || skuDetailsAnss.isEmpty()) {
                  Log.e(LOG_TAG, "onSkuDetailsResponse: " +
                    "Found null or empty SkuDetails. " +
                    "Check to see if the SKUs you requested are correctly published " +
                    "in the Google Play Console.");
                } else {
                  for (SkuDetails skuDetails : skuDetailsAnss) {
                    String sku = skuDetails.getSku();
                    if (GOOGLE_CATALOG.contains(sku)) {
                      skuDetailss.put(skuDetails.getSku(), skuDetails);
                    } else {
                      Log.e(LOG_TAG, "Unknown SKU: " + sku);
                    }
                  }
                }
              } else {
                retryBillingServiceConnectionWithExponentialBackoff();
              }
            });
        }
      }

      private void retryBillingServiceConnectionWithExponentialBackoff() {
        handler.postDelayed(() -> billingClient.startConnection(this), reconnectMilliseconds);
        reconnectMilliseconds =
          Math.min(reconnectMilliseconds * 2, RECONNECT_TIMER_MAX_TIME_MILLISECONDS);
      }
    });
  }

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    // Context exists
    assert getActivity() != null;
    return v -> {
      if (skuDetailss.isEmpty() || !billingClient.isReady()) {
        paymentAlertDialogBuilder.show();
      } else {
        String item = GOOGLE_CATALOG.get(googleSpinner.getSelectedItemPosition());
        Log.d(LOG_TAG, "Selected item in spinner: " + item);
        final SkuDetails skuDetails = skuDetailss.get(item);
        assert skuDetails != null;
        billingClient.launchBillingFlow(
          getActivity(),
          BillingFlowParams.newBuilder().setSkuDetails(skuDetails).build());
      }
    };
  }

  @Override
  public int getFloatingActionButtonResource() {
    return R.drawable.ic_payment_white_24dp;
  }

  @Override
  public int getTitle() {
    return R.string.title_donate;
  }

  @Override
  public void onActivityCreatedFiltered(@Nullable Bundle savedInstanceState) {
    // Context exists
    assert getContext() != null;
    // Adapters
    ArrayAdapter<CharSequence> donationAdapter = new ArrayAdapter<>(
      getContext(),
      android.R.layout.simple_spinner_item,
      getResources().getStringArray(R.array.donation_google_catalog_values));
    donationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    googleSpinner.setAdapter(donationAdapter);
  }

  @Nullable
  @Override
  protected View onCreateViewFiltered(
    @NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
    final View view = inflater.inflate(R.layout.content_donation, container, false);
    // Choose donation amount
    googleSpinner = view.findViewById(R.id.donation_google_android_market_spinner);
    // Alert dialog
    paymentAlertDialogBuilder = new AlertDialog.Builder(getContext())
      .setIcon(android.R.drawable.ic_dialog_alert)
      .setTitle(R.string.donation_alert_dialog_title)
      .setMessage(R.string.donation_alert_dialog_try_again)
      .setCancelable(true)
      .setNeutralButton(R.string.donation_button_close, (dialog, which) -> dialog.dismiss());
    return view;
  }

  private void logBillingResult(@NonNull String location, @NonNull BillingResult billingResult) {
    int responseCode = billingResult.getResponseCode();
    String debugMessage = billingResult.getDebugMessage();
    switch (responseCode) {
      case BillingClient.BillingResponseCode.OK:
      case BillingClient.BillingResponseCode.USER_CANCELED:
        Log.i(LOG_TAG, location + ": " + responseCode + "/" + debugMessage);
        break;
      case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
      case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
      case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
      case BillingClient.BillingResponseCode.ITEM_UNAVAILABLE:
      case BillingClient.BillingResponseCode.DEVELOPER_ERROR:
      case BillingClient.BillingResponseCode.ERROR:
        Log.e(LOG_TAG, location + ": " + responseCode + "/" + debugMessage);
        break;
      // These response codes are not expected
      case BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED:
      case BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED:
      case BillingClient.BillingResponseCode.ITEM_NOT_OWNED:
      default:
        Log.wtf(LOG_TAG, location + ": " + responseCode + "/" + debugMessage);
    }
  }
}