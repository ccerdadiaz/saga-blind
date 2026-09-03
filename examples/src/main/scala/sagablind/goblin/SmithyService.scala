package sagablind.goblin

import sagablind.loader.SagaStepProvider
import org.slf4j.LoggerFactory

class SmithyService extends SagaStepProvider:
  private val log = LoggerFactory.getLogger(getClass)
  def stepId = "smithy"

  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    val armLength  = args("armLength").num.toInt
    val weaponType = if armLength > 40 then "longsword" else "shortsword"
    log.debug(s"forging $weaponType for arm length $armLength cm")
    Thread.sleep(500)
    val weaponId = s"W-${scala.util.Random.nextInt(9000) + 1000}"
    log.debug(s"forged $weaponType $weaponId")
    Right(Map("weaponId" -> ujson.Str(weaponId), "weaponType" -> ujson.Str(weaponType)))

  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    val weaponId = args.get("weaponId").map(_.str).getOrElse("unknown")
    log.debug(s"melting down weapon $weaponId")
    Right(())
