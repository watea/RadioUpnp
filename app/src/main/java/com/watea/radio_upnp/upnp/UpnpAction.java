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

package com.watea.radio_upnp.upnp;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

public abstract class UpnpAction {
  private final String LOG_TAG = UpnpAction.class.getName();
  @NonNull
  private final Action action;
  @NonNull
  private final ActionController actionController;
  private final List<UpnpAction.Argument> arguments = new Vector<>();
  private final Map<String, String> responses = new HashMap<>();

  public UpnpAction(
    @NonNull Action action,
    @NonNull ActionController actionController) {
    this.action = action;
    this.actionController = actionController;
  }

  public UpnpAction(
    @NonNull Action action,
    @NonNull ActionController actionController,
    @NonNull String instanceId) {
    this(action, actionController);
    addArgument("InstanceID", instanceId);
  }

  @Nullable
  public String getResponse(@NonNull String Name) {
    return responses.get(Name);
  }

  public UpnpAction addArgument(@NonNull String name, @NonNull String value) {
    arguments.add(new Argument(name, value));
    return this;
  }

  public void execute(boolean isOnOwnThread) {
    Log.d(
      LOG_TAG, "execute: " + action.getName() + " on: " + action.getDevice().getDisplayString());
    final Request request = new Request(action.getService(), action.getName(), arguments) {
      @Override
      public void onSuccess(@NonNull Map<String, String> responses) {
        Log.d(LOG_TAG, "Successfully called UPnP action: " + action.getName());
        UpnpAction.this.responses.putAll(responses);
        UpnpAction.this.onSuccess();
      }

      @Override
      public void onFailure(
        @NonNull String faultCode, @NonNull String faultString, @NonNull String faultDetail) {
        Log.d(LOG_TAG, "Failed to call UPnP action: " + action.getName() + " => " + faultCode + "/" + faultString + "/" + faultDetail);
        UpnpAction.this.onFailure();
      }
    };
    if (isOnOwnThread) {
      new Thread(request::call).start();
    } else {
      request.call();
    }
  }

  public void schedule() {
    actionController.schedule(this);
  }

  public boolean hasDevice(@NonNull Device device) {
    return action.getDevice().equals(device);
  }

  // Run next by default
  protected void onSuccess() {
    actionController.runNextAction();
  }

  // Run next by default
  protected void onFailure() {
    actionController.runNextAction();
  }

  public static class Argument {
    @NonNull
    private final String key;
    @NonNull
    private final String value;

    public Argument(@NonNull String key, @NonNull String value) {
      this.key = key;
      this.value = value;
    }

    @NonNull
    public String getKey() {
      return key;
    }

    @NonNull
    public String getValue() {
      return value;
    }
  }
}