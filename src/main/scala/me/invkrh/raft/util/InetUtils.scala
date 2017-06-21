package me.invkrh.raft.util

import java.net.{Inet4Address, InetAddress, NetworkInterface}

import scala.collection.JavaConverters._

object InetUtils extends Logging {
  def findLocalInetAddress(): String = {
    val defaultIpOverride = System.getenv("RAFT_LOCAL_IP")
    if (defaultIpOverride != null) {
      InetAddress.getByName(defaultIpOverride).getHostAddress
    } else {
      val address = InetAddress.getLocalHost
      if (address.isLoopbackAddress) {
        // Address resolves to something like 127.0.1.1, which happens on Debian; try to find
        // a better address using the local network interfaces
        // getNetworkInterfaces returns ifs in reverse order compared to ifconfig output order
        // on unix-like system. On windows, it returns in index order.
        // It's more proper to pick ip address following system output order.
        val activeNetworkIFs = NetworkInterface.getNetworkInterfaces.asScala.toSeq
        val reOrderedNetworkIFs = activeNetworkIFs.reverse

        for (ni <- reOrderedNetworkIFs) {
          val addresses = ni.getInetAddresses.asScala
            .filterNot(addr => addr.isLinkLocalAddress || addr.isLoopbackAddress)
            .toSeq
          if (addresses.nonEmpty) {
            val addr = addresses.find(_.isInstanceOf[Inet4Address]).getOrElse(addresses.head)
            // because of Inet6Address.toHostName may add interface at the end if it knows about it
            val strippedAddress = InetAddress.getByAddress(addr.getAddress)
            // We've found an address that looks reasonable!
            logWarn(
              "Your hostname, " + InetAddress.getLocalHost.getHostName + " resolves to" +
                " a loopback address: " + address.getHostAddress + "; using " +
                strippedAddress.getHostAddress + " instead (on interface " + ni.getName + ")"
            )
            logWarn("Set RAFT_LOCAL_IP if you need to bind to another address")
            return strippedAddress.getHostAddress
          }
        }
        logWarn(
          "Your hostname, " + InetAddress.getLocalHost.getHostName + " resolves to" +
            " a loopback address: " + address.getHostAddress + ", but we couldn't find any" +
            " external IP address!"
        )
        logWarn("Set RAFT_LOCAL_IP if you need to bind to another address")
      }
      address.getHostAddress
    }
  }
}
