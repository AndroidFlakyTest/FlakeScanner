package org.ftd.Master

import java.net.ConnectException
import java.util

import com.sun.jdi.{Bootstrap, ClassType, Location, Method, ObjectReference, ThreadReference, VMDisconnectedException, VirtualMachine}
import com.sun.jdi.connect.Connector.Argument
import com.sun.jdi.event.{BreakpointEvent, ClassPrepareEvent, Event, EventSet, LocatableEvent, MethodEntryEvent, MonitorWaitEvent, MonitorWaitedEvent, StepEvent, VMDisconnectEvent}
import com.sun.jdi.request.{BreakpointRequest, EventRequest, MonitorWaitRequest, MonitorWaitedRequest, StepRequest}
import com.sun.jdi.request.EventRequest.{SUSPEND_ALL, SUSPEND_EVENT_THREAD}
import org.apache.logging.log4j.LogManager
import org.ftd.Master.SuspendTiming.SuspendTiming
import org.ftd.Master.TestRunnerThreadState.TestRunnerThreadState
import org.ftd.Master.utils.Retry

import scala.util.control.Breaks._
//import collection.JavaConverters._
import scala.jdk.CollectionConverters._
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps

object SuspendTiming extends Enumeration {
  type SuspendTiming = Value
  val SUSPEND_DISABLE, SUSPEND_ASAP, SUSPEND_TestStart = Value
}

trait QueueIdleListener {
  def whenIdle(): Unit
}

trait MessageListener {
  def processMessage(msg: Message): Unit
}

trait TestStepListener {
  def testStep(testStepEvent: BreakpointEvent, eventSet: EventSet): Unit
}

object TestRunnerThreadState extends Enumeration {
  type TestRunnerThreadState = Value
  val Sleep, Wait, Normal = Value
}

trait TestRunnerThreadStatusListener {
  def stall(nowState: TestRunnerThreadState): Unit

  def recover(from: TestRunnerThreadState): Unit
}

/**
 *
 * @param port    local port being forwarded to JVM debugger port
 * @param suspend whether and when suspend on attach
 */
class FTDDebugger(val port: Int, val testClassPath: String, val testMethodPath: String, val suspend: SuspendTiming = SuspendTiming.SUSPEND_DISABLE) {

  private val logger = LogManager.getLogger()

  private var mainEnqueueMsgReq: BreakpointRequest = _
  private var messageListener: MessageListener = _
  var testRunnerThread: ThreadReference = _

  private def setConnectionArg(cArgs: util.Map[String, Argument], argName: String, value: String): Unit = {
    cArgs.get(argName) match {
      case null => throw new RuntimeException(s"Setting invalid argument ${argName}")
      case arg => arg setValue value
    }
  }

  private val vmm = Bootstrap.virtualMachineManager()
  private val attachingConnectors = vmm.attachingConnectors()

  // HACK: always use the first attaching connector having hostname argument
  private val connector = attachingConnectors.asScala.find(c => c.defaultArguments().containsKey("hostname")).head

  private val cArgs = connector.defaultArguments()
  setConnectionArg(cArgs, "hostname", "localhost")
  setConnectionArg(cArgs, "port", port toString)

  val vm: VirtualMachine = Retry.retry(3, {connector attach cArgs}, classOf[ConnectException])

  require(vm canRequestMonitorEvents)
  require(vm canUseInstanceFilters)

  private val eventRequestManager = vm eventRequestManager

  private val eventQueue = vm.eventQueue()

  private var suspendEvtSet: EventSet = _
  private var suspendEvt: Event = _

  private var vmSuspended: Boolean = false

  var testClsRef: ClassType = _
  var testMethod: Method = _

  try {
    suspend match {
      case SuspendTiming.SUSPEND_DISABLE =>
      case _ =>

        logger.debug("Suspending the vm ASAP")

        vm.suspend()
        vmSuspended = true

        suspend match {
          case SuspendTiming.SUSPEND_TestStart =>
            stepToClassLoad(testClassPath)

            val testClsRefs = vm classesByName testClassPath
            assert(testClsRefs.size() == 1)
            testClsRef = (testClsRefs get 0).asInstanceOf[ClassType]
            testMethod = testClsRef concreteMethodByName(testMethodPath, "()V")
            assert(testMethod != null, s"Test method name ${testMethod} is not found! (maybe typo?)")

            stepToTestClassInit()

            testRunnerThread = suspendEvt match {
              case evt: LocatableEvent =>
               evt.thread()
              case evt: ClassPrepareEvent =>
               evt.thread()
            }

          case _ =>
        }
    }
  }
  catch {
    case ex: VMDisconnectedException =>
      logger.error("Didn't get VM suspended while VM disconnected. Most likely to be a bug.")
      throw ex
  }

  val mainThread: ThreadReference = vm.allThreads().asScala.find(t => t.name().equals("main")).head

  def steppingByReq(reqs: EventRequest*): Unit = {

    reqs.filter(!_.isEnabled).foreach(req => {
      req addCountFilter (1)
      req.enable()
    })

    if (suspendEvtSet != null) {
      logger.debug(s"Resuming from suspendEventSet ${suspendEvtSet}")
      suspendEvtSet.resume()
      // Clean values for careless mistake
      suspendEvtSet = null
      suspendEvt = null
    }
    if(vmSuspended) {
      vm.resume()
      vmSuspended = false
    }

    logger.debug(s"Start waiting for req to be fulfilled ${reqs}")

    while (suspendEvtSet == null) {
      val eventSet: EventSet = eventQueue.remove()
      breakable {
        for (evt <- eventSet.asScala) {
          if (reqs.contains(evt.request())) {

            reqs.foreach(req=>req.disable())

            suspendEvtSet = eventSet
            suspendEvt = evt
            break
          } else {
            logger.debug(s"Dropping event: ${evt}")
          }
        }
      }

    }
  }

  def stepToClassLoad(classPath: String): Unit = {
    logger.debug("stepToClassLoad - start")
    vm.classesByName(classPath).size match {
      case 1 =>
      case 0 =>
        val clsPrepareReq = eventRequestManager.createClassPrepareRequest()
        clsPrepareReq addClassFilter (classPath)
        clsPrepareReq setSuspendPolicy (SUSPEND_ALL)
        steppingByReq(clsPrepareReq)
    }
  }

  def stepToTestClassInit(): Unit = {
    //throw new RuntimeException("Unsupported!")
    logger.debug("stepToTestClassInit - start")
    //
    //val breakpointReq = eventRequestManager createBreakpointRequest (testClsRef.allLineLocations().get(0))
    //breakpointReq setSuspendPolicy (SUSPEND_ALL)
    //
    //val breakpointReq2 = eventRequestManager createBreakpointRequest (testMethod.location())
    //breakpointReq2 setSuspendPolicy (SUSPEND_ALL)
    //
    //steppingByReq(breakpointReq, breakpointReq2)
    steppingTestExecution(SUSPEND_ALL, (_: BreakpointEvent, _: EventSet) => {})
    steppingByReq(testStepReqs: _*)
    disableTestStepping()
    logger.debug("stepToTestClassInit - done")
  }

  private var interceptMessage_asyncThreadOnly: Boolean = false

  private var testStepListener: TestStepListener = _
  private var testStepReqs: Seq[BreakpointRequest] = Seq()

  def steppingTestExecution(suspendPolicy: Int, testStepListener: TestStepListener): Unit = {
    require(suspend == SuspendTiming.SUSPEND_TestStart)
    this.testStepListener = testStepListener

    //testStepReq = eventRequestManager createStepRequest(testRunnerThread, StepRequest.STEP_LINE, StepRequest.STEP_OVER)
    //testStepReq setSuspendPolicy (SUSPEND_EVENT_THREAD)
    //testStepReq.enable()

    //val breakpointReq =  (testClsRef.allLineLocations().get(0))
    //breakpointReq setSuspendPolicy (SUSPEND_ALL)
    testStepReqs = testClsRef.allLineLocations().asScala.map(eventRequestManager createBreakpointRequest(_)).toSeq
    testStepReqs.foreach(req => {
      req setSuspendPolicy (suspendPolicy)
      req.enable()
    })

  }

  def disableTestStepping(): Unit = {
    require(testStepReqs != null, "test execution hasn't been stepped!")
    testStepReqs.foreach(_.disable())
    testStepReqs = Seq()
  }

  private var msgSuspend: Boolean = _

  private var queueIdleListener: QueueIdleListener = _
  private var idleReq: BreakpointRequest = _

  def handleQueueIdle(handler: QueueIdleListener): Unit = {

    // TODO: Do we really need the check below?
    require(suspend == SuspendTiming.SUSPEND_TestStart)

    queueIdleListener = handler
    val aThread = testRunnerThread//suspendEvt.asInstanceOf[LocatableEvent].thread()

    val mainMsgQueueRef = getMainMsgQueueRef(aThread)

    val mIdleHandlersRef = mainMsgQueueRef.getValue(mainMsgQueueRef.referenceType().fieldByName("mIdleHandlers")).asInstanceOf[ObjectReference]
    assert(mIdleHandlersRef != null)
    val mIdleHandlersClsRef = mIdleHandlersRef.referenceType().asInstanceOf[ClassType]
    assert(mIdleHandlersClsRef != null)
    val interestedToArrayMethodRef = mIdleHandlersClsRef.concreteMethodByName("toArray", "([Ljava/lang/Object;)[Ljava/lang/Object;")
    assert(interestedToArrayMethodRef != null)

    idleReq = eventRequestManager createBreakpointRequest (interestedToArrayMethodRef.location())
    idleReq addInstanceFilter mIdleHandlersRef
    idleReq setSuspendPolicy SUSPEND_EVENT_THREAD
    idleReq.enable()
  }

  private var testRunnerThreadStatusListener: TestRunnerThreadStatusListener = _
  private var testRunnerThreadWaitReq: MonitorWaitRequest = _
  private var testRunnerThreadWaitedReq: MonitorWaitedRequest = _
  //private var testRunnerThreadSleepReqs: Seq[BreakpointRequest] = _
  private var testRunnerThreadSleepDoneReqs: ListBuffer[StepRequest] = ListBuffer()

  def handleTestRunnerThreadStall(listener: TestRunnerThreadStatusListener): Unit = {
    require(suspend == SuspendTiming.SUSPEND_TestStart)

    testRunnerThreadStatusListener = listener

    testRunnerThreadWaitReq = eventRequestManager.createMonitorWaitRequest()
    testRunnerThreadWaitReq addThreadFilter (testRunnerThread)
    testRunnerThreadWaitReq setSuspendPolicy SUSPEND_EVENT_THREAD
    testRunnerThreadWaitReq.enable()

    testRunnerThreadWaitedReq = eventRequestManager.createMonitorWaitedRequest()
    testRunnerThreadWaitedReq addThreadFilter (testRunnerThread)
    testRunnerThreadWaitedReq setSuspendPolicy SUSPEND_EVENT_THREAD
    testRunnerThreadWaitedReq.enable()

    // TODO: clean unused code, not doing it now since we might need later
    // The following doens't work since can't set breakpoint on native method
    //testRunnerThreadSleepReqs = Seq()
    //val threadClsRefs = vm.classesByName("java.lang.Thread")
    //assert(threadClsRefs.size() == 1)
    //val threadClsRef = threadClsRefs get(0)
    //val sleepMethodRefs = threadClsRef.asInstanceOf[ClassType].methodsByName("sleep")
    //assert(sleepMethodRefs.size() > 0)
    //testRunnerThreadSleepReqs = sleepMethodRefs.asScala.map(sleepMethodRef => {
    //  val req = eventRequestManager createBreakpointRequest (sleepMethodRef.location())
    //  req addThreadFilter (testRunnerThread)
    //  req enable()
    //  req
    //})

  }

  /**
   *
   * @note Must call after calling stepToTestRunStart
   * @param asyncThreadOnly
   * @param msgSuspend whether suspend the message posting thread
   * @param messageListener
   */
  def enableInterceptMessage(asyncThreadOnly: Boolean = false, msgSuspend: Boolean, messageListener: MessageListener): Unit = {

    //if (suspend == SuspendTiming.SUSPEND_ASAP) logger.warn("Requested intercepting messages while the current suspend mode might make the app crash for doing this.")
    require(suspend == SuspendTiming.SUSPEND_TestStart)

    interceptMessage_asyncThreadOnly = asyncThreadOnly
    this.msgSuspend = msgSuspend
    this.messageListener = messageListener

    //// Look for test runner thread

    val aThread = testRunnerThread//suspendEvt.asInstanceOf[LocatableEvent].thread()

    assert(aThread.isSuspended)

    val msgQueueClsRefs = vm classesByName "android.os.MessageQueue"
    assert(msgQueueClsRefs.size() == 1)
    val msgQueueClsRef = (msgQueueClsRefs get 0).asInstanceOf[ClassType]

    val method_enqueueMsg = msgQueueClsRef concreteMethodByName("enqueueMessage", "(Landroid/os/Message;J)Z")
    assert(method_enqueueMsg != null)

    val enqueueMsgFstLoc: Location = method_enqueueMsg.location()

    mainEnqueueMsgReq = eventRequestManager createBreakpointRequest (enqueueMsgFstLoc)

    val mainMsgQueueRef = getMainMsgQueueRef(aThread)

    logger.debug(s"mainMsgQueueRef: ${mainMsgQueueRef}")

    mainEnqueueMsgReq addInstanceFilter mainMsgQueueRef
    mainEnqueueMsgReq setSuspendPolicy SUSPEND_EVENT_THREAD
    mainEnqueueMsgReq.enable()

    logger.debug("Finish message intercept setup")
  }

  private var mainMsgQueueRef_ : ObjectReference = _
  private def getMainMsgQueueRef(fromThread: ThreadReference): ObjectReference = {
    if (this.mainMsgQueueRef_ == null) {
      val mainLooperRef = getMainLooperRef(fromThread)

      val method_getQueue = mainLooperRef.referenceType().asInstanceOf[ClassType].concreteMethodByName("getQueue", "()Landroid/os/MessageQueue;")
      assert(method_getQueue != null)
      logger.debug("Getting main queue")
      val invocationRst2 = mainLooperRef invokeMethod(fromThread, method_getQueue, new util.ArrayList(), 0)
      mainMsgQueueRef_ = invocationRst2.asInstanceOf[ObjectReference]
    }

    mainMsgQueueRef_
  }

  private def getMainLooperRef(fromThread: ThreadReference) = {
    val looperClsRefs = vm classesByName "android.os.Looper"
    assert(looperClsRefs.size() == 1)
    val looperClsRef = (looperClsRefs get 0).asInstanceOf[ClassType]

    val method_getMainLooper = looperClsRef concreteMethodByName("getMainLooper", "()Landroid/os/Looper;")
    assert(method_getMainLooper != null)

    // Require vm to be suspended by event
    logger.debug("Getting main looper")
    val invocationRst = looperClsRef invokeMethod(fromThread, method_getMainLooper, new util.ArrayList(), 0)
    invocationRst.asInstanceOf[ObjectReference]
  }

  def isMainMsgQueueEmpty(fromThread: ThreadReference): Boolean = {
    val mainMsgQueueRef = getMainMsgQueueRef(fromThread)
    val value = mainMsgQueueRef.getValue(mainMsgQueueRef.referenceType().fieldByName("mMessages"))
    value == null
  }

  /**
   * WARNING: This is blocking
   *
   */
  def process(): Unit = {

    if (suspendEvtSet != null) {
      suspendEvtSet.resume()
      suspendEvtSet = null
      suspendEvt = null
    }

    try {
      while (true) {
        val eventSet = eventQueue remove

        logger.debug(s"EventSet retrieved: ${eventSet}")

        eventSet.forEach(evt => {
          evt.request() match {
            case breakpointRequest: BreakpointRequest if (breakpointRequest == mainEnqueueMsgReq && mainEnqueueMsgReq != null) =>
              logger.debug(s"Processing mainEnqueueMsgReq: ${breakpointRequest}")
              val breakpointEvent = evt.asInstanceOf[BreakpointEvent]
              val appThread = breakpointEvent.thread
              if (!msgSuspend) appThread.resume()
              // TODO: design better API for processMessage
              messageListener processMessage (Message(MessageID(StackTrace.fromThread(appThread), MessageGroup(null)), new Message_RuntimeInfo(appThread, false)))
            case testStepReq: BreakpointRequest if (testStepReqs.contains(testStepReq)) =>
              logger.debug(s"Processing testStepReq: ${testStepReq}")
              testStepListener.testStep(evt.asInstanceOf[BreakpointEvent], eventSet)
            case breakpointRequest: BreakpointRequest if (breakpointRequest == idleReq && idleReq != null) =>
              logger.debug(s"Processing idleReq: ${idleReq}")
              queueIdleListener.whenIdle()
              evt.asInstanceOf[BreakpointEvent].thread().resume()
              logger.debug(s"Resuming from ${evt}")
            case monitorWaitRequest: MonitorWaitRequest if (monitorWaitRequest == testRunnerThreadWaitReq && testRunnerThreadWaitReq != null) =>
              logger.debug(s"Processing testRunnerThreadWaitReq: ${testRunnerThreadWaitReq}")
              testRunnerThreadStatusListener.stall(TestRunnerThreadState.Wait)
              evt.asInstanceOf[MonitorWaitEvent].thread().resume()
              logger.debug(s"Resuming from ${evt}")
            case monitorWaitedRequest: MonitorWaitedRequest if (monitorWaitedRequest == testRunnerThreadWaitedReq && testRunnerThreadWaitedReq != null) =>
              logger.debug(s"Processing monitorWaitedRequest: ${monitorWaitedRequest}")
              testRunnerThreadStatusListener.recover(TestRunnerThreadState.Wait)
              evt.asInstanceOf[MonitorWaitedEvent].thread().resume()
              logger.debug(s"Resuming from ${evt}")
            //case testRunnerSleepReq: BreakpointRequest if (testRunnerThreadSleepReqs != null && testRunnerThreadSleepReqs.contains(testRunnerSleepReq)) =>
            //  val newSleepDoneReq = eventRequestManager createStepRequest(testRunnerThread, StepRequest.STEP_LINE, StepRequest.STEP_OVER)
            //  newSleepDoneReq addCountFilter 1
            //  newSleepDoneReq setSuspendPolicy SUSPEND_EVENT_THREAD
            //  newSleepDoneReq enable()
            //  testRunnerThreadSleepDoneReqs.append(newSleepDoneReq)
            //  testRunnerThreadStatusListener.stall(TestRunnerThreadState.Sleep)
            //  evt.asInstanceOf[BreakpointEvent].thread().resume()
            //  logger.debug(s"Resuming from ${evt}")
            //case testRunnerSleepDoneReq: StepRequest if (testRunnerThreadSleepDoneReqs.contains(testRunnerSleepDoneReq)) =>
            //  testRunnerSleepDoneReq.thread().resume()
            //  testRunnerThreadSleepDoneReqs -= testRunnerSleepDoneReq
            //  testRunnerThreadStatusListener.recover(TestRunnerThreadState.Sleep)
            //  evt.asInstanceOf[StepEvent].thread().resume()
            //  logger.debug(s"Resuming from ${evt}")
            case req =>
              if (req != null) {
                throw new RuntimeException(s"Event ${evt} from request ${req} is unexpected!")
              } else {
                evt match {
                  case _: VMDisconnectEvent =>
                    logger.info("Received VMDisconnectEvent")
                  case _ =>
                    logger.warn(s"Received unexpected event ${evt}")
                }
              }
          }
        })
      }
    } catch {
      case _: VMDisconnectedException =>
    }
  }

}