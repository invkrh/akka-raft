package me.invkrh.raft.util

import java.net.URL

case class Location(hostName: String, port: Int) {
  override def toString: String = s"$hostName:$port"
}

object Location {
  def apply(address: String): Location = {
    try {
      val url = new URL("http://" + address)
      Location(url.getHost, url.getPort)
    } catch {
      case _: Exception =>
        throw new IllegalArgumentException(s"Listener is malformed with: $address")
    }
  }
}
