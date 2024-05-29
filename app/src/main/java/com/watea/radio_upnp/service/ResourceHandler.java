package com.watea.radio_upnp.service;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import androidx.annotation.NonNull;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

import fi.iki.elonen.NanoHTTPD;

// Récupérer le chemin du fichier demandé
// Remplacer "/monfichier.html" par le chemin réel de votre fichier
// L'utilisateur demande le fichier spécifié
// Vous pouvez lire le contenu du fichier et le renvoyer ici
// Exemple : lire le contenu du fichier monfichier.html
// Fichier non trouvé

public class ResourceHandler implements NanoHttpServer.Handler {
  private static final int TIMEOUT_RECEPTION_REPONSE = 10;
  private static final String ROOT = "/"; // Répertoire racine
  private static final String PATH = "/"; // Chemin vers le fichier image
  public String getMonAdresseIP() {
    try {
      for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
        NetworkInterface intf = en.nextElement();
        for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
          InetAddress inetAddress = enumIpAddr.nextElement();
          if (!inetAddress.isLoopbackAddress())
            return inetAddress.getHostAddress();
        }
      }
    } catch (SocketException e) {
      e.printStackTrace();
    }
    return null;
  }
  private InetAddress getAddressBroadcast(@NonNull Context context) throws UnknownHostException {
    WifiManager WiFiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    DhcpInfo dhcp = WiFiManager.getDhcpInfo();

    int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
    byte[] quads = new byte[4];
    for (int k = 0; k < 4; k++)
      quads[k] = (byte) ((broadcast >> k * 8) & 0xFF);
    return InetAddress.getByAddress(quads);
  }

  private DatagramPacket envoyerTrameUDP(@NonNull Context context, String requete, int port) throws Exception {
    DatagramSocket socket = new DatagramSocket(port);
    socket.setBroadcast(true);
    InetAddress broadcastAdress = getAddressBroadcast(context);
    DatagramPacket packet = new DatagramPacket(requete.getBytes(), requete.length(), broadcastAdress, port);
    socket.send(packet);

    byte[] buf = new byte[1024];
    packet = new DatagramPacket(buf, buf.length);
    socket.setSoTimeout(TIMEOUT_RECEPTION_REPONSE);

    String monAdresse = getMonAdresseIP();
    socket.receive(packet);
    while (packet.getAddress().getHostAddress().contains(monAdresse)) {
      socket.receive(packet);
    }

    socket.close();

    return packet;
  }
  @Override
  public NanoHTTPD.Response handle(@NonNull NanoHTTPD.IHTTPSession iHTTPSession) {
    // Récupérer le chemin du fichier demandé
    String uri = iHTTPSession.getUri();
    // Remplacer "/monfichier.html" par le chemin réel de votre fichier
    String cheminFichier = "/monfichier.html";

    if (uri.equals(cheminFichier)) {
      // L'utilisateur demande le fichier spécifié
      // Vous pouvez lire le contenu du fichier et le renvoyer ici
      // Exemple : lire le contenu du fichier monfichier.html
      String contenuFichier = "Contenu du fichier monfichier.html";
      return newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/html", contenuFichier);
    } else {
      // Fichier non trouvé
      return newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Fichier non trouvé");
    }
  }
}
