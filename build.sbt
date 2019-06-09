val mainScala = "2.12.8"
val allScala  = Seq("2.11.12", mainScala)

inThisBuild(Seq(
  organization := "dev.zio",
  homepage := Some(url("https://github.com/zio/zio-instrumentation")),
  name := "zio-instrumentation",
  organizationName := "Tamer Abdulradi",
  startYear := Some(2019),
  licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
  scalaVersion := mainScala,
  parallelExecution in Test := false,
  scalafmtOnCompile := true,
  fork in Test := true,
  pgpPublicRing := file("/tmp/public.asc"),
  pgpSecretRing := file("/tmp/secret.asc"),
  releaseEarlyWith := SonatypePublisher,
  scmInfo := Some(
    ScmInfo(url("https://github.com/zio/zio-instrumentation/"), "scm:git:git@github.com:zio/zio-instrumentation.git")
  ),
  developers := List(
    Developer(
      "tabdulradi",
      "Tamer Abdulradi",
      "tamer@abdulradi.com",
      url("https://abdulradi.com")
    )
  ),

  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "UTF-8",
    "-explaintypes",
    "-Yrangepos",
    "-feature",
    "-Xfuture",
    "-language:higherKinds",
    "-language:existentials",
    "-unchecked",
    "-Xlint:_,-type-parameter-shadow",
    "-Ywarn-numeric-widen",
    "-Ywarn-unused",
    "-Ywarn-value-discard"
  ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, 11)) =>
      Seq(
        "-Yno-adapted-args",
        "-Ypartial-unification",
        "-Ywarn-inaccessible",
        "-Ywarn-infer-any",
        "-Ywarn-nullary-override",
        "-Ywarn-nullary-unit"
      )
    case Some((2, 12)) =>
      Seq(
        "-Xsource:2.13",
        "-Yno-adapted-args",
        "-Ypartial-unification",
        "-Ywarn-extra-implicit",
        "-Ywarn-inaccessible",
        "-Ywarn-infer-any",
        "-Ywarn-nullary-override",
        "-Ywarn-nullary-unit",
        "-opt-inline-from:<source>",
        "-opt-warnings",
        "-opt:l:inline"
      )
    case _ => Nil
  }),
  fork in run := true,
  crossScalaVersions := allScala
))

lazy val core = project.settings(
  libraryDependencies ++= Seq(
    "org.scalaz" %% "scalaz-zio" % "1.0-RC5"
  )
)

lazy val opentracing = project.dependsOn(core).settings(
  libraryDependencies ++= Seq(
    "io.opentracing" % "opentracing-api" % "0.31.0"
  )
)

lazy val opencensus = project.dependsOn(core).settings(
  libraryDependencies ++= Seq(
    "io.opencensus" % "opencensus-api" % "0.22.1",
    "io.opencensus" % "opencensus-impl" % "0.22.1"
  )
)

lazy val zipkin = project.dependsOn(core).settings(
  libraryDependencies ++= Seq()
)

lazy val auto = 
  project.in(file("experimental/auto-instrument")).dependsOn(core)

lazy val examples = project.dependsOn(opentracing, opencensus, auto).settings(
  libraryDependencies ++= Seq(
    "ch.qos.logback"   % "logback-classic" % "1.0.13",
    "io.jaegertracing" % "jaeger-client" % "0.32.0",
    "io.opencensus"    % "opencensus-impl" % "0.22.1",
    "io.opencensus"    % "opencensus-exporter-trace-jaeger" % "0.22.1",
  )
)

addCommandAlias("fmt", "all scalafmtSbt scalafmt test:scalafmt")
addCommandAlias("check", "all scalafmtSbtCheck scalafmtCheck test:scalafmtCheck")