package me.invkrh.raft.util

import java.net.{Inet4Address, InetAddress, NetworkInterface, URI}

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

import akka.actor.{ActorNotFound, ActorRef, ActorSystem}

import me.invkrh.raft.deploy.raftSystemName
import me.invkrh.raft.exception.{MalformedAddressException, UnreachableAddressException}

object NetworkUtils extends Logging {

  case class CanonicalAddress(hostName: String, port: Int)

  object CanonicalAddress {
    def apply(address: String): CanonicalAddress = {
      val url =
        try {
          new URI("any://" + address) // http is just protocol placeholder
        } catch {
          case _: Throwable =>
            throw MalformedAddressException(address, "can not recognize host and port")
        }
      if (url.getHost == null) {
        throw MalformedAddressException(address, "can not retrieve host")
      } else if (url.getPort == -1) {
        throw MalformedAddressException(address, "can not retrieve port")
      } else {
        CanonicalAddress(url.getHost, url.getPort)
      }
    }
  }

  def resolveRefByName(systemName: String, hostName: String, port: Int, actorName: String)(
      implicit system: ActorSystem,
      timeout: FiniteDuration): ActorRef = {
    val target = s"akka://$raftSystemName@$hostName:$port/user/$actorName"
    try {
      Await.result(system.actorSelection(target).resolveOne(timeout), timeout)
    } catch {
      case _: ActorNotFound => throw UnreachableAddressException(target)
    }
  }

  def resolveRefByName(systemName: String, address: CanonicalAddress, actorName: String)(
      implicit system: ActorSystem,
      timeout: FiniteDuration): ActorRef = {
    resolveRefByName(systemName, address.hostName, address.port, actorName)
  }

  def resolveRefByName(systemName: String, address: String, actorName: String)(
      implicit system: ActorSystem,
      timeout: FiniteDuration): ActorRef = {
    resolveRefByName(systemName, CanonicalAddress(address), actorName)
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

        reOrderedNetworkIFs.view
          .flatMap { ni =>
            val addresses = ni.getInetAddresses.asScala
              .filterNot(addr => addr.isLinkLocalAddress || addr.isLoopbackAddress)
              .toSeq
            if (addresses.nonEmpty) {
              val addr = addresses.find(_.isInstanceOf[Inet4Address]).getOrElse(addresses.head)
              // because of Inet6Address.toHostName may add interface
              // at the end if it knows about it
              val strippedAddress = InetAddress.getByAddress(addr.getAddress)
              // We've found an address that looks reasonable!
              logWarn(
                "Your hostname, " + InetAddress.getLocalHost.getHostName + " resolves to" +
                  " a loopback address: " + address.getHostAddress + "; using " +
                  strippedAddress.getHostAddress + " instead (on interface " + ni.getName + ")")
              logWarn("Set RAFT_LOCAL_IP if you need to bind to another address")
              Some(strippedAddress.getHostAddress)
            } else {
              None
            }
          }
          .headOption
          .getOrElse {
            logWarn(
              "Your hostname, " + InetAddress.getLocalHost.getHostName + " resolves to" +
                " a loopback address: " + address.getHostAddress + ", but we couldn't find any" +
                " external IP address!")
            logWarn("Set RAFT_LOCAL_IP if you need to bind to another address")
            address.getHostAddress
          }
      } else {
        address.getHostAddress
      }
    }
  }
}
