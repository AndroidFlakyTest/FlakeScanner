package org.ftd.Master.Strategy

import com.android.ddmlib.IDevice
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner
import org.apache.logging.log4j.LogManager
import org.ftd.Master.TestRunner

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.Duration

class RerunStrategy extends Strategy {
  private val logger = LogManager.getLogger()

  val name: String = this.getClass.getSimpleName

  def start(device: IDevice, testRunner: RemoteAndroidTestRunner, appName: String, testClassPath: String, testMethodPath: String, testCaseHangTimeout: Duration = Duration.Inf, max_runs: Int, givenPassed: Boolean): Boolean = {

    // Note: handle when givenPassed == false

    val iterationStream_ = LazyList.from(1)
    val iterationStream = if (max_runs != -1 ) iterationStream_.take(max_runs) else iterationStream_

    if (max_runs != -1 ) logger.warn("max runs is infinity")

    for(i <- iterationStream) {

      logger.info(s"Running ${i}th time")

      val testEndPromise = Promise[Boolean]()
      val testEndPromise_future = testEndPromise.future

      val testRunEndPromise = Promise[Unit]()

      TestRunner.run(testRunner, testEndPromise, testRunEndPromise)

      val rst = Await.result(testEndPromise_future, Duration.Inf)
      if (!rst) {
        return true
      }
    }

    false
  }
}