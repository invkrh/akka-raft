package me.invkrh.raft.kit

import java.util.{Map => JMap}

object SysEnvUtils {

  private def getEnv: JMap[String, String] = {
    val field = System.getenv().getClass.getDeclaredField("m")
    field.setAccessible(true)
    field.get(System.getenv()).asInstanceOf[JMap[String, String]]
  }

  def set(key: String, value: String): String = {
    getEnv.put(key, value)
  }

  def unset(key: String): String = {
    getEnv.remove(key)
  }
}
