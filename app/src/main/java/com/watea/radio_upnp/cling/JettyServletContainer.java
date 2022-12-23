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

import android.util.Log;

import androidx.annotation.NonNull;

import com.watea.radio_upnp.service.HttpService;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.fourthline.cling.transport.spi.ServletContainerAdapter;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

import javax.servlet.Servlet;

/**
 * A singleton wrapper of a <code>org.eclipse.jetty.server.Server</code>.
 * <p>
 * This {@link ServletContainerAdapter} starts
 * a Jetty 9 instance on its own and stops it. Only one single context and servlet
 * is registered, to handle UPnP requests.
 * </p>
 * <p>
 * This implementation works on Android, dependencies are <code>jetty-server</code>
 * and <code>jetty-servlet</code> Maven modules.
 * </p>
 *
 * @author Christian Bauer
 */
public class JettyServletContainer implements ServletContainerAdapter {
  private static final String LOG_TAG = JettyServletContainer.class.getName();
  @NonNull
  private final HttpService.HttpServer httpServer;

  public JettyServletContainer(@NonNull HttpService.HttpServer httpServer) {
    Log.d(LOG_TAG, "Creating JettyServletContainer");
    this.httpServer = httpServer;
  }

  // Nothing to do here
  @Override
  public synchronized void setExecutorService(ExecutorService executorService) {
  }

  @Override
  public synchronized int addConnector(String host, int port) throws IOException {
    final Server server = httpServer.getServer();
    final ServerConnector connector = new ServerConnector(server);
    connector.setHost(host);
    connector.setPort(port);
    // Open immediately so we can get the assigned local port
    connector.open();
    // Only add if open() succeeded
    httpServer.getServer().addConnector(connector);
    // stats the connector if the server is started (server starts all connectors when started)
    if (server.isStarted()) {
      try {
        connector.start();
      } catch (Exception ex) {
        Log.e(LOG_TAG, "Couldn't start connector: " + connector, ex);
        throw new RuntimeException(ex);
      }
    }
    return connector.getLocalPort();
  }

  @Override
  public synchronized void removeConnector(String host, int port) {
    stopIfRunning();
  }

  @Override
  public synchronized void registerServlet(String contextPath, Servlet servlet) {
    Log.i(LOG_TAG, "Registering UPnP servlet under context path: " + contextPath);
    final ServletContextHandler servletHandler =
      new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
    if ((contextPath != null) && (contextPath.length() > 0)) {
      servletHandler.setContextPath(contextPath);
    }
    servletHandler.addServlet(new ServletHolder(servlet), "/*");
    httpServer.addHandler(servletHandler);
  }

  // Nothing to do here
  @Override
  public void startIfNotRunning() {
  }

  // Nothing to do here
  @Override
  public void stopIfRunning() {
  }
}