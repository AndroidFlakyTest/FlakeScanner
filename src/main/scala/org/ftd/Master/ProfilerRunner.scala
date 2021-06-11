package org.ftd.Master

import com.android.ddmlib.testrunner.{ITestRunListener, RemoteAndroidTestRunner, TestIdentifier}
import com.android.ddmlib.{AndroidDebugBridge, Client, IDevice}
import org.apache.logging.log4j.LogManager
import org.ftd.Master.ProfilerRunner.{ClientChangeListener, RunStatus}

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, Promise}
import scala.language.postfixOps
import scala.concurrent.ExecutionContext.Implicits.global

import com.android.ddmlib.AndroidDebugBridge.IClientChangeListener
import com.android.ddmlib.ClientData.DebuggerStatus
import org.ftd.Master.utils.{Retry, RetryException}

object ProfilerRunner {

  class ClientChangeListener(val appName: String, val device: IDevice, val changeDebugStatusPromise: Promise[Client]) extends IClientChangeListener {

    private val logger = LogManager.getLogger()
    private var waitForNameChange = false

    def clientChanged(client: Client, changeMask: Int): Unit = {
      if (!changeDebugStatusPromise.isCompleted) {
        logger.debug(s"appName: ${client.getClientData.getClientDescription}, pid: ${client.getClientData.getPid}, changeMask: ${changeMask}, isDebuggerStatus: ${(changeMask & Client.CHANGE_DEBUGGER_STATUS) != 0}")
        if ((changeMask & Client.CHANGE_DEBUGGER_STATUS) != 0) {
          client.getClientData.getClientDescription match {
            case null =>
              waitForNameChange = true
            case x: String if x == appName =>
              logger.debug(s"Target app has debuggerConnectionStatus: ${client.getClientData.getDebuggerConnectionStatus}")
              if (client.getClientData.getDebuggerConnectionStatus == DebuggerStatus.WAITING) {
                changeDebugStatusPromise success client
              }
            case _ =>
          }
        } else if (waitForNameChange && (changeMask & Client.CHANGE_NAME) != 0) {
          if (client.getClientData.getClientDescription == appName &&
            client.getClientData.getDebuggerConnectionStatus == DebuggerStatus.WAITING) {
            changeDebugStatusPromise success client
          }
        }
      }
    }
  }

  case class RunStatus(testResult: Boolean, profiler: Profiler)

}

class ProfilerRunner(device: IDevice, testRunner: RemoteAndroidTestRunner, appName: String, testClassPath: String, testMethodPath: String) {

  private val logger = LogManager.getLogger()

  // NOTE: This is blocking
  def run(releaseMap: collection.Map[MessageGroup, mutable.LinkedHashSet[MessageID]] = Map(), strictRelease: Boolean, ignoreUnknonMsgs: Boolean, uselessMsgIDs: Set[MessageID] = Set(), testCaseHangTimeout: Duration = Duration.Inf): RunStatus = {

    Retry.retry(3, {

      val clientChangePromise = Promise[Client]()
      val clientChangePromise_future = clientChangePromise.future

      val clientChangeListener = new ClientChangeListener(appName, device, clientChangePromise)
      AndroidDebugBridge addClientChangeListener clientChangeListener
      // Wait for debug status change

      val testEndPromise = Promise[Boolean]()
      val testEndPromise_future = testEndPromise.future

      val testRunEndPromise = Promise[Unit]()
      val testRunEndPromise_future = testRunEndPromise.future
      logger.info("Starting test run")
      val testRunFuture =
        Future {
          TestRunner.run(testRunner, testEndPromise, testRunEndPromise)
          logger.info("Test run returned")
        }

      logger.debug("Waiting client change to have debugger status WAITING")

      val client = Await.result(clientChangePromise_future, Duration.Inf)
      assert(client != null)
      logger.info(s"Client for ${appName} retrieved: ${client}")

      // logger.info(s"Debugger attached: ${client.isDebuggerAttached}")

      val profiler = new Profiler(testClassPath, testMethodPath, client getDebuggerListenPort, releaseMap, strictRelease, ignoreUnknonMsgs, uselessMsgIDs, testCaseHangTimeout)

      profiler.start()

      logger.trace("Waiting for test to terminate")
      val testRst = Await.result(testEndPromise_future, Duration.Inf)
      logger.trace("Waiting for test run to terminate")
      Await.result(testRunEndPromise_future, Duration.Inf)

      //Await.ready(testRunFuture, Duration.Inf)
      client.kill()
      RunStatus(testRst, profiler)
    }, classOf[RetryException])

  }
}
