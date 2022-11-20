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
import android.view.View;
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
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.google.common.collect.ImmutableList;
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
  private static final List<QueryProductDetailsParams.Product> GOOGLE_PRODUCTS = Arrays.asList(
    getProduct("radio_upnp.donation.1"),
    getProduct("radio_upnp.donation.2"),
    getProduct("radio_upnp.donation.3"));
  private static final Handler handler = new Handler(Looper.getMainLooper());
  private final Map<String, ProductDetails> ownProductDetailss = new Hashtable<>();
  private BillingClient billingClient;
  // <HMI assets
  private Spinner googleSpinner;
  private AlertDialog.Builder paymentAlertDialogBuilder;
  // />

  @NonNull
  private static QueryProductDetailsParams.Product getProduct(@NonNull String name) {
    return QueryProductDetailsParams.Product.newBuilder()
      .setProductId(name)
      .setProductType(BillingClient.ProductType.INAPP)
      .build();
  }

  @Override
  public void onConsumeResponse(@NonNull BillingResult billingResult, @NonNull String s) {
    logBillingResult("onConsumeResponse", billingResult);
    Log.d(LOG_TAG, "End consumption flow: " + s);
  }

  @Override
  public void onPurchasesUpdated(
    @NonNull BillingResult billingResult,
    @Nullable List<com.android.billingclient.api.Purchase> purchases) {
    logBillingResult("onPurchasesUpdated", billingResult);
    switch (billingResult.getResponseCode()) {
      case BillingClient.BillingResponseCode.OK:
        // Consume purchase
        if (purchases == null) {
          Log.e(LOG_TAG, "onPurchasesUpdated: purchase list is null");
        } else {
          for (com.android.billingclient.api.Purchase purchase : purchases) {
            Log.d(LOG_TAG, "onPurchasesUpdated: " + purchase);
            billingClient.consumeAsync(
              ConsumeParams.newBuilder().setPurchaseToken(purchase.getPurchaseToken()).build(),
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

  @NonNull
  @Override
  public View.OnClickListener getFloatingActionButtonOnClickListener() {
    return v -> {
      if (ownProductDetailss.isEmpty() || !billingClient.isReady()) {
        paymentAlertDialogBuilder.show();
      } else {
        final String productName =
          GOOGLE_PRODUCTS.get(googleSpinner.getSelectedItemPosition()).zza();
        Log.d(LOG_TAG, "Selected item in spinner: " + productName);
        final ProductDetails productDetails = ownProductDetailss.get(productName);
        assert productDetails != null;
        billingClient.launchBillingFlow(
          getMainActivity(),
          BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(ImmutableList.of(
              BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()))
            .build());
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
  protected int getLayout() {
    return R.layout.content_donation;
  }

  @Override
  public void onCreateView(@NonNull View view) {
    // Choose donation amount
    googleSpinner = view.findViewById(R.id.donation_google_android_market_spinner);
    // Alert dialog
    paymentAlertDialogBuilder = new AlertDialog.Builder(getContext(), R.style.AlertDialogStyle)
      .setIcon(android.R.drawable.ic_dialog_alert)
      .setTitle(R.string.donation_alert_dialog_title)
      .setMessage(R.string.donation_alert_dialog_try_again)
      .setCancelable(true)
      .setNeutralButton(R.string.donation_button_close, (dialog, which) -> dialog.dismiss());
    // Adapters
    assert getContext() != null;
    ArrayAdapter<CharSequence> donationAdapter = new ArrayAdapter<>(
      getContext(),
      android.R.layout.simple_spinner_item,
      getResources().getStringArray(R.array.donation_google_catalog_values));
    donationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    googleSpinner.setAdapter(donationAdapter);
  }

  @Override
  public void onActivityCreated(@Nullable Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
    // BillingClient, new each time
    assert getContext() != null;
    billingClient = BillingClient.newBuilder(getContext())
      .enablePendingPurchases()
      .setListener(this)
      .build();
    billingClient.startConnection(new BillingClientStateListener() {
      private long reconnectMilliseconds = RECONNECT_TIMER_START_MILLISECONDS;

      @Override
      public void onBillingServiceDisconnected() {
        ownProductDetailss.clear();
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
          // Query product details asynchronously
          billingClient.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(GOOGLE_PRODUCTS).build(),
            (billingClientResult, productDetailss) -> {
              logBillingResult("productDetailsResponseListener", billingClientResult);
              if (billingClientResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                if (productDetailss.isEmpty()) {
                  Log.e(LOG_TAG, "productDetailsResponseListener: null or empty response." +
                    "Check to see if the billing you requested are correctly published " +
                    "in the Google Play Console.");
                } else {
                  for (ProductDetails productDetails : productDetailss) {
                    ownProductDetailss.put(productDetails.getProductId(), productDetails);
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

  private void logBillingResult(@NonNull String location, @NonNull BillingResult billingResult) {
    final int responseCode = billingResult.getResponseCode();
    final String debugMessage = billingResult.getDebugMessage();
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