package org.ftd.Master

import com.sun.jdi.{AbsentInformationException, Location, StackFrame, ThreadReference}
import org.apache.logging.log4j.LogManager

import collection.JavaConverters._

case class StackTrace(locations: List[SourceLine])

object StackTrace {

  def fromThread(thread: ThreadReference): StackTrace = {
    var resumeThread: Boolean = false
    if (!thread.isSuspended) {
      thread.suspend()
      resumeThread = true
    }
    val allFrameLocations = thread.frames().asScala.map { x =>
      try {
        SourceLine.fromLocation(x.location)
      } catch {
        case _: AbsentInformationException =>
          null
      }
    }
    if(resumeThread) {
      thread.resume()
    }
    StackTrace(allFrameLocations.toList)
  }
}