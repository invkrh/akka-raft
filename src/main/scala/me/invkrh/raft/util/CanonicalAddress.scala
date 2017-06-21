package me.invkrh.raft.util

import java.net.{URI, URL}

import me.invkrh.raft.exception.MalformedAddressException

case class CanonicalAddress(hostName: String, port: Int)
object CanonicalAddress {
  def apply(address: String): CanonicalAddress = {
    try {
      val url = new URI("any://" + address) // http is just protocol placeholder
      if (url.getHost == null || url.getPort == -1) {
        throw new Exception()
      }
      CanonicalAddress(url.getHost, url.getPort)
    } catch {
      case e: Throwable => throw MalformedAddressException(address)
    }
  }
}
