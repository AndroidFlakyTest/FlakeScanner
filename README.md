FlakeScanner
===

A tool for detecting Android flaky tests. This is considered alpha version, active development and improvements are on going and will be pushed soon. We would like to also welcome any contributions from you!

This is an implementation of the paper "Flaky Test Detection in Android via Event Order Exploration" to appear in FSE 2021.

Setup
===

Requirement
---
- `java` (> 1.8)
- `sbt` (ver 1.3.7 tested)
- `gradle`

Usage
---

Run command `sbt` to build. Instruction for using `sbt` can be found online.

```
Usage: FlakeScanner [options] appName testPackage apkPath testRunnerClsPath testClassPath testMethodPath

  --adbPath <value>
  --debug
  --disable-ddm-log
  --apkInstallOpts <value>
  --deviceName <value>     Use default device if not supplied
  --max-runs <value>
  --config-from-file <value>
  --test-hang-timeout <value>
  --given-passed <value>
  --strategy <value>
  appName
  testPackage
  apkPath
  testRunnerClsPath
  testClassPath
  testMethodPath
```


Development Note
===
- Please work on personal branches, instead of always pushing to master
- For pushing work to master, please use PR

Developed by
===
Xiao Liang Yu

Zhen Dong
