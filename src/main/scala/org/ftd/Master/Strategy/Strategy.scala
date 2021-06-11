package org.ftd.Master.Strategy

import com.android.ddmlib.IDevice
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner

import scala.concurrent.duration.Duration

trait Strategy {
  val name: String

  def start(device: IDevice, testRunner: RemoteAndroidTestRunner, appName: String, testClassPath: String, testMethodPath: String, testCaseHangTimeout: Duration = Duration.Inf, max_runs: Int, givenPassed: Boolean): Boolean
}
