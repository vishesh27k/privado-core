package ai.privado.tagger.sink

import ai.privado.model.{Constants}
import ai.privado.tagger.PrivadoSimplePass
import ai.privado.utility.Utilities
import ai.privado.utility.Utilities.{addRuleTags, storeForTag}
import io.shiftleft.codepropertygraph.generated.Cpg
import overflowdb.BatchedUpdate
import io.shiftleft.semanticcpg.language._
import io.joern.dataflowengineoss.language._
import io.joern.dataflowengineoss.queryengine.EngineContext

class APITagger(cpg: Cpg) extends PrivadoSimplePass(cpg) {

  lazy val APISINKS_REGEX =
    "(?i).*(?:url|client|connection|request|execute|load|host|access|fetch|get|set|put|post|trace|patch|send|remove|delete|write|read|assignment|provider).*"

  override def run(builder: BatchedUpdate.DiffGraphBuilder): Unit = {
    val apiInternalSinkPattern = cpg.literal.code(ruleInfo.patterns.head).l
    val apis                   = cpg.call.methodFullName(APISINKS_REGEX).l

    implicit val engineContext: EngineContext = EngineContext(Utilities.getDefaultSemantics())
    val apiFlows                              = apis.reachableByFlows(apiInternalSinkPattern).l

    apiFlows.foreach(flow => {
      val literalCode = flow.elements.head.code
      val apiNode     = flow.elements.last
      addRuleTags(builder, apiNode, ruleInfo)
      storeForTag(builder, apiNode)(Constants.apiUrl, literalCode)
    })
  }
}
