package me.invkrh.raft.deploy.remote

import com.typesafe.config.Config

import me.invkrh.raft.deploy.ConfigHolder
import me.invkrh.raft.exception.InvalidArgumentsException
import me.invkrh.raft.kit.TestHarness

class Initiator(val config: Config) extends ClusterInitiatorRemote with ConfigHolder

class ClusterInitiatorRemoteTest extends TestHarness {
  "ClusterInitiatorRemote" should {
    val init = new Initiator(this.config)
    "parse arguments of main function using flag" in {
      init.parse(List("--host", "localhost", "--port", "1234"))
      assertResult("localhost") {
        init.host
      }
      assertResult(1234) {
        init.port
      }
    }
    "parse arguments of main function using abbreviation" in {
      init.parse(List("-h", "local", "-p", "4321"))
      assertResult("local") {
        init.host
      }
      assertResult(4321) {
        init.port
      }
    }
    "throw exception if arguments are not valid" in {
      intercept[InvalidArgumentsException] {
        init.parse(List("-hh", "local", "-p", "4321"))
      }
    }
  }
}
