package me.invkrh.raft.storage

import scala.collection.mutable

import com.typesafe.config.Config

import me.invkrh.raft.exception.{UnknownCommandException, UnknownDataStoreTypeException}
import me.invkrh.raft.message.ClientMessage._

trait DataStore {
  def applyCommand(cmd: Command): CommandResult
}

object DataStore {
  def apply(config: Config): DataStore = {
    val dsType = config.getString("type")
    dsType match {
      case "memory" | "memo" => MemoryStore()
      case _ => throw UnknownDataStoreTypeException(dsType)
    }
  }
}

case class MemoryStore(cache: mutable.HashMap[Any, Any] = new mutable.HashMap()) extends DataStore {

  override def applyCommand(cmd: Command): CommandResult = {
    try {
      cmd match {
        case GET(k) => CommandSuccess(cache.get(k))
        case DEL(k) => CommandSuccess(cache.remove(k))
        case SET(k, v) =>
          cache.update(k, v)
          CommandSuccess(None)
        case Init => CommandSuccess(None)
        case _ => throw UnknownCommandException(cmd)
      }
    } catch {
      case e: Exception =>
        CommandFailure(s"[${cmd.toString}] is failed with error: " + e.getLocalizedMessage)
    }
  }
}
