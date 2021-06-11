package org.ftd.Master

import com.sun.jdi.Location
import org.apache.logging.log4j.LogManager

case class SourceLine(sourcePath: String, line: Int)

object SourceLine {

  private val logger = LogManager.getLogger()

  def fromLocation(location: Location): SourceLine = {
    try {
      SourceLine(location.sourcePath(), location.lineNumber())
    } catch {
      case ex: IllegalArgumentException => {
        logger.debug(s"Encountered IllegalArgumentException when getting location data: ${ex}")
        null
      }
    }
  }
}
