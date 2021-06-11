package org.ftd.Master

import java.io.File
import java.nio.file.Path

import scala.concurrent.duration.Duration

case class Config(
                   apkPath: Seq[Path] = null,
                   apkInstallOpts: Seq[String] = Seq(),
                   testRunnerClsPath: String = null,
                   testClassPath: String = null,
                   testMethodPath: String = null,
                   adbPath: Path = null,
                   deviceName: String = null,
                   appName: String = null,
                   testPackage: String = null,
                   debug: Boolean = false,
                   max_runs: Int = -1, // -1 means forever
                   from_files: File = null,
                   disable_ddm_log: Boolean = false,
                   testCaseHangTimeout: Duration = Duration.Inf,
                   strategy: String = "XiaoScheduling",
                   givenPassed: Boolean = true,
                 )