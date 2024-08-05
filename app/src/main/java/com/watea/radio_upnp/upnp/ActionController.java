/*
 * Copyright (c) 2024. Stephane Treuchot
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

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public class ActionController {
  private final List<UpnpAction> upnpActions = new ArrayList<>();

  public synchronized void release() {
    upnpActions.clear();
  }

  public synchronized void release(@NonNull Device device) {
    upnpActions.removeIf(upnpAction -> upnpAction.hasDevice(device));
  }

  public synchronized void runNextAction() {
    if (!upnpActions.isEmpty()) {
      upnpActions.remove(0);
      pullAction(false);
    }
  }

  public synchronized void schedule(@NonNull UpnpAction upnpAction) {
    upnpActions.add(upnpAction);
    // First action? => Start new thread
    if (upnpActions.size() == 1) {
      pullAction(true);
    }
  }

  private void pullAction(boolean isOnOwnThread) {
    if (!upnpActions.isEmpty()) {
      upnpActions.get(0).execute(isOnOwnThread);
    }
  }
}