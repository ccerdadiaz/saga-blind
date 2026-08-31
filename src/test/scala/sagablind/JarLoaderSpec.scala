package sagablind

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import sagablind.core.*
import sagablind.loader.{JarLoader, SagaStepProvider}
import sagablind.fixture.{SmithyStep, BootsStep}
import sagablind.pool.OkvPool

import java.io.File
import java.nio.file.{Files, Paths}
import java.util.jar.{JarEntry, JarOutputStream}

class JarLoaderSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  var loader: JarLoader = scala.compiletime.uninitialized
  var jarPath: String   = scala.compiletime.uninitialized

  override def beforeEach(): Unit =
    loader  = JarLoader()
    jarPath = buildFixtureJar()

  override def afterEach(): Unit =
    Files.deleteIfExists(Paths.get(jarPath))

  // ── helpers ───────────────────────────────────────────────────────────────

  /** Locate a class file using the classloader that loaded it — works with
   *  sbt's custom classloader which does not set java.class.path. */
  def findClassFile(resourcePath: String): Option[java.net.URL] =
    Option(getClass.getClassLoader.getResource(resourcePath))

  def buildFixtureJar(): String =
    val tmp     = Files.createTempFile("saga-blind-fixture", ".jar")
    val jarFile = tmp.toFile
    val jos     = JarOutputStream(java.io.FileOutputStream(jarFile))

    val classNames = List(
      "sagablind/fixture/SmithyStep.class",
      "sagablind/fixture/BootsStep.class",
      "sagablind/fixture/BrokenStep.class",
    )

    classNames.foreach: name =>
      findClassFile(name).foreach: url =>
        jos.putNextEntry(JarEntry(name))
        jos.write(url.openStream().readAllBytes())
        jos.closeEntry()

    jos.close()

    if jarFile.length() < 100 then
      throw RuntimeException(
        s"Fixture jar is empty — could not locate fixture classes via classloader"
      )

    jarFile.getAbsolutePath

  def smithyDescriptor: StepDescriptor = SmithyStep().descriptor
  def bootsDescriptor:  StepDescriptor = BootsStep().descriptor

  // ── load ──────────────────────────────────────────────────────────────────

  "JarLoader" should "load a jar and instantiate declared providers" in:
    val sagaId  = SagaId("goblin-loader-1")
    val result  = loader.load(sagaId, jarPath, List(smithyDescriptor, bootsDescriptor))
    result.isRight shouldBe true
    val providers = result.toOption.get
    providers.keys should contain allOf ("smithy", "boots")

  it should "return Left if jar does not exist" in:
    val sagaId = SagaId("goblin-loader-2")
    val result = loader.load(sagaId, "/nonexistent/path.jar", List(smithyDescriptor))
    result shouldBe a[Left[?, ?]]
    result.swap.getOrElse("") should include("Jar not found")

  it should "return Left if class is not in jar" in:
    val sagaId = SagaId("goblin-loader-3")
    val ghost  = smithyDescriptor.copy(className = "com.goblin.GhostStep")
    val result = loader.load(sagaId, jarPath, List(ghost))
    result shouldBe a[Left[?, ?]]

  it should "instantiate providers that execute correctly" in:
    val sagaId    = SagaId("goblin-loader-4")
    val providers = loader.load(sagaId, jarPath, List(smithyDescriptor)).toOption.get
    val pool      = OkvPool(sagaId)
    providers("smithy").execute(pool) shouldBe Right(())
    pool.get("weaponId") shouldBe Some(ujson.Str("W-042"))

  it should "isolate classloaders between saga instances" in:
    val id1 = SagaId("goblin-loader-5")
    val id2 = SagaId("goblin-loader-6")
    loader.load(id1, jarPath, List(smithyDescriptor))
    loader.load(id2, jarPath, List(smithyDescriptor))
    loader.activeCount shouldBe 2

  it should "release classloader on saga completion" in:
    val sagaId = SagaId("goblin-loader-7")
    loader.load(sagaId, jarPath, List(smithyDescriptor))
    loader.activeCount shouldBe 1
    loader.release(sagaId)
    loader.activeCount shouldBe 0

  it should "release all classloaders independently" in:
    val id1 = SagaId("goblin-loader-9")
    val id2 = SagaId("goblin-loader-10")
    loader.load(id1, jarPath, List(smithyDescriptor))
    loader.load(id2, jarPath, List(bootsDescriptor))
    loader.release(id1)
    loader.activeCount shouldBe 1
    loader.release(id2)
    loader.activeCount shouldBe 0

  // ── memoryStats ───────────────────────────────────────────────────────────

  it should "report memory stats with correct active loader count" in:
    val id1   = SagaId("goblin-loader-11")
    val id2   = SagaId("goblin-loader-12")
    loader.load(id1, jarPath, List(smithyDescriptor))
    loader.load(id2, jarPath, List(bootsDescriptor))
    val stats = loader.memoryStats()
    stats.activeLoaders    shouldBe 2
    stats.heapUsedMb       should be > 0L
    stats.heapMaxMb        should be > 0L
    stats.loadedClassCount should be > 0

  it should "reflect released loaders in stats" in:
    val sagaId = SagaId("goblin-loader-13")
    loader.load(sagaId, jarPath, List(smithyDescriptor))
    loader.release(sagaId)
    loader.memoryStats().activeLoaders shouldBe 0