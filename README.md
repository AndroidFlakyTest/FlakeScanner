FlakeScanner
===

A tool for detecting Android flaky tests. This is considered alpha version, active development and improvements are on going and will be pushed soon. We would like to also welcome any contributions from you!

This is an implementation of the paper "Flaky Test Detection in Android via Event Order Exploration" to appear in FSE 2021.

Build From Source
===

With Docker
---
This is the easiest way to build from source. With `docker` (tested > 18) installed, just do the following with the working directory the root of this project:
```Bash
bash scripts/build-docker.bash
```
If the build was successful, you should have access to the docker image with tag `ftd:latest`

Without Docker
---

### Requirement
- `java` (> 1.8)
- `sbt` (ver 1.3.7 tested)
- `gradle`

Run command `sbt` to build. Instruction for using `sbt` can be found online.

Usage
===

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
