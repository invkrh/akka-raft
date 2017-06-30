package me.invkrh.raft.util

import me.invkrh.raft.deploy.RaftConfig
import me.invkrh.raft.exception.{
  RaftConfigDirectoryNotFoundException,
  RaftConfigFileNotFoundException
}
import me.invkrh.raft.kit.{SysEnvUtils, TestHarness}

class RaftConfigTest extends TestHarness {

  "RaftConfig" should {

    "throw exception if config dir is not set in sys env" in {
      SysEnvUtils.unset("RAFT_CONF_DIR")
      intercept[RaftConfigDirectoryNotFoundException] {
        SysEnvUtils.unset("RAFT_CONF_DIR")
        new RaftConfig() {}
      }
    }

    "throw exception if config dir does not exist" in {
      SysEnvUtils.set("RAFT_CONF_DIR", "/abc")
      intercept[RaftConfigFileNotFoundException] {
        new RaftConfig() {}
      }
    }

    "local config if the correct config dir is set in sys env" in {
      SysEnvUtils.set("RAFT_CONF_DIR", getClass.getResource("/").getPath)
      assertResult(this.config) {
        val instance = new RaftConfig() {}
        instance.config
      }
    }
  }
}
