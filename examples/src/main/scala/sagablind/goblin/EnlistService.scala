package sagablind.goblin

import sagablind.loader.SagaStepProvider
import org.slf4j.LoggerFactory

class EnlistService extends SagaStepProvider:
  private val log = LoggerFactory.getLogger(getClass)
  def stepId = "enlist"

  def execute(args: Map[String, ujson.Value]): Either[Throwable, Map[String, ujson.Value]] =
    val goblinId   = args("goblinId").str
    val weaponType = args("weaponType").str
    val bootSize   = args("bootSize").str
    val hat        = args("hatSerialNumber").str
    log.debug(s"enlisting goblin $goblinId — weapon:$weaponType boots:$bootSize helmet:$hat")
    Thread.sleep(200)
    val enlistmentId = s"E-${scala.util.Random.nextInt(9000) + 1000}"
    log.info(s"goblin $goblinId enlisted — id:$enlistmentId ready for battle!")
    Right(Map("enlistmentId" -> ujson.Str(enlistmentId)))

  def compensate(args: Map[String, ujson.Value]): Either[Throwable, Unit] =
    val id = args.get("enlistmentId").map(_.str).getOrElse("unknown")
    log.debug(s"removing enlistment $id from army registry")
    Right(())
