package me.invkrh.raft.storage

import me.invkrh.raft.kit.TestHarness
import me.invkrh.raft.message.{CommandSuccess, DEL, GET, Init, SET}

class DataStoreTest extends TestHarness {
  "MemoryStore" should {
    "reply command with success" in {
      val store = MemoryStore()
      assertResult(CommandSuccess(None)) {
        store.applyCommand(SET("x", 1))
      }
      assertResult(CommandSuccess(Some(1))) {
        store.applyCommand(GET("x"))
      }
      assertResult(CommandSuccess(Some(2))) {
        store.applyCommand(SET("y", 2))
        store.applyCommand(DEL("y"))
      }
      assertResult(CommandSuccess(None)) {
        store.applyCommand(Init)
      }
    }
  }
}
