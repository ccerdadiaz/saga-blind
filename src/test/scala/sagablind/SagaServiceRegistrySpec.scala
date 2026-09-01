package sagablind

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import sagablind.control.SagaServiceRegistry
import sagablind.core.DefinitionStatus

import java.nio.file.{Files, Path}

class SagaServiceRegistrySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  var registry: SagaServiceRegistry = scala.compiletime.uninitialized
  var sagaFile: Path                = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    registry = SagaServiceRegistry()
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

  // ── publish / resolve ─────────────────────────────────────────────────────

  "SagaServiceRegistry" should "publish a saga from a .saga file" in:
    registry.publish(sagaFile).isRight shouldBe true
    registry.size shouldBe 1

  it should "resolve a Playing saga by name" in:
    registry.publish(sagaFile)
    registry.resolve("goblin-campaign").isRight shouldBe true

  it should "not resolve an unknown saga" in:
    registry.resolve("ghost") shouldBe a[Left[?, ?]]

  it should "withdraw a saga by name" in:
    registry.publish(sagaFile)
    registry.withdraw("goblin-campaign")
    registry.size shouldBe 0

  it should "start in Playing status after publish" in:
    registry.publish(sagaFile)
    registry.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Playing)

  // ── pause / continue ──────────────────────────────────────────────────────

  it should "pause a Playing saga" in:
    registry.publish(sagaFile)
    registry.pause("goblin-campaign") shouldBe Right(())
    registry.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Paused)

  it should "not resolve a Paused saga for new instances" in:
    registry.publish(sagaFile)
    registry.pause("goblin-campaign")
    registry.resolve("goblin-campaign") shouldBe a[Left[?, ?]]

  it should "continue a Paused saga" in:
    registry.publish(sagaFile)
    registry.pause("goblin-campaign")
    registry.continue("goblin-campaign") shouldBe Right(())
    registry.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Playing)

  it should "not pause an already Paused saga" in:
    registry.publish(sagaFile)
    registry.pause("goblin-campaign")
    registry.pause("goblin-campaign") shouldBe a[Left[?, ?]]

  it should "not continue a Playing saga" in:
    registry.publish(sagaFile)
    registry.continue("goblin-campaign") shouldBe a[Left[?, ?]]

  // ── stop ──────────────────────────────────────────────────────────────────

  it should "stop a Playing saga" in:
    registry.publish(sagaFile)
    registry.stop("goblin-campaign") shouldBe Right(())
    registry.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Stopped)

  it should "stop a Paused saga" in:
    registry.publish(sagaFile)
    registry.pause("goblin-campaign")
    registry.stop("goblin-campaign") shouldBe Right(())
    registry.statusOf("goblin-campaign") shouldBe Some(DefinitionStatus.Stopped)

  it should "not resolve a Stopped saga for new instances" in:
    registry.publish(sagaFile)
    registry.stop("goblin-campaign")
    registry.resolve("goblin-campaign") shouldBe a[Left[?, ?]]

  it should "not stop an already Removed saga" in:
    registry.publish(sagaFile)
    registry.stop("goblin-campaign")
    registry.remove("goblin-campaign", instanceCount = 0)
    registry.stop("goblin-campaign") shouldBe a[Left[?, ?]]

  // ── remove ────────────────────────────────────────────────────────────────

  it should "remove a Stopped saga with no instances" in:
    registry.publish(sagaFile)
    registry.stop("goblin-campaign")
    registry.remove("goblin-campaign", instanceCount = 0).shouldBe(Right(()))
    registry.size shouldBe 0

  it should "not remove a saga that is not Stopped" in:
    registry.publish(sagaFile)
    registry.remove("goblin-campaign", instanceCount = 0).shouldBe(a[Left[?, ?]])

  it should "not remove a Stopped saga with instances still in flight" in:
    registry.publish(sagaFile)
    registry.stop("goblin-campaign")
    registry.remove("goblin-campaign", instanceCount = 3).shouldBe(a[Left[?, ?]])

  // ── available / all ───────────────────────────────────────────────────────

  it should "list only Playing sagas as available" in:
    registry.publish(sagaFile)
    registry.available should contain("goblin-campaign")
    registry.pause("goblin-campaign")
    registry.available shouldNot contain("goblin-campaign")

  it should "list all sagas regardless of status" in:
    registry.publish(sagaFile)
    registry.pause("goblin-campaign")
    registry.all.size shouldBe 1
    registry.all.head.status shouldBe DefinitionStatus.Paused
