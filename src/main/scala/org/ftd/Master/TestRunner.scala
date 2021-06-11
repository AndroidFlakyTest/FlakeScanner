package org.ftd.Master

import com.android.ddmlib.testrunner.{ITestRunListener, RemoteAndroidTestRunner, TestIdentifier}
import org.apache.logging.log4j.LogManager
import org.ftd.Master.utils.RetryException
import java.util

import scala.concurrent.Promise

object TestRunner {
  class TestRunListener(testEndPromise: Promise[Boolean], testRunEndPromise: Promise[Unit]) extends ITestRunListener {

    private val logger = LogManager.getLogger()
    private var runFailed: Boolean = false
    private var shouldRetry: Boolean = false

    def testRunStarted(runName: String, testCount: Int): Unit = {
      logger.debug(s"Test run started, runName: ${runName}")
    }

    def testStarted(test: TestIdentifier): Unit = {
      logger.debug(s"Test started, test: ${test}")
    }

    def testFailed(test: TestIdentifier, trace: String): Unit = {
      logger.debug("Test failed")
      logger.debug(trace)
      assert(!testEndPromise.isCompleted)
      testEndPromise success (false)
    }

    def testAssumptionFailure(test: TestIdentifier, trace: String): Unit = {
      throw new RuntimeException("Test assumption failed")
    }

    def testIgnored(test: TestIdentifier): Unit = {
      throw new RuntimeException("Test ignored")
    }

    def testEnded(test: TestIdentifier, testMetrics: util.Map[String, String]): Unit = {
      logger.debug("Test ended")
      if (!testEndPromise.isCompleted) {
        testEndPromise success (true)
      }
    }

    def testRunFailed(errMsg: String): Unit = {
      logger.debug("Test run failed.")
      logger.debug(errMsg)
      runFailed = true
      if (errMsg.startsWith("com.android.ddmlib.TimeoutException")) {
        shouldRetry = true
      }
    }

    def testRunStopped(elapsedTime: Long): Unit = {

    }

    def testRunEnded(elapsedTime: Long, runMetrics: util.Map[String, String]): Unit = {
      assert(testEndPromise.isCompleted, "No test executed!")
      logger.debug(s"Test run ended in ${elapsedTime} milliseconds.")
      assert(!testRunEndPromise.isCompleted)

      if (!runFailed) {
        testRunEndPromise success()
      } else {
        if (shouldRetry) {
          testRunEndPromise failure (RetryException())
        } else {
          testRunEndPromise failure (new RuntimeException("Test run failed!"))
        }

      }

    }
  }

  def run(testRunner: RemoteAndroidTestRunner, testEndPromise: Promise[Boolean], testRunEndPromise: Promise[Unit]): Unit = {
    val testRunListener = new TestRunListener(testEndPromise, testRunEndPromise)
    testRunner run testRunListener
  }
}
