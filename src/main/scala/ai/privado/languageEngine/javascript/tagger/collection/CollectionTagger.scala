package ai.privado.languageEngine.javascript.tagger.collection

import ai.privado.cache.RuleCache
import ai.privado.model.{Constants, InternalTag, RuleInfo}
import ai.privado.tagger.PrivadoParallelCpgPass
import ai.privado.utility.Utilities._
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.{Block, Call, Method, MethodRef}
import io.shiftleft.semanticcpg.language._
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

class CollectionTagger(cpg: Cpg, ruleCache: RuleCache) extends PrivadoParallelCpgPass[RuleInfo](cpg) {
  private val logger       = LoggerFactory.getLogger(this.getClass)
  private val methodUrlMap = mutable.HashMap[Long, String]()
  private val classUrlMap  = mutable.HashMap[Long, String]()

  override def generateParts(): Array[RuleInfo] =
    ruleCache.getRule.collections.filter(_.catLevelTwo == Constants.default).toArray

  override def runOnPart(builder: DiffGraphBuilder, collectionRuleInfo: RuleInfo): Unit = {
    val collectionMethodsCache = mutable.ListBuffer[Method]()

    // Supporting below pattern
    // fastify.get('/endpoint', async (request, reply) => {
    //  return 'Hello, World!';
    // });
    // Supported Framework: Express, Fastify, Featherjs, AdonisJS, Loopback, Restify, Connect
    // TODO: Based on below frameworks improve the logic
    // TODO: Need to support more frameworks Koa, Sails
    val EXPRESS_CLIENT_PATTERN = collectionRuleInfo.patterns.headOption.getOrElse("")
    if (EXPRESS_CLIENT_PATTERN.nonEmpty) {
      val expressCollectionCalls =
        cpg.call.methodFullName(EXPRESS_CLIENT_PATTERN).l
      for (call <- expressCollectionCalls) {
        if (call.argument.nonEmpty) {
          if (call.argument.isMethodRef.nonEmpty) {
            val isValid = getCollectionMethodsCache(call, call.argument.isMethodRef.head.referencedMethod.id())
            if (isValid) {
              collectionMethodsCache += call.argument.isMethodRef.head.referencedMethod
            }
          }
        }
      }
    }

    // Supporting below pattern
    // {
    //   method: 'PUT',
    //   path: '/movies/{id}',
    //   handler: async (req, emailId) => {
    //     const payload = { ...req.payload, emailId }
    //     const status = await req.mongo.db.collection('movies').updateOne({ _id: ObjectID }, { $set: payload });
    //     return status;
    //   }
    // }
    // General Object with method, path & handler block
    val generalAssignmentCallsWithMethodParam =
      cpg
        .call("<operator>.assignment")
        .code("(?i).*(method|type).{0,4}[=]{1}.{0,4}(get|put|post|delete|trace|patch|option).*")
        .l
    for (call <- generalAssignmentCallsWithMethodParam) {
      if (call.astParent.isBlock) {
        val result = Try {
          getRouteAndHandlerFromBlock(call.astParent.asInstanceOf[Block], "(url|path|uri|route)", "handler")
        }

        result match {
          case Success((route, handler)) =>
            if (route.nonEmpty && handler.nonEmpty) {
              if (handler.isMethodRef) {
                val referencedMethod = handler.referencedMethod
                methodUrlMap.addOne(referencedMethod.id() -> route)
                collectionMethodsCache += referencedMethod
              }
            }
          case Failure(exception) =>
            logger.error(s"An error occurred: ${exception.getMessage}")
        }
      }
    }

    val CONNECT_CLIENT_PATTERN = "(?:connect).*"
    val connectCollectionCalls = cpg.call.methodFullName(CONNECT_CLIENT_PATTERN).l
    for (call <- connectCollectionCalls) {
      if (call.argument.nonEmpty) {
        val isValid = getCollectionMethodsCache(call, call.method.id())
        if (isValid) {
          collectionMethodsCache += call.method
        }
      }
    }

    tagDirectSources(cpg, builder, collectionMethodsCache.l, collectionRuleInfo)
    tagDerivedSources(cpg, builder, collectionMethodsCache.l, collectionRuleInfo)
  }

  def getRouteAndHandlerFromBlock(block: Block, pathField: String, handlerField: String): (String, MethodRef) = {
    val objectKeyValuePairs      = block.astChildren.isCall.name("<operator>.assignment").l
    var handlerMethod: MethodRef = null
    var path: String             = ""
    val pathRegex: Regex         = pathField.r

    for (keyVal <- objectKeyValuePairs) {
      if (keyVal.astChildren.nonEmpty) {
        val objKeyVal = keyVal.astChildren
        val key       = objKeyVal.isCall.head

        if (pathRegex.findFirstIn(key.code).isDefined && key.astSiblings.head.isLiteral) {
          path = key.astSiblings.head.code
        }

        if (key.code.contains(handlerField) && key.astSiblings.head.isMethodRef) {
          handlerMethod = cpg.methodRef.id(key.astSiblings.head.id()).head
        }
      }
    }
    (path, handlerMethod)
  }

  def getCollectionMethodsCache(call: Call, methodId: Long): Boolean = {
    var isValid = false

    if (call.argument.isLiteral.nonEmpty) {
      val endpoint = getRoute(call.argument.isLiteral.head.code)
      if (endpoint.nonEmpty) {
        methodUrlMap.addOne(methodId -> endpoint)
        isValid = true
      }
    } else if (call.argument.isCall.nonEmpty) {
      val formatStringCallCode   = call.argument.isCall.name("<operator>.formatString")
      val additionStringCallCode = call.argument.isCall.name("<operator>.addition")

      if (formatStringCallCode.nonEmpty) {
        val endpoint = convertFormatString(formatStringCallCode.head.code)
        if (endpoint.nonEmpty) {
          methodUrlMap.addOne(methodId -> endpoint)
          isValid = true
        }
      }

      if (additionStringCallCode.nonEmpty) {
        val endpoint = convertAdditionString(additionStringCallCode.head.code)
        if (endpoint.nonEmpty) {
          methodUrlMap.addOne(methodId -> endpoint)
          isValid = true
        }
      }
    }
    isValid
  }

  def tagDirectSources(
    cpg: Cpg,
    builder: DiffGraphBuilder,
    collectionMethods: List[Method],
    collectionRuleInfo: RuleInfo
  ): Unit = {
    val collectionPoints = collectionMethods.flatMap(collectionMethod => {
      ruleCache.getRule.sources.flatMap(sourceRule => {
        val parameters = collectionMethod.parameter
        val locals     = collectionMethod.local
        val literals   = collectionMethod.call("(?:get).*").argument.isLiteral

        // TODO: handle cases where `request.args.get('id', None)` used directly in handler block without method param
        val matchingParameters = parameters.where(_.name(sourceRule.combinedRulePattern)).whereNot(_.code("self")).l
        val matchingLocals     = locals.code(sourceRule.combinedRulePattern).l
        val matchingLiterals = literals
          .code("(\"|'|`)(" + sourceRule.combinedRulePattern + ")(\"|'|`)")
          .whereNot(_.code(".*\\s.*"))
          .l

        if (!(matchingParameters.isEmpty && matchingLocals.isEmpty && matchingLiterals.isEmpty)) {
          matchingParameters.foreach(parameter =>
            storeForTag(builder, parameter, ruleCache)(Constants.id, sourceRule.id)
          )
          matchingLocals.foreach(local => storeForTag(builder, local, ruleCache)(Constants.id, sourceRule.id))
          matchingLiterals.foreach(literal => storeForTag(builder, literal, ruleCache)(Constants.id, sourceRule.id))
          Some(collectionMethod)
        } else {
          None
        }

      })
    })

    tagMethodEndpoints(builder, collectionPoints.l, collectionRuleInfo)
  }

  def tagDerivedSources(
    cpg: Cpg,
    builder: DiffGraphBuilder,
    collectionMethods: List[Method],
    collectionRuleInfo: RuleInfo
  ): Unit = {
    // Implementation to also mark the collection points which use derived type declaration as there parameters
    val derivedTypeDecl = (getAllDerivedTypeDecl(cpg, InternalTag.OBJECT_OF_SENSITIVE_CLASS_BY_MEMBER_NAME.toString) ++
      getAllDerivedTypeDecl(cpg, InternalTag.OBJECT_OF_SENSITIVE_CLASS_BY_MEMBER_TYPE.toString) ++
      getAllDerivedTypeDecl(cpg, InternalTag.OBJECT_OF_SENSITIVE_CLASS_BY_INHERITANCE.toString)).toSet

    val collectionPointsFromDerivedTypeDecl = collectionMethods.flatMap(collectionMethod => {
      var parameters = collectionMethod.parameter.l
      parameters = collectionMethod.parameter
        .where(_.typeFullName.filter(fullName => !derivedTypeDecl.contains(fullName)))
        .l

      if (parameters.isEmpty) {
        None
      } else {
        // Have removed the earlier code, where we were fetching all the referencing identifiers of parameter and then tagging, because we were missing on cases where the parameter is not used in the code
        parameters
          .whereNot(_.code("this"))
          .foreach(parameter => {
            parameter.tag
              .name(Constants.privadoDerived + ".*")
              .foreach(refTag => {
                storeForTag(builder, parameter, ruleCache)(refTag.name, refTag.value)
              })
          })
        collectionMethod
      }
    })

    tagMethodEndpoints(builder, collectionPointsFromDerivedTypeDecl.l, collectionRuleInfo)
  }

  private def tagMethodEndpoints(
    builder: DiffGraphBuilder,
    collectionPoints: List[Method],
    collectionRuleInfo: RuleInfo,
    returnByName: Boolean = false
  ) = {
    collectionPoints.foreach(collectionPoint => {
      addRuleTags(builder, collectionPoint, collectionRuleInfo, ruleCache)
      storeForTag(builder, collectionPoint, ruleCache)(
        InternalTag.COLLECTION_METHOD_ENDPOINT.toString,
        getFinalEndPoint(collectionPoint, returnByName)
      )
    })
  }

  private def getAllDerivedTypeDecl(cpg: Cpg, objectName: String) = {
    cpg.identifier.where(_.tag.name(objectName)).typeFullName.dedup.l
  }

  private def getFinalEndPoint(collectionPoint: Method, returnByName: Boolean): String = {
    if (returnByName) {
      collectionPoint.name
    } else {
      val methodUrl = methodUrlMap.getOrElse(collectionPoint.id(), "")
      Try(classUrlMap.getOrElse(collectionPoint.typeDecl.head.id(), "")) match {
        case Success(classUrl) => classUrl + methodUrl
        case Failure(e) =>
          methodUrl
      }
    }
  }

  /** Returns the route extracted from the code
    */
  private def getRoute(code: String): String = {
    val regex = """(\"|\'|`)(/.*?)(\"|\'|`)""".r
    Try(regex.findFirstMatchIn(code).map(_.group(2))) match {
      case Success(url) => if (url == None) "" else url.get
      case Failure(e) =>
        logger.debug("Exception : ", e)
        ""
    }
  }

  def convertAdditionString(input: String): String = {
    input
      .replaceAll("\\+", "")
      .replaceAll("""(\"|'|`)""", "")
      .replaceAll(" ", "")
  }

  def convertFormatString(input: String): String = {
    input
      .replaceAll("<operator>.formatString\\(", "")
      .replaceAll("""(\"|'|`|\)|,| )""", "")
  }
}
