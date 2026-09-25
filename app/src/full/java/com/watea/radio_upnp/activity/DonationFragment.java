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

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.ConsumeParams;
import com.android.billingclient.api.ConsumeResponseListener;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryPurchasesParams;
import com.google.common.collect.ImmutableList;
import com.watea.radio_upnp.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class DonationFragment extends OpenDonationFragment
  implements ConsumeResponseListener, PurchasesUpdatedListener {
  private static final String LOG_TAG = DonationFragment.class.getSimpleName();
  private final Map<String, ProductDetails> ownProductDetailss = new HashMap<>();
  private BillingClient billingClient;
  private String[] productIds;

  // Recovers purchases left unconsumed at each app start, e.g. slow payments completed after the
  // donation screen was left; otherwise Google refunds them after 3 days
  public static void consumePendingPurchases(@NonNull Context context) {
    final BillingClient client = BillingClient.newBuilder(context.getApplicationContext())
      .setListener((billingResult, purchases) -> {
      })
      .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
      .build();
    client.startConnection(new BillingClientStateListener() {
      @Override
      public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
        if (billingResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
          Log.e(LOG_TAG, "consumePendingPurchases: setup failed: " + billingResult.getDebugMessage());
          client.endConnection();
          return;
        }
        client.queryPurchasesAsync(
          QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
          (queryResult, purchases) -> {
            if (queryResult.getResponseCode() != BillingClient.BillingResponseCode.OK) {
              Log.e(LOG_TAG, "consumePendingPurchases: query failed: " + queryResult.getDebugMessage());
              client.endConnection();
              return;
            }
            // Connection is released once every consumption has answered
            final AtomicInteger remaining = new AtomicInteger(1);
            final ConsumeResponseListener listener = (consumeResult, purchaseToken) -> {
              logConsumeResponse(consumeResult);
              if (remaining.decrementAndGet() == 0) {
                client.endConnection();
              }
            };
            for (final Purchase purchase : purchases) {
              if (isPurchased(purchase)) {
                remaining.incrementAndGet();
                consume(client, purchase, listener);
              }
            }
            if (remaining.decrementAndGet() == 0) {
              client.endConnection();
            }
          });
      }

      @Override
      public void onBillingServiceDisconnected() {
        // Nothing to do: next app start retries
      }
    });
  }

  private static boolean isPurchased(@NonNull Purchase purchase) {
    final boolean result = (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED);
    if (!result) {
      Log.i(LOG_TAG, "Purchase not completed, state: " + purchase.getPurchaseState());
    }
    return result;
  }

  private static void consume(
    @NonNull BillingClient client,
    @NonNull Purchase purchase,
    @NonNull ConsumeResponseListener listener) {
    client.consumeAsync(ConsumeParams.newBuilder().setPurchaseToken(purchase.getPurchaseToken()).build(), listener);
  }

  private static void logConsumeResponse(@NonNull BillingResult billingResult) {
    if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
      Log.i(LOG_TAG, "Donation consumed, thank you!");
    } else {
      Log.e(LOG_TAG, "Consumption failed: " + billingResult.getDebugMessage());
    }
  }

  @Override
  public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);
    // Resource for Products Ids
    productIds = getResources().getStringArray(R.array.google_products);
    // Visibility
    view.findViewById(R.id.google_charges_text_view).setVisibility(View.VISIBLE);
    view.findViewById(R.id.google_pay_image_button).setVisibility(View.VISIBLE);
    view.findViewById(R.id.google_or_use_text_view).setVisibility(View.VISIBLE);
    // Choose donation amount
    final Spinner googleSpinner = view.findViewById(R.id.donation_google_android_market_spinner);
    googleSpinner.setVisibility(View.VISIBLE);
    // Buttons and listeners
    view.findViewById(R.id.google_pay_image_button).setOnClickListener(buttonView -> {
      if (ownProductDetailss.isEmpty() || !billingClient.isReady()) {
        tell(R.string.donation_error);
      } else {
        final ProductDetails productDetails = ownProductDetailss.get(productIds[googleSpinner.getSelectedItemPosition()]);
        if ((productDetails == null) || (productDetails.getOneTimePurchaseOfferDetails() == null)) {
          tell(R.string.donation_error);
        } else {
          final BillingFlowParams billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(ImmutableList.of(
              BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .build()
            ))
            .build();
          billingClient.launchBillingFlow(requireActivity(), billingFlowParams);
        }
      }
    });
    initGoogleBillingClient();
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    // A new client is created on each onViewCreated()
    if (billingClient != null) {
      billingClient.endConnection();
    }
  }

  @Override
  public void onPurchasesUpdated(@NonNull BillingResult billingResult, @Nullable List<Purchase> purchases) {
    if ((billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) && (purchases != null)) {
      for (final Purchase purchase : purchases) {
        // Pending ones are consumed at a later app start
        if (isPurchased(purchase)) {
          consume(billingClient, purchase, this);
        }
      }
    } else if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.USER_CANCELED) {
      Log.i(LOG_TAG, "Purchase canceled by user");
    } else {
      Log.e(LOG_TAG, "Purchase failed: " + billingResult.getDebugMessage());
    }
  }

  @Override
  public void onConsumeResponse(@NonNull BillingResult billingResult, @NonNull String purchaseToken) {
    logConsumeResponse(billingResult);
  }

  private void initGoogleBillingClient() {
    try {
      billingClient = BillingClient.newBuilder(requireContext())
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build();
      billingClient.startConnection(new BillingClientStateListener() {
        @Override
        public void onBillingSetupFinished(@NonNull BillingResult billingResult) {
          if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
            Log.d(LOG_TAG, "Billing Client ready");
            queryGoogleProducts();
          } else {
            Log.e(LOG_TAG, "Billing Setup Failed: " + billingResult.getDebugMessage());
          }
        }

        @Override
        public void onBillingServiceDisconnected() {
          // Reconnection is automatic on next request
          Log.w(LOG_TAG, "Billing Service Disconnected");
        }
      });
    } catch (IllegalStateException illegalStateException) {
      Log.e(LOG_TAG, "Billing Client creation failed", illegalStateException);
    }
  }

  private void queryGoogleProducts() {
    final List<QueryProductDetailsParams.Product> googleProducts = new ArrayList<>();
    for (final String productId : productIds) {
      googleProducts.add(QueryProductDetailsParams.Product.newBuilder()
        .setProductId(productId)
        .setProductType(BillingClient.ProductType.INAPP)
        .build());
    }
    final QueryProductDetailsParams queryProductDetailsParams = QueryProductDetailsParams.newBuilder()
      .setProductList(googleProducts)
      .build();
    billingClient.queryProductDetailsAsync(queryProductDetailsParams, (billingResult, productDetailsResult) -> {
      if (billingResult.getResponseCode() == BillingClient.BillingResponseCode.OK) {
        final List<ProductDetails> productDetailss = productDetailsResult.getProductDetailsList();
        for (final ProductDetails productDetails : productDetailss) {
          ownProductDetailss.put(productDetails.getProductId(), productDetails);
        }
      } else {
        Log.e(LOG_TAG, "Query Product Details failed: " + billingResult.getDebugMessage());
      }
    });
  }
}