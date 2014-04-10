import sbt._
import sbt.{Build => SbtBuild}
import sbt.Keys._
import scala.scalajs.sbtplugin.ScalaJSPlugin._
import scala.scalajs.sbtplugin.ScalaJSPlugin.ScalaJSKeys._


object Build extends SbtBuild {

  val sharedSettings = Defaults.defaultSettings ++ Seq(
    organization := "org.monifu",
    version := "0.5-SNAPSHOT",
    scalaVersion := "2.10.4",
    crossScalaVersions := Seq("2.10.4", "2.11.0-RC4"),

    scalacOptions ++= Seq(
      "-unchecked", "-deprecation", "-feature", "-Xlint", "-target:jvm-1.6", "-Yinline-warnings"
    ),

    resolvers ++= Seq(
      "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases",
      Resolver.sonatypeRepo("releases")
    ),

    // -- Settings meant for deployment on oss.sonatype.org

    publishMavenStyle := true,

    publishTo := {
      val nexus = "https://oss.sonatype.org/"
      if (isSnapshot.value)
        Some("snapshots" at nexus + "content/repositories/snapshots")
      else
        Some("releases"  at nexus + "service/local/staging/deploy/maven2")
    },

    publishArtifact in Test := false,
    pomIncludeRepository := { _ => false }, // removes optional dependencies
    
    pomExtra :=
      <url>http://www.monifu.org/</url>
      <licenses>
        <license>
          <name>Apache License, Version 2.0</name>
          <url>https://www.apache.org/licenses/LICENSE-2.0</url>
          <distribution>repo</distribution>
        </license>
      </licenses>
      <scm>
        <url>git@github.com:alexandru/monifu.git</url>
        <connection>scm:git:git@github.com:alexandru/monifu.git</connection>
      </scm>
      <developers>
        <developer>
          <id>alex_ndc</id>
          <name>Alexandru Nedelcu</name>
          <url>https://www.bionicspirit.com/</url>
        </developer>
      </developers>
  )

  // -- Actual Projects

  lazy val root = Project(id = "monifu", base = file("."), settings = sharedSettings)
    .aggregate(monifuCore, monifuCoreJS)
    .dependsOn(monifuCore)

  lazy val monifuCore = Project(
    id = "monifu-core",
    base = file("monifu-core"),
    settings = sharedSettings ++ Seq(
      unmanagedSourceDirectories in Compile <+= sourceDirectory(_ / "shared" / "scala"),
      scalacOptions += "-optimise",
      libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-reflect" % _ % "compile"),
      libraryDependencies ++= Seq(
        "org.scalatest" %% "scalatest" % "2.1.2" % "test"
      )
    )
  )

  lazy val monifuCoreJS = Project(
    id = "monifu-core-js",
    base = file("monifu-core-js"),
    settings = sharedSettings ++ scalaJSSettings ++ Seq(
      unmanagedSourceDirectories in Compile <+= sourceDirectory(_ / ".." / ".." / "monifu-core" / "src" / "shared" / "scala"),
      libraryDependencies <+= scalaVersion("org.scala-lang" % "scala-reflect" % _ % "compile"),
      libraryDependencies ++= Seq(
        "org.scala-lang.modules.scalajs" %% "scalajs-jasmine-test-framework" % scalaJSVersion % "test"
      )
    )
  )
}
