package org.maxbot.app;
import android.content.Context;
import android.net.*;
import java.net.*;
final class WifiBinding {
  static Network network(Context c){ConnectivityManager cm=(ConnectivityManager)c.getSystemService(Context.CONNECTIVITY_SERVICE);for(Network n:cm.getAllNetworks()){NetworkCapabilities caps=cm.getNetworkCapabilities(n);if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return n;}return null;}
  static void bind(Context c,DatagramSocket socket)throws java.io.IOException {Network n=network(c);if(n==null)throw new java.io.IOException("Join ESP Wi-Fi first");n.bindSocket(socket);}
}
