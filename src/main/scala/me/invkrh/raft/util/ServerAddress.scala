package me.invkrh.raft.util

import java.net.URL

case class ServerAddress(host: String, port: Int)

object ServerAddress {
  def apply(hostAndPort: String): ServerAddress = {
    try {
      val url = new URL("http://" + hostAndPort)
      ServerAddress(url.getHost, url.getPort)
    } catch {
      case _: Exception =>
        throw new IllegalArgumentException(s"Listener is malformed with: $hostAndPort")
    }
  }
}
