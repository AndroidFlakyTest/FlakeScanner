package org.ftd.Master.utils

import java.io.File
import java.lang.Thread.UncaughtExceptionHandler
import java.nio.file.{Files, Paths}

import org.ftd.Master.{Config, Main}

import scala.concurrent.duration.Duration

object CLI {
  def main(args: Array[String]): Unit = {

    val prevUncaughtExHandler = Thread.getDefaultUncaughtExceptionHandler

    Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler {
      override def uncaughtException(thread: Thread, throwable: Throwable): Unit = {
        if (prevUncaughtExHandler != null) prevUncaughtExHandler.uncaughtException(thread, throwable)
        System.err.println(throwable)
        System.err.println(s"Happened in thread ${thread}")
        throwable.printStackTrace(System.err)
        System.exit(1)
      }
    })

    import scopt.OParser

    val builder = OParser.builder[Config]
    val parser = {
      import builder._
      OParser.sequence(
        programName("FTD"),

        opt[String]("adbPath")
          .required()
          // .withFallback(() => System.getenv("PATH").split(File.pathSeparator).map(p => Paths.get(p).resolve("adb")).find( p => Files.exists(p) ).head )
          .action((x, c) => c.copy(adbPath = Paths get x)),

        opt[Unit]("debug")
          .action((_, c) => c.copy(debug = true)),

        opt[Unit]("disable-ddm-log")
          .action((_, c) => c.copy(disable_ddm_log = true)),

        opt[Seq[String]]("apkInstallOpts")
          .optional()
          .action((x, c) => c.copy(apkInstallOpts = x)),

        opt[String]("deviceName")
          .optional()
          .action((x, c) => c.copy(deviceName = x))
          .text("Use default device if not supplied"),

        opt[Int]("max-runs")
          .optional()
          .action((x, c) => c.copy(max_runs = x)),

        opt[File]("config-from-file")
            .optional()
            .action((x, c)=>c.copy(from_files = x)),

        opt[Duration]("test-hang-timeout")
            .optional()
            .action((x, c)=>c.copy(testCaseHangTimeout = x)),

        opt[Boolean]("given-passed")
          .optional()
          .action((x, c)=>c.copy(givenPassed = x)),

        opt[String]("strategy")
            .optional()
            .action((x, c)=>c.copy(strategy = x)),

        arg[String]("appName")
          .required()
          .action((x, c) => c.copy(appName = x)),

        arg[String]("testPackage")
          .required()
          .action((x, c) => c.copy(testPackage = x)),

        arg[Seq[String]]("apkPath")
          .required()
          .action((x, c) => c.copy(apkPath = x.map(p => Paths.get(p)))),

        arg[String]("testRunnerClsPath")
          .required()
          .action((x, c) => c.copy(testRunnerClsPath = x)),

        arg[String]("testClassPath")
          .required()
          .action((x, c) => c.copy(testClassPath = x)),

        arg[String]("testMethodPath")
          .required()
          .action((x, c) => c.copy(testMethodPath = x)),

      )
    }

    OParser.parse(parser, args, Config()) match {
      case Some(config) => Main.main(config)
      case None => // Do nothing
    }
  }
}