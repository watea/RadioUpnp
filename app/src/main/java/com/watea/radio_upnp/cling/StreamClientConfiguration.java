/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.watea.radio_upnp.cling;

import android.os.Build;

import org.fourthline.cling.model.ServerClientTokens;
import org.fourthline.cling.transport.spi.AbstractStreamClientConfiguration;

import java.util.concurrent.ExecutorService;

/**
 * Settings for the Jetty 9 implementation.
 *
 * @author Christian Bauer
 */
public class StreamClientConfiguration extends AbstractStreamClientConfiguration {

  public StreamClientConfiguration(ExecutorService timeoutExecutorService) {
    super(timeoutExecutorService);
  }

  @Override
  public String getUserAgentValue(int majorVersion, int minorVersion) {
    final ServerClientTokens tokens = new ServerClientTokens(majorVersion, minorVersion);
    tokens.setOsName("Android");
    tokens.setOsVersion(Build.VERSION.RELEASE);
    return tokens.toString();
  }
}