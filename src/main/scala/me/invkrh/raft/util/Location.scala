package me.invkrh.raft.util

import java.net.URL

case class Location(host: String, port: Int) {
  override def toString: String = s"$host:$port"
}

object Location {
  def apply(hostAndPort: String): Location = {
    try {
      val url = new URL("http://" + hostAndPort)
      Location(url.getHost, url.getPort)
    } catch {
      case _: Exception =>
        throw new IllegalArgumentException(s"Listener is malformed with: $hostAndPort")
    }
  }
}
