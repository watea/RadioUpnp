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

import androidx.annotation.NonNull;

import com.watea.radio_upnp.adapter.UpnpPlayerAdapter;
import com.watea.radio_upnp.service.Exporter;
import com.watea.radio_upnp.service.HttpService;

import org.fourthline.cling.DefaultUpnpServiceConfiguration;
import org.fourthline.cling.android.AndroidNetworkAddressFactory;
import org.fourthline.cling.binding.xml.DeviceDescriptorBinder;
import org.fourthline.cling.binding.xml.RecoveringUDA10DeviceDescriptorBinderImpl;
import org.fourthline.cling.binding.xml.ServiceDescriptorBinder;
import org.fourthline.cling.binding.xml.UDA10ServiceDescriptorBinderSAXImpl;
import org.fourthline.cling.model.Namespace;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.transport.impl.AsyncServletStreamServerConfigurationImpl;
import org.fourthline.cling.transport.impl.AsyncServletStreamServerImpl;
import org.fourthline.cling.transport.impl.RecoveringGENAEventProcessorImpl;
import org.fourthline.cling.transport.impl.RecoveringSOAPActionProcessorImpl;
import org.fourthline.cling.transport.spi.GENAEventProcessor;
import org.fourthline.cling.transport.spi.NetworkAddressFactory;
import org.fourthline.cling.transport.spi.SOAPActionProcessor;
import org.fourthline.cling.transport.spi.StreamServer;

/**
 * Configuration settings for deployment on Android.
 * <p>
 * This configuration utilizes the Jetty transport implementation
 * found in {@link org.fourthline.cling.transport.impl.jetty} for TCP/HTTP networking, as
 * client and server. The servlet context path for UPnP is set to <code>/upnp</code>.
 * </p>
 * <p>
 * The kxml2 implementation of <code>org.xmlpull</code> is available on Android, therefore
 * this configuration uses {@link RecoveringUDA10DeviceDescriptorBinderImpl},
 * {@link RecoveringSOAPActionProcessorImpl}, and {@link RecoveringGENAEventProcessorImpl}.
 * </p>
 * <p>
 * This configuration utilizes {@link UDA10ServiceDescriptorBinderSAXImpl}, the system property
 * <code>org.xml.sax.driver</code> is set to  <code>org.xmlpull.v1.sax2.Driver</code>.
 * </p>
 * <p>
 * To preserve battery, the {@link org.fourthline.cling.registry.Registry} will only
 * be maintained every 3 seconds.
 * </p>
 *
 * @author Christian Bauer
 */
public class UpnpServiceConfiguration extends DefaultUpnpServiceConfiguration {
  @NonNull
  private final HttpService.HttpServer httpServer;

  public UpnpServiceConfiguration(@NonNull HttpService.HttpServer httpServer) {
    this(0, httpServer); // Ephemeral port
  }

  public UpnpServiceConfiguration(
    int streamListenPort, @NonNull HttpService.HttpServer httpServer) {
    super(streamListenPort, false);
    this.httpServer = httpServer;
    // This should be the default on Android 2.1 but it's not set by default
    System.setProperty("org.xml.sax.driver", "org.xmlpull.v1.sax2.Driver");
  }

  @Override
  public org.fourthline.cling.transport.spi.StreamClient<?> createStreamClient() {
    // Use Jetty
    return new StreamClient(new StreamClientConfiguration(getSyncProtocolExecutorService()));
  }

  @Override
  public StreamServer<?> createStreamServer(NetworkAddressFactory networkAddressFactory) {
    // Use Jetty, start/stop a new shared instance of JettyServletContainer
    return new AsyncServletStreamServerImpl(new AsyncServletStreamServerConfigurationImpl(
      new JettyServletContainer(httpServer), networkAddressFactory.getStreamListenPort()));
  }

  @Override
  public int getRegistryMaintenanceIntervalMillis() {
    return 5000; // Preserve battery on Android, only run every 5 seconds
  }

  @Override
  protected NetworkAddressFactory createNetworkAddressFactory(int streamListenPort) {
    return new AndroidNetworkAddressFactory(streamListenPort);
  }

  @Override
  protected SOAPActionProcessor createSOAPActionProcessor() {
    return new RecoveringSOAPActionProcessorImpl();
  }

  @Override
  protected GENAEventProcessor createGENAEventProcessor() {
    return new RecoveringGENAEventProcessorImpl();
  }

  @Override
  protected DeviceDescriptorBinder createDeviceDescriptorBinderUDA10() {
    return new RecoveringUDA10DeviceDescriptorBinderImpl();
  }

  @Override
  protected ServiceDescriptorBinder createServiceDescriptorBinderUDA10() {
    return new UDA10ServiceDescriptorBinderSAXImpl();
  }

  @Override
  protected Namespace createNamespace() {
    // For the Jetty server, this is the servlet context path
    return new Namespace("/upnp");
  }
}