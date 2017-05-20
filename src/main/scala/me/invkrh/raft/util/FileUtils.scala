package me.invkrh.raft.util

import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._

object FileUtils {

  def contents(path: Path): List[String] = {
    Files
      .readAllLines(path)
      .asScala
      .filter(x => x.nonEmpty && !x.startsWith("#") && !x.startsWith("//"))
      .toList
  }

  def getCoordinatorSystemAddress(path: Path): Location = {
    val fileContent = contents(path)
    if (fileContent.size != 1) {
      throw new RuntimeException("Configure file is malformed")
    } else {
      Location(fileContent.head)
    }
  }
}
