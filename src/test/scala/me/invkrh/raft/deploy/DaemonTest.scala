package me.invkrh.raft.deploy

import com.typesafe.config.ConfigValueFactory

import me.invkrh.raft.exception._
import me.invkrh.raft.kit.RaftTestHarness

class DaemonTest extends RaftTestHarness("DaemonSpec") {
  "DaemonTest" should {
    "throw IllegalArgumentException when number of arguments is not correct" in {
      intercept[IllegalArgumentException] {
        Daemon("abcd")
      }

      intercept[IllegalArgumentException] {
        Daemon("abcd", "abcd", "abcd")
      }
    }

    "throw RaftConfigurationFileNotFoundException if the config file does not exist" in {
      intercept[RaftConfigurationFileNotFoundException] {
        Daemon("abcd", "start")
      }
    }

    "throw UnreachableAddressException when joining and precusor system can not resolved" in {
      intercept[UnreachableAddressException] {
        Daemon(config, "join")
      }
    }

    "throw UnreachableAddressException when stopping and precusor system can not resolved" in {
      intercept[UnreachableAddressException] {
        Daemon(config, "stop")
      }
    }

    "throw UnreachableAddressException when stopping-all and precusor system can not resolved" in {
      intercept[UnreachableAddressException] {
        Daemon(config, "stop-all")
      }
    }

    "throw InvalidArgumentException when action is not defined" in {
      intercept[InvalidArgumentException] {
        Daemon(config, "other")
      }
    }
  }
}
