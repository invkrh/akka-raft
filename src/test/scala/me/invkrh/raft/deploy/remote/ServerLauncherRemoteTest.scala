package me.invkrh.raft.deploy.remote

import me.invkrh.raft.exception.{InvalidArgumentsException, MalformedAddressException}
import me.invkrh.raft.kit.TestHarness
import me.invkrh.raft.util.CanonicalAddress

class ServerLauncherRemoteTest extends TestHarness {
  "ServerLauncherRemote" should {
    val launch = new Launcher()
    "parse arguments of main function using flag" in {
      launch.parse(List("--host", "localhost", "--port", "1234", "--init", "localhost:8080"))
      assertResult("localhost") {
        launch.host
      }
      assertResult(1234) {
        launch.port
      }
      assertResult(CanonicalAddress("localhost", 8080)) {
        launch.initAddr
      }
    }
    "parse arguments of main function using abbreviation" in {
      launch.parse(List("-h", "local", "-p", "4321", "-i", "localhost:4040"))
      assertResult("local") {
        launch.host
      }
      assertResult(4321) {
        launch.port
      }
      assertResult(CanonicalAddress("localhost", 4040)) {
        launch.initAddr
      }
    }
    "throw exception if arguments are not valid" in {
      intercept[InvalidArgumentsException] {
        launch.parse(List("-hh", "local", "-p", "4321"))
      }
    }
    "throw exception if initiator address is not valid" in {
      intercept[MalformedAddressException] {
        launch.parse(List("-h", "local", "-p", "4321", "-i", "localhost4040"))
      }
    }
  }
}
