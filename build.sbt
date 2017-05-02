name := "cpc-anal"

version := "1.0"

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  "org.scala-lang" % "scala-reflect" % scalaVersion.value,
  "com.typesafe" % "config" % "1.2.1"
)

javacOptions ++= Seq(
  "-encoding", "UTF-8"
)

compileOrder := CompileOrder.JavaThenScala

