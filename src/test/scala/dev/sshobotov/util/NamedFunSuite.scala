package dev.sshobotov.util

abstract class NamedFunSuite extends munit.FunSuite {

  override def munitTestTransforms = super.munitTestTransforms ++ List(
    new TestTransform("append `entity should`", { test =>
      test.withName(test.location.filename.replace("Test.scala", "") + " should " + test.name)
    })
  )
}
