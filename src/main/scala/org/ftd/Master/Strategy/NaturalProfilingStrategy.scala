package org.ftd.Master.Strategy

import com.android.ddmlib.IDevice
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner
import org.apache.logging.log4j.LogManager
import org.ftd.Master.{ProfilerRunner, TestRunner}

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration

class NaturalProfilingStrategy extends Strategy {
  private val logger = LogManager.getLogger()

  val name: String = this.getClass.getSimpleName

  def start(device: IDevice, testRunner: RemoteAndroidTestRunner, appName: String, testClassPath: String, testMethodPath: String, testCaseHangTimeout: Duration = Duration.Inf, max_runs: Int, givenPassed: Boolean): Boolean = {

    // Note: handle when givenPassed == false

    val iterationStream_ = LazyList.from(1)
    val iterationStream = if (max_runs != -1 ) iterationStream_.take(max_runs) else iterationStream_

    if (max_runs != -1 ) logger.warn("max runs is infinity")

    for(i <- iterationStream) {

      logger.info(s"Running ${i}th time")

      testRunner setDebug(true)

      val profilerRunner = new ProfilerRunner(device, testRunner, appName, testClassPath, testMethodPath)
      val ProfilerRunner.RunStatus(testRst, _) = profilerRunner.run(Map(), strictRelease = false, ignoreUnknonMsgs = true, uselessMsgIDs = Set(), testCaseHangTimeout)

      if (!testRst) {
        return true
      }
    }

    false
  }
}
