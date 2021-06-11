package org.ftd.Master.Strategy

import com.android.ddmlib.IDevice
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner
import org.apache.logging.log4j.LogManager
import org.ftd.Master.Scheduler.{AdaptiveSchedulerRunner, DescendingDelayScheduler, MaxDelayScheduler}

import scala.concurrent.duration.Duration

class XiaoScheduling extends Strategy {

  private val logger = LogManager.getLogger()

  val name: String = this.getClass.getSimpleName

  /**
   *
   * @return indicates whether detected flaky
   */
  def start(device: IDevice, testRunner: RemoteAndroidTestRunner, appName: String, testClassPath: String, testMethodPath: String, testCaseHangTimeout: Duration = Duration.Inf, max_runs: Int, givenPassed: Boolean): Boolean = {

      testRunner setDebug true

      val init_passedBefore = givenPassed
      val init_failedBefore = !givenPassed

      val maxDelaySchedulerRunner = new AdaptiveSchedulerRunner(new MaxDelayScheduler(), device, testRunner, appName, testClassPath, testMethodPath, testCaseHangTimeout)
      val AdaptiveSchedulerRunner.Status(detectedFlaky, runs, passedBefore, failedBefore) = maxDelaySchedulerRunner.run(max_runs, init_passedBefore, init_failedBefore)

      if(detectedFlaky) {
        return true
      }

      logger.info("Now using DescendingDelayScheduler")
      val descendingDelayScheduler = new DescendingDelayScheduler(maxDelaySchedulerRunner.scheduler.lastReleaseMap, maxDelaySchedulerRunner.lastProfiler.msgGroups, maxDelaySchedulerRunner.scheduler.uselessMsgIDs, maxDelaySchedulerRunner.lastProfiler.thisUselessMsgIDs.toSet, passedBefore, failedBefore)
      val descendingDelaySchedulerRunner = new AdaptiveSchedulerRunner(descendingDelayScheduler, device, testRunner, appName, testClassPath, testMethodPath, testCaseHangTimeout)
      val AdaptiveSchedulerRunner.Status(detectedFlaky2, _, _, _) = descendingDelaySchedulerRunner.run(if (max_runs == -1) -1 else max_runs - runs, passedBefore, failedBefore)

      if(detectedFlaky2) {
        return true
      }

      logger.info("All possibilities tried, test case not detected flaky.")
      false
  }
}
