package me.invkrh.raft.util

import java.net.URL

import me.invkrh.raft.exception.MalformedAddressException

case class CanonicalAddress(hostName: String, port: Int)
object CanonicalAddress {
  def apply(address: String): CanonicalAddress = {
    try {
      val url = new URL("http://" + address) // http is just protocol placeholder
      CanonicalAddress(url.getHost, url.getPort)
    } catch {
      case e: Throwable => throw MalformedAddressException(address)
    }
  }
}
