package org.ftd.Master

import java.util.{Timer, TimerTask}
import java.util.concurrent.TimeUnit

import com.sun.jdi.ThreadReference
import com.sun.jdi.request.EventRequest.{SUSPEND_ALL, SUSPEND_EVENT_THREAD}
import com.sun.jdi.event.{BreakpointEvent, EventSet, StepEvent}
import org.apache.logging.log4j.LogManager
import org.ftd.Master.TestRunnerThreadState.TestRunnerThreadState

import scala.util.control.Breaks._
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration
import scala.language.postfixOps

class Profiler(val testClassPath: String, val testMethodPath: String, val debuggerListenPort: Int, val releaseMap: collection.Map[MessageGroup, mutable.LinkedHashSet[MessageID]] = Map(), val strictRelease: Boolean, val ignoreUnknonMsgs: Boolean, val uselessMsgIDs: Set[MessageID] = Set(), val testCaseHangTimeout: Duration = Duration.Inf) {

  private val logger = LogManager.getLogger()

  val debugger: FTDDebugger = new FTDDebugger(debuggerListenPort, testClassPath, testMethodPath, SuspendTiming.SUSPEND_TestStart)

  val msgStore = new MessageStore

  val thisReleaseRec: mutable.LinkedHashMap[MessageGroup, mutable.LinkedHashSet[MessageID]] = mutable.LinkedHashMap()
  val thisUselessMsgIDs: mutable.Set[MessageID] = mutable.HashSet[MessageID](uselessMsgIDs.toSeq: _*)

  private var messageQIdle: Boolean = _
  val msgGroups: ListBuffer[MessageGroup] = ListBuffer()

  private var lastSeenTestRunnerST: StackTrace = StackTrace.fromThread(debugger.testRunnerThread)

  // NOTE: Must execute the following before steppingTestExecution, else locking will happen
  debugger enableInterceptMessage(false, true, (msg: Message) => {

    logger.trace(s"Handling message ${msg}")

    val newMsg_ = Message(MessageID(msg.ID.appSF, MessageGroup(lastSeenTestRunnerST)), new Message_RuntimeInfo(msg.runtimeInfo.fromThread, usSuspended = true), msg.mustExecBeforeLocation, msg.occurrence)

    val newMsg = msgStore createMessage (newMsg_)
    if (Array(
      debugger.testRunnerThread, // If we suspend, the test runner thread will be stall
      debugger.mainThread // If we suspend, the whole thing will be stall
    ).contains(newMsg.runtimeInfo.fromThread)) {
      newMsg.runtimeInfo.passThrough = true
      logger.trace(s"Pass through msg: ${newMsg}")
      unsuspendMsg(newMsg)
    } else {
      if(ignoreUnknonMsgs && !releaseMap.values.exists(_.contains(newMsg.ID))) {
          logger.trace(s"Pass through UNKNOWN msg: ${newMsg}")
          unsuspendMsg(newMsg)
      } else {
        releaseOneMsgIfStall(newMsg.runtimeInfo.fromThread)
      }
    }
  })

  var lastTimeStmtTimer: Timer = _

  var enteredTestMethodBefore: Boolean = false
  debugger steppingTestExecution(SUSPEND_EVENT_THREAD, (stepEvt: BreakpointEvent, eventSet: EventSet) => {

    if (lastTimeStmtTimer != null) lastTimeStmtTimer.cancel()

    assert(debugger.testMethod != null)
    //if (stepEvt.location().method() == debugger.testMethod) {
    enteredTestMethodBefore = true

    lastSeenTestRunnerST = StackTrace.fromThread(stepEvt.thread())
    msgGroups += MessageGroup(lastSeenTestRunnerST)
    logger.trace(s"lastSeenTestRunnerST: ${lastSeenTestRunnerST}")

    if (testCaseHangTimeout.isFinite) {
      lastTimeStmtTimer = new Timer()
      lastTimeStmtTimer.schedule(new TimerTask {
        override def run(): Unit = {
          //// Start releasing messages
          //msgStore.getSuspendedMessages.headOption match {
          //    case Some(msg) =>
          //        logger.trace(s"Release msg: ${msg}")
          //        msg.runtimeInfo.fromThread.resume()
          //    case _ => throw new RuntimeException("Test Execution hanged while no message suspended")
          //}
          //throw new RuntimeException(s"Test case unexpectedly hanged after waiting for ${testCaseHangTimeout}")
          logger.error(s"Test case unexpectedly hanged after waiting for ${testCaseHangTimeout}")
          System.exit(81)
        }
      }, testCaseHangTimeout.toMillis)
    }

    //}
    //else {
    //  lastSeenTestRunnerST = null
    //
    //  if (enteredTestMethodBefore) {
    //    debugger.disableTestStepping()
    //  }
    //}

    if (strictRelease) {
      // Release all messages indicated by release map
      while (releaseOneByReleaseMap()) {}
    }

    stepEvt.thread().resume()
  })

  debugger handleQueueIdle (() => {
    logger.debug("Message queue idle!")
    messageQIdle = true
    releaseOneMsgIfStall(debugger.mainThread)
  })

  var testRunnerThreadWaitTimer: Timer = _
  debugger handleTestRunnerThreadStall (new TestRunnerThreadStatusListener {
    override def stall(nowState: TestRunnerThreadState): Unit = {
      logger.debug(s"Test runner thread stall! now state is ${nowState}")
      if (messageQIdle) {
        // Only the test runner thread has waited for a while, then release message
        testRunnerThreadWaitTimer = new Timer()
        testRunnerThreadWaitTimer.schedule(new TimerTask {
          override def run(): Unit = {
            releaseOneMsgIfStall(debugger.testRunnerThread)
          }
        }, Duration(5, TimeUnit.SECONDS).toMillis)
      } else {
        // Just wait for the message queue to be idle to release message
      }
    }

    override def recover(from: TestRunnerThreadState): Unit = {
      logger.debug(s"Test runner thread recovered from ${from}!")
      if (testRunnerThreadWaitTimer != null) testRunnerThreadWaitTimer.cancel()
    }
  })

  def releaseOneMsgIfStall(suspendedThread: ThreadReference): Unit = {
    logger.debug(s"messageQIdle=${messageQIdle}")
    if (messageQIdle) {
      val testRunnerThreadStatus = debugger.testRunnerThread.status()
      logger.debug(s"debugger.testRunnerThread.status()=${testRunnerThreadStatus}")
      if (testRunnerThreadStatus == ThreadReference.THREAD_STATUS_WAIT) {
        val mainMsgQueueEmpty = debugger.isMainMsgQueueEmpty(suspendedThread)
        logger.debug(s"mainMsgQueueEmpty=${mainMsgQueueEmpty}")
        if (mainMsgQueueEmpty) {
          releaseOneMsg()
        }
      }
    }
  }

  /**
   *
   * @return whether any message released
   */
  def releaseOneByReleaseMap(suspendedMsgs: Iterable[Message] = msgStore.getSuspendedMessages): Boolean = {

    val targetMsgGrp = MessageGroup(lastSeenTestRunnerST)
    val reversedReleaseMapKeys = releaseMap.keys.toList.reverse
    val targetMsgGrpIdx = reversedReleaseMapKeys.indexOf(targetMsgGrp)

    if (targetMsgGrpIdx != -1) {
      for (k <- reversedReleaseMapKeys.drop(targetMsgGrpIdx)) {
        releaseMap.getOrElse(k, null) match {
          case x if (x == null || x.isEmpty) =>
          case seq =>

            for (id <- seq) {
              suspendedMsgs.find(msg => msg.ID == id) match {
                case Some(msg) =>
                  logger.trace(s"Hitting releaseMap for ${id}!")
                  unsuspendMsg(msg)
                  return true
                case _ =>

              }
            }
        }
      }

    }

    false
  }

  def releaseOneMsg(): Unit = {

    val suspendedMsgs = msgStore.getSuspendedMessages

    val releasedOne = releaseOneByReleaseMap(suspendedMsgs)

    if (!releasedOne) releaseOneMsg_1st(suspendedMsgs)

  }

  /**
   * release one message that is suspended and sent the first
   *
   * @param suspendedMsgs
   */
  private def releaseOneMsg_1st(suspendedMsgs: Iterable[Message]): Unit = {
    logger.trace("Randomly selecting suspended message for releasing.")
    // Start releasing messages
    if (suspendedMsgs.isEmpty) {
      logger.info("Test Execution hanged while no message suspended")
    } else {
      suspendedMsgs.find(msg => !uselessMsgIDs.contains(msg.ID)) match {
        case Some(msg) =>
          unsuspendMsg(msg)
        case _ => logger.info("Test Execution hanged while no *interesting* message suspended")

      }
    }

  }

  /**
   * Start profiling. This is a sync blocking method.
   */
  def start(): Unit = {
    messageQIdle = debugger.isMainMsgQueueEmpty(debugger.mainThread)
    debugger.process()
    thisUselessMsgIDs ++= msgStore.getSuspendedMessages.map(msg => msg.ID)
    if (lastTimeStmtTimer != null) lastTimeStmtTimer.cancel()
    if (testRunnerThreadWaitTimer != null) testRunnerThreadWaitTimer.cancel()
  }

  def unsuspendMsg(msg: Message): Unit = {
    logger.trace(s"Release msg: ${msg}")
    messageQIdle = false
    val currentGrp = MessageGroup(lastSeenTestRunnerST)
    msg.unSuspend(currentGrp)
    if (!msg.runtimeInfo.passThrough) thisReleaseRec.getOrElseUpdate(currentGrp, mutable.LinkedHashSet()) += msg.ID
  }

}
