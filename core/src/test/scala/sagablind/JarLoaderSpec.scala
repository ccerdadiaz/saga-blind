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
      Option(getClass.getClassLoader.getResource(name)).foreach: url =>
        jos.putNextEntry(JarEntry(name))
        jos.write(url.openStream().readAllBytes())
        jos.closeEntry()
    jos.close()
    if jarFile.length() < 100 then
      throw RuntimeException("Fixture jar is empty")
    jarFile.getAbsolutePath

  def smithyDescriptor: StepDescriptor = StepDescriptor(
    id = "smithy", kind = StepKind.Mandatory,
    className = "sagablind.fixture.SmithyStep",
    inputMappings = Nil, compensateMappings = Nil,
  )
  def bootsDescriptor: StepDescriptor = StepDescriptor(
    id = "boots", kind = StepKind.Mandatory,
    className = "sagablind.fixture.BootsStep",
    inputMappings = Nil, compensateMappings = Nil,
  )

  "JarLoader" should "load a jar and instantiate declared providers" in:
    val result = loader.load(SagaId("g-1"), jarPath, List(smithyDescriptor, bootsDescriptor))
    result.isRight shouldBe true
    result.toOption.get.keys should contain allOf ("smithy", "boots")

  it should "return Left if jar does not exist" in:
    loader.load(SagaId("g-2"), "/nonexistent.jar", List(smithyDescriptor))
      .swap.getOrElse("") should include("Jar not found")

  it should "return Left if class is not in jar" in:
    val ghost = smithyDescriptor.copy(className = "com.goblin.GhostStep")
    loader.load(SagaId("g-3"), jarPath, List(ghost)) shouldBe a[Left[?, ?]]

  it should "instantiate providers that execute correctly" in:
    val providers = loader.load(SagaId("g-4"), jarPath, List(smithyDescriptor)).toOption.get
    providers("smithy").execute(Map.empty) shouldBe Right(Map("weaponId" -> ujson.Str("W-042")))

  it should "isolate classloaders between saga instances" in:
    loader.load(SagaId("g-5"), jarPath, List(smithyDescriptor))
    loader.load(SagaId("g-6"), jarPath, List(smithyDescriptor))
    loader.activeCount shouldBe 2

  it should "release classloader on saga completion" in:
    loader.load(SagaId("g-7"), jarPath, List(smithyDescriptor))
    loader.release(SagaId("g-7"))
    loader.activeCount shouldBe 0

  it should "release all classloaders independently" in:
    loader.load(SagaId("g-9"), jarPath, List(smithyDescriptor))
    loader.load(SagaId("g-10"), jarPath, List(bootsDescriptor))
    loader.release(SagaId("g-9"))
    loader.activeCount shouldBe 1
    loader.release(SagaId("g-10"))
    loader.activeCount shouldBe 0

  it should "report memory stats with correct active loader count" in:
    loader.load(SagaId("g-11"), jarPath, List(smithyDescriptor))
    loader.load(SagaId("g-12"), jarPath, List(bootsDescriptor))
    val stats = loader.memoryStats()
    stats.activeLoaders    shouldBe 2
    stats.heapUsedMb       should be > 0L
    stats.loadedClassCount should be > 0

  it should "reflect released loaders in stats" in:
    loader.load(SagaId("g-13"), jarPath, List(smithyDescriptor))
    loader.release(SagaId("g-13"))
    loader.memoryStats().activeLoaders shouldBe 0
