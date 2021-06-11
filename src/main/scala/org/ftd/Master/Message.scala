package org.ftd.Master

import com.sun.jdi.ThreadReference

// group uses stacktrace location info

case class MessageGroup(testRunnerSF: StackTrace)

case class MessageID(appSF: StackTrace, group: MessageGroup)

class Message_RuntimeInfo(val fromThread: ThreadReference, var usSuspended: Boolean, var passThrough: Boolean = false, var releaseTime: MessageGroup = null) {
  case class Message_RuntimeInfo_Store(threadName: String, usSuspended: Boolean, releaseTime: MessageGroup)

  val threadName: String = fromThread.name()

  override def toString: String = Message_RuntimeInfo_Store(threadName, usSuspended, releaseTime).toString
}

case class Message(ID: MessageID, runtimeInfo: Message_RuntimeInfo, mustExecBeforeLocation: SourceLine = null, occurrence: Int = 0) {
  def isSuspended: Boolean = runtimeInfo.usSuspended

  def unSuspend(timing: MessageGroup): Unit = {
    require(runtimeInfo.usSuspended)
    assert(runtimeInfo.fromThread.isSuspended, "Message leaking?!")
    runtimeInfo.usSuspended = false
    runtimeInfo.releaseTime = timing
    runtimeInfo.fromThread.resume()
  }
}
