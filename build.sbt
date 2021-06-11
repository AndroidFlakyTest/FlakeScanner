organization := "org.ftd"
name := "xiao-ftd"

scalaVersion := "2.13.1"

libraryDependencies += "com.github.scopt" %% "scopt" % "4.0.0-RC2"
libraryDependencies += "com.google.guava" % "guava" % "28.2-jre"
libraryDependencies += "org.apache.logging.log4j" % "log4j-api" % "2.13.0"
libraryDependencies += "org.apache.logging.log4j" % "log4j-core" % "2.13.0"

mainClass in Compile := Some("org.ftd.Master.utils.CLI")

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)

maintainer in Docker := "xiao"
daemonUser in Docker := "ftd-user"
defaultLinuxInstallLocation in Docker := "/ftd"
dockerAlias := dockerAlias.value.withName("ftd").withTag(Option("intermediate-runner"))
dockerBaseImage := "openjdk:11"
dockerAutoremoveMultiStageIntermediateImages in Docker := false

