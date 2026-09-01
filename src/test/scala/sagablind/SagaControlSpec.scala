package sagablind

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import sagablind.control.SagaControl
import sagablind.core.DefinitionStatus

import java.nio.file.{Files, Path}

class SagaControlSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  var control:  SagaControl = scala.compiletime.uninitialized
  var sagaFile: Path        = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    control  = SagaControl()
    sagaFile = Files.createTempFile("test-saga", ".saga")
    Files.writeString(sagaFile, """
      |saga: goblin-campaign
      |jar: /libs/goblin.jar
      |steps:
      |  - id: measurements
      |    kind: mandatory
      |    class: com.goblin.Measurements
    """.stripMargin)

  override def afterEach(): Unit =
    Files.deleteIfExists(sagaFile)

  // ── publish / get ─────────────────────────────────────────────────────────

  "SagaControl" should "publish a saga from a .saga file" in:
    control.publish(sagaFile).isRight shouldBe true
    control.size shouldBe 1

  it should "get a saga by name regardless of status" in:
    control.publish(sagaFile)
    control.get("goblin-campaign").isRight shouldBe true

  it should "return Left for unknown saga" in:
    control.get("ghost") shouldBe a[Left[?, ?]]

  it should "withdraw a saga by name" in:
    control.publish(sagaFile)
    control.withdraw("goblin-campaign")
    control.size shouldBe 0

  it should "start in Playing status after publish" in:
    control.publish(sagaFile)
    control.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Playing)

  it should "get returns saga with status" in:
    control.publish(sagaFile)
    control.pause("goblin-campaign")
    val saga = control.get("goblin-campaign").toOption.get
    saga.status shouldBe DefinitionStatus.Paused

  // ── pause / continue ──────────────────────────────────────────────────────

  it should "pause a Playing saga" in:
    control.publish(sagaFile)
    control.pause("goblin-campaign") shouldBe Right(())
    control.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Paused)

  it should "continue a Paused saga" in:
    control.publish(sagaFile)
    control.pause("goblin-campaign")
    control.continue("goblin-campaign") shouldBe Right(())
    control.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Playing)

  it should "not pause an already Paused saga" in:
    control.publish(sagaFile)
    control.pause("goblin-campaign")
    control.pause("goblin-campaign") shouldBe a[Left[?, ?]]

  it should "not continue a Playing saga" in:
    control.publish(sagaFile)
    control.continue("goblin-campaign") shouldBe a[Left[?, ?]]

  // ── stop ──────────────────────────────────────────────────────────────────

  it should "stop a Playing saga" in:
    control.publish(sagaFile)
    control.stop("goblin-campaign") shouldBe Right(())
    control.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Stopped)

  it should "stop a Paused saga" in:
    control.publish(sagaFile)
    control.pause("goblin-campaign")
    control.stop("goblin-campaign") shouldBe Right(())
    control.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Stopped)

  it should "not stop an already Removed saga" in:
    control.publish(sagaFile)
    control.stop("goblin-campaign")
    control.remove("goblin-campaign", instanceCount = 0)
    control.stop("goblin-campaign") shouldBe a[Left[?, ?]]

  // ── remove ────────────────────────────────────────────────────────────────

  it should "remove a Stopped saga with no instances" in:
    control.publish(sagaFile)
    control.stop("goblin-campaign")
    control.remove("goblin-campaign", instanceCount = 0).shouldBe(Right(()))
    control.size shouldBe 0

  it should "not remove a saga that is not Stopped" in:
    control.publish(sagaFile)
    control.remove("goblin-campaign", instanceCount = 0).shouldBe(a[Left[?, ?]])

  it should "not remove a Stopped saga with instances still in flight" in:
    control.publish(sagaFile)
    control.stop("goblin-campaign")
    control.remove("goblin-campaign", instanceCount = 3).shouldBe(a[Left[?, ?]])

  // ── available / all ───────────────────────────────────────────────────────

  it should "list only Playing sagas as available" in:
    control.publish(sagaFile)
    control.available should contain("goblin-campaign")
    control.pause("goblin-campaign")
    control.available shouldNot contain("goblin-campaign")

  it should "list all sagas regardless of status" in:
    control.publish(sagaFile)
    control.pause("goblin-campaign")
    control.all.size shouldBe 1
    control.all.head.status shouldBe DefinitionStatus.Paused

  // ── stopAll ───────────────────────────────────────────────────────────────

  it should "stop all Playing and Paused definitions on stopAll" in:
    val sagaFile2 = Files.createTempFile("test-saga-2", ".saga")
    Files.writeString(sagaFile2, """
      |saga: goblin-patrol
      |jar: /libs/goblin.jar
      |steps:
      |  - id: patrol
      |    kind: mandatory
      |    class: com.goblin.Patrol
    """.stripMargin)

    control.publish(sagaFile)
    control.publish(sagaFile2)
    control.pause("goblin-campaign")

    control.stopAll()

    control.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Stopped)
    control.statusOf("goblin-patrol")   shouldBe Some(DefinitionStatus.Stopped)

    Files.deleteIfExists(sagaFile2)

  it should "not affect already Stopped definitions on stopAll" in:
    control.publish(sagaFile)
    control.stop("goblin-campaign")
    control.stopAll()
    control.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Stopped)
