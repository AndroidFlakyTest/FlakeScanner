package org.ftd.Master

import java.util
import java.util.concurrent.TimeUnit

import scala.concurrent._
import scala.concurrent.duration.Duration
import com.android.ddmlib.{AndroidDebugBridge, Client, DdmPreferences, IDevice, InstallException, TimeoutException}
import com.android.ddmlib.AndroidDebugBridge.{IClientChangeListener, IDeviceChangeListener}
import com.android.ddmlib.ClientData.DebuggerStatus
import com.android.ddmlib.IDevice.DeviceState
import com.android.ddmlib.testrunner.{ITestRunListener, RemoteAndroidTestRunner, TestIdentifier}
import com.android.ddmlib.Log.LogLevel
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.config.Configurator
import org.ftd.Master.Scheduler.{DescendingDelayScheduler, MaxDelayScheduler, SchedulingInfo}
import org.ftd.Master.Strategy.{NaturalProfilingStrategy, RerunStrategy, Strategy, XiaoScheduling}
import org.ftd.Master.utils.Retry

import scala.util.Try
import scala.sys.process._
import ExecutionContext.Implicits.global
import scala.collection.immutable.{HashSet, ListMap}
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.language.postfixOps

class DeviceChangeListener(val connectPromise: Promise[IDevice], forDevice: String = null) extends IDeviceChangeListener {

  private var waitForOnline: Boolean = false

  def deviceChanged(device: IDevice, changeMask: Int): Unit = {
    if (!connectPromise.isCompleted) {
      if (waitForOnline && (changeMask & IDevice.CHANGE_STATE) != 0 && device.getState == DeviceState.ONLINE) {
        connectPromise success(device)
      }
    }
  }

  def deviceConnected(device: IDevice): Unit = {
    if (!connectPromise.isCompleted) {
      if (forDevice == null || device.getName == forDevice) {
        if (device.getState != DeviceState.ONLINE) {
          waitForOnline = true
        } else {
          connectPromise success(device)
        }
      }
    }
  }

  def deviceDisconnected(device: IDevice): Unit = {
  }
}

object Main {

  private val logger = LogManager.getLogger()

  def main(config: Config): Unit = {

    try {

      if (config.debug) {

        if(!config.disable_ddm_log) DdmPreferences.setLogLevel(LogLevel.VERBOSE.getStringValue)

        Configurator.setRootLevel(Level.TRACE)
        val log4jCtx = LogManager.getContext(false).asInstanceOf[LoggerContext]
        val log4jConfig = log4jCtx.getConfiguration

        val rootConfig = log4jConfig.getLoggers get ""

        log4jConfig.getAppenders.forEach((name, appender) => {
          appender match {
            case fileAppender: FileAppender =>
              val newFileAppender: FileAppender = FileAppender
                .newBuilder().asInstanceOf[FileAppender.Builder[Nothing]]
                .setConfiguration(log4jConfig).asInstanceOf[FileAppender.Builder[Nothing]]
                .withFileName(fileAppender.getFileName).asInstanceOf[FileAppender.Builder[Nothing]]
                .setName(fileAppender.getName).asInstanceOf[FileAppender.Builder[Nothing]]
                .withImmediateFlush(true).asInstanceOf[FileAppender.Builder[Nothing]]
                .setIgnoreExceptions(fileAppender.ignoreExceptions()).asInstanceOf[FileAppender.Builder[Nothing]]
                .withBufferedIo(false).asInstanceOf[FileAppender.Builder[Nothing]]
                .setLayout(fileAppender.getLayout).asInstanceOf[FileAppender.Builder[Nothing]]
                .setFilter(fileAppender.getFilter).asInstanceOf[FileAppender.Builder[Nothing]]
                .build()
              rootConfig removeAppender name
              log4jConfig addAppender (newFileAppender)
            case _ =>
          }
        })

        log4jCtx.updateLoggers()
      }

      val deviceConnectPromise = Promise[IDevice]()
      val deviceConnectPromise_future = deviceConnectPromise.future

      val deviceChangeListener = new DeviceChangeListener(deviceConnectPromise, config.deviceName)

      AndroidDebugBridge addDeviceChangeListener deviceChangeListener

      AndroidDebugBridge.init(true)

      AndroidDebugBridge.createBridge(config.adbPath toString, true)

      // Simply execute without caring whether successful and wait for a connected device
      if (config.deviceName != null) {
        val adbConnectCMD = s"${config.adbPath} connect ${config.deviceName}"
        logger.info("Making device to connect")
        val adbConnectCMD_msg = Process(adbConnectCMD).!!
        logger.info(adbConnectCMD_msg)
      }

      logger.info("Waiting device to connect")
      // Wait for device to connect
      val device: IDevice = Await.result(deviceConnectPromise_future, Duration.Inf)

      AndroidDebugBridge removeDeviceChangeListener deviceChangeListener

      logger.info(s"Device ${device} connected")

      val packageInstallOpts = config.apkInstallOpts :+ "-t"
      config.apkPath.foreach(p => Retry.retry(10, device.installPackage(p toString, false, packageInstallOpts: _*), (e)=>e.isInstanceOf[InstallException] && e.getCause.isInstanceOf[TimeoutException]) )

      logger.info("Package installed")

      val testRunner = new RemoteAndroidTestRunner(config.testPackage, config.testRunnerClsPath, device)
      // set adb command
      testRunner setMethodName(config.testClassPath, config.testMethodNameInAdb)
      testRunner setRunOptions( testRunner.getRunOptions + " --no-window-animation" )

      val strategy: Strategy = config.strategy match {
        case s if (s.equals(new XiaoScheduling().name)) => new XiaoScheduling()
        case s if (s.equals(new NaturalProfilingStrategy().name)) => new NaturalProfilingStrategy()
        case s if (s.equals(new RerunStrategy().name)) => new RerunStrategy()
      }

      val startDetectionTime = System.currentTimeMillis()
      val rst: Boolean = strategy.start(device, testRunner, config.appName, config.testClassPath, config.testMethodPath, config.testCaseHangTimeout, config.max_runs, config.givenPassed)
      val endDetectionTime = System.currentTimeMillis()
      val detectionDuration = Duration(endDetectionTime - startDetectionTime, TimeUnit.MILLISECONDS)
      logger.info(s"Detection duration: ${detectionDuration.toSeconds} seconds")

      if(rst) {
        exitFlaky()
      } else {
        exitNormal()
      }

    } finally {
      logger.trace("Cleanup start")
      System.out.flush()
      try {
        AndroidDebugBridge.terminate()
      } catch {
        case _: InterruptedException =>
          // This can be safely ignored
        case ex: Throwable =>
          logger.error(ex)
          // Just print it out without affecting exit status
          // Not really care if it successfully cleans up or not
      }

      logger.trace("Cleanup end")
    }

  }

  def exitNormal(): Unit = {
    println("Not detected flaky")
    System.exit(0)
  }

  def exitFlaky(): Unit = {
    logger.info("Detected Flaky!!!")
    println("Flaky")
    System.exit(0)
  }
}