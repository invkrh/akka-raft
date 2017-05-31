package me.invkrh.raft.deploy

import java.net.{Inet4Address, InetAddress, NetworkInterface}

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

import me.invkrh.raft.util.Logging

trait RemoteProvider extends Logging {
  val systemName: String = "RemoteSystem"

  def systemShutdownHook(): Unit = {
    logInfo(s"System [$systemName] has been shut down")
  }

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

  // System is heavy, create as needed
  // The problem of being a singleton due to actor name conflict in the same system
  def createSystem(hostName: String = findLocalInetAddress(), port: Int = 0): ActorSystem = {
    val config = Map(
      "akka.actor.provider" -> "remote",
      "akka.remote.artery.enabled" -> "on",
      "akka.remote.artery.canonical.hostname" -> hostName,
      "akka.remote.artery.canonical.port" -> port.toString
    ).asJava
    val conf = ConfigFactory.parseMap(config).withFallback(ConfigFactory.load())
    val sys = ActorSystem(systemName, conf)
    sys.whenTerminated foreach { _ =>
      systemShutdownHook()
    }
    sys
  }
}
