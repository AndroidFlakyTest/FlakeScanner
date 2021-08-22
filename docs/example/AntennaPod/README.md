Tutorial for running with AntennaPod
===

AntennaPod is a widely used open-source Podcast manager for Android. Its official repository is at [https://github.com/AntennaPod/AntennaPod](https://github.com/AntennaPod/AntennaPod) . As also indicated by the developing team, their test suite contains flaky test. We will be using v1.8.1 as an example.

**In this tutorial we are using the prebuilt docker image. If you haven't installed it, please install by following the instructions in README.md.**

To start, you need to have two APKs for running instrumented tests of AntennaPod, one is the App itself while the other contains the test-related files. You may compile them following the general steps of compiling for instrumented tests. For your convenience, we have also provided the two compiled APKs at `TODO`. To use them in container, you will need to make them accessible by the container. **Assuming you have put both APKs in the same folder.** This is done by providing the option `-v PATH_APKS:/apks` to `docker run` where `PATH_APKS` is the path **to the folder** containing both APKs.

For running instrumented tests, an Android device is required. This can be either a physical device or an emulator. Make sure the device is connected on host by running `adb devices`. For the container to be able to access them, the `--net=host` option to `docker run` is used.

Finally, you need to identify the arguments used by FlakeScanner. You may see also the Usage section in README.md for this.

- `appName` is the application ID when running the test.
- `testPackage` is the Java classpath to the test package of the app.
- `apkPath` is the list of paths to the apks files, separated by comma (`,`). **NOTE: paths should be the ones inside container, in this example if your APP APK is at `PATH_APKS/app.apk`, then you should provide `/apks/app.apk` as this is its location inside the container.**
- `testRunnerClsPath` which is often `androidx.test.runner.AndroidJUnitRunner` for newer projects and `android.support.test.runner.AndroidJUnitRunner` for older projects.

All four of them should be the same for all instrumented tests of an app and can be identified by inspecting the Gradle files. Sometimes, it can be less obvious. You may want to run instrumented tests normally and inspect the logs to identify them. For the APKs we have provided, the values are following:

|Argument|Value|
|---|---|
|appName|`de.danoeh.antennapod.debug`|
|testPackage|`de.test.antennapod`|
|apkPath|`TODO`|
|testRunnerClsPath|`android.support.test.runner.AndroidJUnitRunner`|

We are now ready to run FlakeScanner. Taking an instrumented test `testClickNavDrawer` in the `ui.MainActivityTest` of AntennaPod as an example. The command to run FlakeScanner on it is:
```Bash
docker run -v PATH_APKS:/apks --net=host ftd \
  de.danoeh.antennapod.debug \
  de.test.antennapod \
  TODO \
  android.support.test.runner.AndroidJUnitRunner \
  de.test.antennapod.ui.MainActivityTest \
  testClickNavDrawer \
  --adbPath=/usr/bin/adb
```

If you encounter any issue, run with an additional argument `--debug` and create an issue here with the full log pasted inline. We will then try to help you.
