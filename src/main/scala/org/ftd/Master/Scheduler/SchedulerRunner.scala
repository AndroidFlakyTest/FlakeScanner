package org.ftd.Master.Scheduler

import com.android.ddmlib.IDevice
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner
import org.apache.logging.log4j.LogManager
import org.ftd.Master.Scheduler.AdaptiveSchedulerRunner.Status
import org.ftd.Master.{Profiler, ProfilerRunner}

import scala.collection.immutable.ListMap
import scala.concurrent.duration.Duration

object AdaptiveSchedulerRunner {
  case class Status(detectedFlaky: Boolean, runs: Int, passedBefore: Boolean, failedBefore: Boolean)
}

class AdaptiveSchedulerRunner[S <: AdaptiveScheduler](val scheduler: S, val device: IDevice, val testRunner: RemoteAndroidTestRunner, val appName: String, val testClassPath: String, val testMethodPath: String, val testCaseHangTimeout: Duration = Duration.Inf) {

  private val logger = LogManager.getLogger()

  var lastProfiler: Profiler = _
  /**
   *
   * @param max_runs
   * @return `true` if detected flaky, `false` otherwise
   */
  def run(max_runs: Int, init_passedBefore: Boolean, init_failedBefore: Boolean): Status = {

    var passedBefore = init_passedBefore
    var failedBefore = init_failedBefore

    var runningCnt: Int = 0

    while (scheduler.hasNext && (max_runs == -1 || runningCnt < max_runs)) {
      runningCnt += 1;
      logger.info(s"${scheduler}: Running ${runningCnt}th time")

      val SchedulingInfo(newReleaseMap, uselessMsgIDs, strictRelease, ignoreUnknonMsgs) = scheduler.next()

      val profilerRunner = new ProfilerRunner(device, testRunner, appName, testClassPath, testMethodPath)
      val ProfilerRunner.RunStatus(testRst, profiler) = profilerRunner.run(newReleaseMap, strictRelease, ignoreUnknonMsgs, uselessMsgIDs, testCaseHangTimeout)

      logger.trace(s"All profiled msg: \n${profiler.msgStore}")
      logger.trace(s"Useless msg: \n${profiler.thisUselessMsgIDs}")

      lastProfiler = profiler

      scheduler.update(SchedulingUpdateInfo(testRst, profiler.thisReleaseRec.to(ListMap), if(testRst) profiler.thisUselessMsgIDs.toSet else Set()))

      if (!testRst) {
        failedBefore = true
        if (passedBefore) return Status(detectedFlaky = true, runningCnt, passedBefore, failedBefore)
      } else {
        passedBefore = true
        if (failedBefore) return Status(detectedFlaky = true, runningCnt, passedBefore, failedBefore)
      }

    }

    Status(detectedFlaky = false, runningCnt, passedBefore, failedBefore)
  }
}
