
package ai.privado.audit

import ai.privado.audit.AuditReportEntryPoint.{eliminateEmptyCellValueIfExist}
import ai.privado.exporter.JSONExporter
import ai.privado.model.{Constants, InternalTag}
import ai.privado.utility.StatsRecorder
import ai.privado.model.Language
import ai.privado.model.Language.Language
import io.circe.{Decoder, Encoder}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.shiftleft.codepropertygraph.generated.Cpg
import io.shiftleft.codepropertygraph.generated.nodes.*
import io.shiftleft.semanticcpg.language.*
import org.slf4j.LoggerFactory

import java.util.Calendar
import scala.collection.Map
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Failure, Success, Try}

object DEDSourceDiscoveryUtils {

  private val logger = LoggerFactory.getLogger(getClass)
  // Not used for security-purposes. Only to generate a unique identifier.
  private lazy val md5 = java.security.MessageDigest.getInstance("MD5")

  // Regular expression pattern to filter common language types
  val filterCommonLangTypes =
    "(?i)(class|window|str|list|dict|bool|chr|icon|boolean|number|bigdecimal|jwt|any|let|define|enum|asttype|nil|null|none|not|uuid|java|undefined|nan|empty|objectid|_id|tostring|valueof|json|mime(type){0,1}|true|false|before|after|arr|typeof|match|to_dict|toarray|todate|case|<global>|<fakeNew>|(buf|array|int|num|type|float|byte|string|blob|object).{0,1}|set|map|hashmap|vector|deque|function|method|property|char|short|long|double|decimal|datetime|date|time|timestamp|enum|flag|void|interface|trait|package|namespace|record|tuple|struct|component|hook|hoc|service)"

  // Regular expression pattern to filter common variable names
  val filterCommonVars =
    "(?i)(.*(\\\\|\\/).*|cls|self|ctx|constructor|context|super|new|prototype|main|use|foreach|copy|random|trim|skip|replace|.*(dto|exception)|slice|fill|some|every|concat|contains|apply|merge|stmt|format|where|name|data|regexp|.{0,1}(sort|in|slug|match|ne|regex|or|sum|and)|session|status|patch|accept|event|env|cmd|push|join|split|splice|filter|reduce|shift|unshift|retry|start|buffer|thread|length|staticmethod|app|next|end|req|console|push|pop|handler|server|catch|then|uri|split|exp|other|info|debug|warning|critical|exception|size|max|text|http|query|href|write|(sql|row|len|err|res|ret|obj|msg|val|key|item|url|tmp|col|file|img|test|result|path|module|import|export|log|key|value|include|load|dump).{0,1})"

  // List of prefixes to filter out common variables that start with these values
  private val filterCommonVarsStartsWith =
    "$obj|$group|$set|$id|$lte|$gte|$options|__|_tmp_|tmp|$iterLocal|_result|file|return|is|sha_|this|get|set|post|put|update|create|clear|check|find|insert|execute|encode|decode|aggregate|assert|parse|generate|validate|process|download|upload|delete|handle|mongo|http|validation|exception|param|attr|arg|_iterator|{|[|log|error|iterator|logger|<tmp-|iter_tmp|toLocale|indexOf|page"
  val filterCommonVarsStartsWithArr = filterCommonVarsStartsWith.split("\\|")

  // Additional Language specific filters
  def getLanguageSpecificFilters(lang: Language): String = {
    lang match {
      case Language.JAVASCRIPT =>
        "(?i)((<anon-class>|<lambda>|_computed_object_property_)\\d+|promise|i18n|ajv|async|axios|connectdb|require.{0,1}|express|moment|document|css|children|style|ref|props|success|route|state|.*reducer.{0,1}|action|link|render|onclick|hasOwnProperty|stringify|date|use(state|effect|dispatch|form|selector|query|memo|router|swrconfig)|request|send|form|loading|dispatch|ngif|ngforof|inject|component|router|validators|formgroup|formcontrol|subscribe|observable|jquery|vue|react|angular|.*[.](js|css|ts))"
      case Language.GO =>
        "(?i)(context|block|nonce|syscall|buf|fmt|http|log|os|io|error|http|httpclient|httprequest|httpresponse)"
      case Language.PYTHON =>
        "(?i)(<module>|.*<meta>|.*_tmp\\d+|print|boto.{0,1}|s3|request.{0,1}|classmethod|hasattr|int32|eval|cursor|append|now|close|system|pid|strip|add|acknowledged|apply_async|limit|statusCode|extend|list_objects.{0,6}|pytest|datetime|pandas|numpy|scipy|sklearn|matplotlib|flask|django|argparse|os|sys|format_exc|read|std(out|in|err)|exit|compile|open)"
      case Language.JAVA | Language.KOTLIN =>
        "(?i)(system|out|buf|(com|io|org|net|androidx|software|io)[.]{1}.*|printstream|scanner|stringbuilder|thread|exception|bufferedreader|inputstream|outputstream|integer|character)"
      case Language.PHP =>
        "(?i)(_post|_get|_session|_files|_server|_cookie|uid|mysqli|pdo|print_r|var_dump|echo|header|session_start|session_destroy)"
      case Language.RUBY =>
        "(?i)(node|tree|raise|gem|rails|sinatra|rake|erb|haml)"
      case Language.CSHARP =>
        "(?i)(table|migrationBuilder|modelBuilder|program|yaml|context|cts|instance|idx|system|console|io|collections|generic|threading|tasks|linq|xml|entity|configuration|component|service)"
    }
  }

  def nodeIdentifier(filePath: String, name: String, nodeType: String, lineNumber: String): String =
    md5
      .digest(s"$filePath-$name".getBytes)
      .map(0xff & _)
      .map("%02x".format(_))
      .foldLeft("")(_ + _)

  def nodeOffset(node: TypeDecl | Member | MethodParameterIn | Identifier | Local | FieldIdentifier): String =
    node.lineNumber match {
      case Some(offset) => offset.toString
      case _            => ""
    }

  def getSourceRuleId(
    node: TypeDecl | Member | MethodParameterIn | Identifier | Local | FieldIdentifier | Call
  ): String = {
    // Extract the source rule ID, defaulting to AUDIT_EMPTY_CELL_VALUE if not found
    val sourceRuleId =
      node.tag.nameExact(Constants.id).value.headOption.getOrElse(AuditReportConstants.AUDIT_EMPTY_CELL_VALUE)

    // Determine if tagging is disabled by DED
    val taggingDisabledByDED = node.tag.nameExact(InternalTag.TAGGING_DISABLED_BY_DED.toString).nonEmpty

    if (taggingDisabledByDED) {
      AuditReportConstants.AUDIT_EMPTY_CELL_VALUE
    } else {
      sourceRuleId
    }
  }

  def getNodeLocationAndUniqueId(
    node: TypeDecl | Member | MethodParameterIn | Identifier | Local | FieldIdentifier,
    name: String,
    nodeType: String
  ): (String, String, String) = {
    val path         = node.file.name.headOption.getOrElse(Constants.EMPTY)
    val lineNumber   = DEDSourceDiscoveryUtils.nodeOffset(node)
    val nodeUniqueId = DEDSourceDiscoveryUtils.nodeIdentifier(path, name, nodeType, lineNumber)

    (path, lineNumber, nodeUniqueId)
  }

  // Add Members in workbookResult
  def getNewMembers(
    xtocpg: Try[Cpg],
    workbookResult: ListBuffer[List[String]],
    lang: Language
  ): ListBuffer[List[String]] = {
    val typeDeclMembersMap = xtocpg match {
      case Success(cpg) => {
        val memberInfoMap = mutable.HashMap[TypeDecl, List[Member]]()
        val members       = mutable.ListBuffer[Member]()
        cpg.typeDecl.foreach(typeDeclNode => {
          if (typeDeclNode.member.nonEmpty) {
            val filteredMembers =
              typeDeclNode.member
                // Filter out members with length <= 2
                .filter(i => i.name.length > 2)
                // Filter out members that start with any of the specified prefixes
                .filter(i => !filterCommonVarsStartsWithArr.exists(xx => i.name.toLowerCase.startsWith(xx.toLowerCase)))
                // Filter out members matching common language types pattern
                .filter(i => !i.name.matches(filterCommonLangTypes))
                // Filter out members matching common variable names pattern
                .filter(i => !i.name.matches(filterCommonVars))
                // Filter out members matching language-specific filters
                .filter(i => !i.name.matches(DEDSourceDiscoveryUtils.getLanguageSpecificFilters(lang)))
                // Filter out identifier which are potentially function calls
                .filter(i => !i.typeFullName.matches("\\(.*\\).=>.*"))
                .dedup
                .l
            memberInfoMap.put(typeDeclNode, filteredMembers)
            members ++= filteredMembers
          }
        })
        memberInfoMap.toMap
      }
      case Failure(ex) => {
        logger.debug(f"Error while getting new member ", ex)
        Map[TypeDecl, List[Member]]()
      }
    }

    val addedIdentifiers = mutable.Set[String]()
    typeDeclMembersMap.foreach { case (typeDecl, members) =>
      val nodeType = AuditReportConstants.ELEMENT_DISCOVERY_CLASS_NAME
      val (_, offset, typeDeclNodeUniqueId) =
        DEDSourceDiscoveryUtils.getNodeLocationAndUniqueId(typeDecl, typeDecl.fullName, nodeType)

      members.foreach(member => {
        val nodeType = AuditReportConstants.ELEMENT_DISCOVERY_NODE_TYPE_MEMBER
        val (path, lineNumber, nodeUniqueId) =
          DEDSourceDiscoveryUtils.getNodeLocationAndUniqueId(member, member.name, nodeType)

        val identifierUniqueKey = s"${member.typeFullName}$path${member.name}"
        val sourceRuleId        = DEDSourceDiscoveryUtils.getSourceRuleId(member)

        if (
          !member.name
            .matches(AuditReportConstants.JS_ELEMENT_DISCOVERY_TYPE_EXCLUDE_REGEX)
          && !member.name
            .matches(AuditReportConstants.JS_ELEMENT_DISCOVERY_EXCLUDE_PARAMS_REGEX)
          && !member.name.matches(AuditReportConstants.JS_ELEMENTS_TO_BE_EXCLUDED)
          && !addedIdentifiers.contains(identifierUniqueKey)
        )
          addedIdentifiers.add(identifierUniqueKey)
        workbookResult += List(
          typeDecl.fullName,
          typeDecl.file.name.headOption.getOrElse(AuditReportConstants.AUDIT_EMPTY_CELL_VALUE),
          "0.0",
          member.name,
          member.typeFullName,
          sourceRuleId.startsWith("Data.Sensitive.").toString,
          sourceRuleId,
          AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
          AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
          AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
          lineNumber,
          nodeUniqueId,
          nodeType
        )
      })
    }

    workbookResult
  }

  // Add MethodParameters in workbookResult
  def getNewMethodParametersFromTypes(
    xtocpg: Try[Cpg],
    workbookResult: ListBuffer[List[String]],
    lang: Language
  ): ListBuffer[List[String]] = {
    val methodParameters = xtocpg match {
      case Success(cpg) => {
        val parameters = mutable.ListBuffer[MethodParameterIn]()
        cpg.method.foreach(typeDeclNode => {
          if (typeDeclNode.parameter.nonEmpty) {
            val filteredMembers =
              typeDeclNode.parameter
                // Filter out members with length <= 2
                .filter(i => i.name.length > 2)
                // Filter out members that start with any of the specified prefixes
                .filter(i => !filterCommonVarsStartsWithArr.exists(xx => i.name.toLowerCase.startsWith(xx.toLowerCase)))
                // Filter out members matching common language types pattern
                .filter(i => !i.name.matches(filterCommonLangTypes))
                // Filter out members matching common variable names pattern
                .filter(i => !i.name.matches(filterCommonVars))
                // Filter out members matching language-specific filters
                .filter(i => !i.name.matches(DEDSourceDiscoveryUtils.getLanguageSpecificFilters(lang)))
                // Filter out identifier which are potentially function calls
                .filter(i => !i.typeFullName.matches("\\(.*\\).=>.*"))
                .dedup
                .l
            parameters ++= filteredMembers
          }
        })
        parameters.toList
      }
      case Failure(ex) => {
        logger.debug(f"Error while getting new method param ", ex)
        List[MethodParameterIn]()
      }
    }

    val addedMethodParamaters = mutable.Set[String]()
    methodParameters.foreach(mParams => {
      val nodeType = AuditReportConstants.ELEMENT_DISCOVERY_NODE_TYPE_METHOD_PARAM
      val (path, lineNumber, nodeUniqueId) =
        DEDSourceDiscoveryUtils.getNodeLocationAndUniqueId(mParams, mParams.name, nodeType)

      val identifierUniqueKey = s"${mParams.typeFullName}$path${mParams.name}"
      val sourceRuleId        = DEDSourceDiscoveryUtils.getSourceRuleId(mParams)

      if (
        !mParams.name.matches(AuditReportConstants.JS_ELEMENT_DISCOVERY_TYPE_EXCLUDE_REGEX)
        && !mParams.name.matches(AuditReportConstants.JS_ELEMENT_DISCOVERY_EXCLUDE_PARAMS_REGEX)
        && !mParams.name.matches(AuditReportConstants.JS_ELEMENTS_TO_BE_EXCLUDED)
        && !addedMethodParamaters.contains(identifierUniqueKey)
      )
        addedMethodParamaters.add(identifierUniqueKey)
      workbookResult += List(
        mParams.typeFullName,
        mParams.file.name.headOption.getOrElse(AuditReportConstants.AUDIT_EMPTY_CELL_VALUE),
        "0.0",
        mParams.name,
        mParams.typeFullName,
        sourceRuleId.startsWith("Data.Sensitive.").toString,
        sourceRuleId,
        AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
        AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
        AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
        lineNumber,
        nodeUniqueId,
        nodeType
      )
    })

    workbookResult
  }

  // Add Identifiers in workbookResult
  def getIdentifiers(
    xtocpg: Try[Cpg],
    workbookResult: ListBuffer[List[String]],
    lang: Language
  ): ListBuffer[List[String]] = {
    val identifiers = xtocpg match {
      case Success(cpg) => {
        cpg.identifier
          .groupBy(i => (i.name, i.file.name.head))
          .map { case (_, identifiers) => identifiers.head }
          // Filter out identifiers with length <= 2
          .filter(i => i.name.length > 2)
          // Filter out identifiers that start with any of the specified prefixes
          .filter(i => !filterCommonVarsStartsWithArr.exists(xx => i.name.toLowerCase.startsWith(xx.toLowerCase)))
          // Filter out identifiers matching common language types pattern
          .filter(i => !i.name.matches(filterCommonLangTypes))
          // Filter out identifiers matching common variable names pattern
          .filter(i => !i.name.matches(filterCommonVars))
          // Filter out identifiers matching language-specific filters
          .filter(i => !i.name.matches(DEDSourceDiscoveryUtils.getLanguageSpecificFilters(lang)))
          // Filter out identifier which are potentially function calls
          .filter(i => !i.typeFullName.matches("\\(.*\\).=>.*|(\\w|_|-)+:(\\w|_|-)+"))
          // Filter out identifier which are potentially import calls
          .filter(i => !i.astSiblings.isCall.name("import|require").nonEmpty)
          .l
      }
      case Failure(ex) => {
        logger.debug(f"Error while getting Identifier ", ex)
        List[Identifier]()
      }
    }

    val addedIdentifiers = mutable.Set[String]()
    identifiers.foreach(identifier => {
      val nodeType = AuditReportConstants.ELEMENT_DISCOVERY_NODE_TYPE_IDENTIFIER
      val (path, lineNumber, nodeUniqueId) =
        DEDSourceDiscoveryUtils.getNodeLocationAndUniqueId(identifier, identifier.name, nodeType)
      val identifierUniqueKey = s"${identifier.typeFullName}$path${identifier.name}"
      val sourceRuleId        = DEDSourceDiscoveryUtils.getSourceRuleId(identifier)

      if (
        identifier.name.matches(AuditReportConstants.JS_ELEMENT_DISCOVERY_TYPE_EXCLUDE_REGEX)
        && !identifier.name.matches(AuditReportConstants.JS_ELEMENT_DISCOVERY_EXCLUDE_PARAMS_REGEX)
        && !identifier.name.matches(AuditReportConstants.JS_ELEMENTS_TO_BE_EXCLUDED)
        && !addedIdentifiers.contains(identifierUniqueKey)
      )
        addedIdentifiers.add(identifierUniqueKey)
      workbookResult += List(
        identifier.typeFullName,
        identifier.file.name.headOption.getOrElse(AuditReportConstants.AUDIT_EMPTY_CELL_VALUE),
        "0.0",
        identifier.name,
        identifier.typeFullName,
        sourceRuleId.startsWith("Data.Sensitive.").toString,
        sourceRuleId,
        AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
        AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
        AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
        lineNumber,
        nodeUniqueId,
        nodeType
      )
    })

    workbookResult
  }

  // Add FieldIdentifiers in workbookResult
  def getFieldAccessIdentifier(
    xtocpg: Try[Cpg],
    workbookResult: ListBuffer[List[String]],
    lang: Language
  ): ListBuffer[List[String]] = {
    val identifierSourceRuleIdMap = new mutable.HashMap[Long, String]()
    val fieldIdentifiers = xtocpg match {
      case Success(cpg) => {
        cpg.fieldAccess.fieldIdentifier
          .groupBy(i => (i.canonicalName, i.file.name.head))
          .map { case (_, identifiers) => identifiers.head }
          // Filter out identifiers with length <= 2
          .filter(i => i.canonicalName.length > 2)
          // Filter out identifiers that start with any of the specified prefixes
          .filter(i =>
            !filterCommonVarsStartsWithArr.exists(xx => i.canonicalName.toLowerCase.startsWith(xx.toLowerCase))
          )
          // Filter out identifiers matching common language types pattern
          .filter(i => !i.canonicalName.matches(filterCommonLangTypes))
          // Filter out identifiers matching common variable names pattern
          .filter(i => !i.canonicalName.matches(filterCommonVars))
          // Filter out identifiers matching language-specific filters
          .filter(i => !i.canonicalName.matches(DEDSourceDiscoveryUtils.getLanguageSpecificFilters(lang)))
          // Filter out identifier which are potentially export calls
          .filter(i => !i.astSiblings.isIdentifier.name("import|exports").nonEmpty)
          // Filter out fieldIdentifiers like `session` of format `res.session.XXX`
          .whereNot(_.astParent.astParent.isCall.name("<operator>.fieldAccess"))
          .map((i: FieldIdentifier) => {
            if (i.astParent.isCall) {
              identifierSourceRuleIdMap
                .addOne(i.id, DEDSourceDiscoveryUtils.getSourceRuleId(i.astParent.asInstanceOf[Call]))
            }
            i
          })
          .l
      }
      case Failure(ex) => {
        logger.debug(f"Error while getting FieldIdentifier ", ex)
        List[FieldIdentifier]()
      }
    }

    val addedFieldIdentifiers = mutable.Set[String]()
    fieldIdentifiers.foreach(fIdentifier => {
      val nodeType = AuditReportConstants.ELEMENT_DISCOVERY_NODE_TYPE_FIELD_IDENTIFIER
      val (path, lineNumber, nodeUniqueId) =
        DEDSourceDiscoveryUtils.getNodeLocationAndUniqueId(fIdentifier, fIdentifier.canonicalName, nodeType)
      val localsUniqueKey = s"NA$path${fIdentifier.canonicalName}"
      val sourceRuleId =
        identifierSourceRuleIdMap.get(fIdentifier.id).getOrElse(AuditReportConstants.AUDIT_EMPTY_CELL_VALUE)

      if (
        !fIdentifier.canonicalName.matches(AuditReportConstants.JS_ELEMENT_DISCOVERY_TYPE_EXCLUDE_REGEX)
        && !fIdentifier.canonicalName.matches(AuditReportConstants.JS_ELEMENT_DISCOVERY_EXCLUDE_PARAMS_REGEX)
        && !fIdentifier.canonicalName.matches(AuditReportConstants.JS_ELEMENTS_TO_BE_EXCLUDED)
        && !addedFieldIdentifiers.contains(localsUniqueKey)
      )
        addedFieldIdentifiers.add(localsUniqueKey)
      workbookResult += List(
        "NA",
        fIdentifier.file.head.name,
        "0.0",
        fIdentifier.canonicalName,
        "NA",
        sourceRuleId.startsWith("Data.Sensitive.").toString,
        sourceRuleId,
        AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
        AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
        AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,
        lineNumber,
        nodeUniqueId,
        nodeType
      )
    })

    workbookResult
  }

  def getHeaderList(): List[String] = {
    List(
      AuditReportConstants.ELEMENT_DISCOVERY_CLASS_NAME,
      AuditReportConstants.ELEMENT_DISCOVERY_FILE_NAME,
      AuditReportConstants.FILE_PRIORITY_SCORE,
      AuditReportConstants.ELEMENT_DISCOVERY_MEMBER_NAME,
      AuditReportConstants.ELEMENT_DISCOVERY_MEMBER_TYPE,
      AuditReportConstants.ELEMENT_DISCOVERY_TAGGED_NAME,
      AuditReportConstants.ELEMENT_DISCOVERY_SOURCE_RULE_ID,
      AuditReportConstants.ELEMENT_DISCOVERY_INPUT_COLLECTION,
      AuditReportConstants.ELEMENT_DISCOVERY_COLLECTION_ENDPOINT,
      AuditReportConstants.ELEMENT_DISCOVERY_METHOD_NAME,
      AuditReportConstants.ELEMENT_DISCOVERY_SOURCE_LINE_NUMBER,
      AuditReportConstants.ELEMENT_DISCOVERY_VARIABLE_ID,
      AuditReportConstants.ELEMENT_DISCOVERY_NODE_TYPE
    )
  }

  def updateWorkbookResultsToGetUniqueSourcePerFile(workbookResult: List[List[String]]): List[List[String]] = {
    try {
      // Group entries by filename
      val groupedByFilename = workbookResult.groupBy(entry => entry(1))

      // For each group, filter unique items based on name
      val filteredResults = groupedByFilename.values.flatMap { entries =>
        val uniqueByName = entries.groupBy(entry => entry(3)).values.map(_.head)
        uniqueByName
      }.toList
      // Update MemberType if it contains "\n"
      val updatedMemberTypeResults = filteredResults.map(entry => {
        val row = entry.toArray
        if (row(4).contains("\n")) {
          row(4) = "NA"
        }
        row.toList
      })
      println(s"${Calendar.getInstance().getTime} - No of sources after dedup: ${updatedMemberTypeResults.size}")
      updatedMemberTypeResults
    } catch {
      case e: Exception =>
        logger.debug(s"An error occurred while filtering the workbook result: ${e.getMessage}")
        workbookResult
    }
  }
}

object DEDSourceDiscovery {
  private val logger = LoggerFactory.getLogger(getClass)

  case class DEDSourceAudit(
    className: String,
    fileName: String,
    memberName: String,
    memberType: String,
    tagged: Boolean,
    sourceRuleId: String,
    variableDeclarationLineNumber: String,
    memberId: String,
    nodeType: String
  )

  implicit val DEDSourceAuditModelDecoder: Decoder[DEDSourceAudit] =
    deriveDecoder[DEDSourceAudit]
  implicit val DEDSourceAuditEncoder: Encoder[DEDSourceAudit] =
    deriveEncoder[DEDSourceAudit]

  def processDEDSourceDiscoveryForIdentifierAndFieldIdentfier(xtocpg: Try[Cpg], lang: Language): List[List[String]] = {
    var workbookResult = new ListBuffer[List[String]]()
    try {
      // Adding Identifiers
      workbookResult = DEDSourceDiscoveryUtils.getIdentifiers(xtocpg, workbookResult, lang)

      // Adding FieldIdentifiers
      if (lang != Language.GO) {
        workbookResult = DEDSourceDiscoveryUtils.getFieldAccessIdentifier(xtocpg, workbookResult, lang)
      }
    } catch {
      case ex: Exception =>
        logger.debug("Failed to process Data Element Discovery report", ex)
    }

    DEDSourceDiscoveryUtils.updateWorkbookResultsToGetUniqueSourcePerFile(workbookResult.toList)
  }

  def processDEDSourceDiscovery(xtocpg: Try[Cpg], lang: Language): List[List[String]] = {
    var workbookResult = new ListBuffer[List[String]]()

    // Construct the excel sheet and fill the data
    try {
      // Adding Members
      workbookResult = DEDSourceDiscoveryUtils.getNewMembers(xtocpg, workbookResult, lang)
      println(s"${Calendar.getInstance().getTime} - Member size: ${workbookResult.size}")

      if (lang != Language.JAVA && lang != Language.KOTLIN) {
        // Adding MethodParameters
        workbookResult = DEDSourceDiscoveryUtils.getNewMethodParametersFromTypes(xtocpg, workbookResult, lang)
        println(s"${Calendar.getInstance().getTime} - Method Param size: ${workbookResult.size}")
      }

      // Adding Identifiers
      workbookResult = DEDSourceDiscoveryUtils.getIdentifiers(xtocpg, workbookResult, lang)
      println(s"${Calendar.getInstance().getTime} - Identifier size: ${workbookResult.size}")

      // Adding FieldIdentifiers
      if (lang != Language.GO && lang != Language.JAVA && lang != Language.KOTLIN) {
        workbookResult = DEDSourceDiscoveryUtils.getFieldAccessIdentifier(xtocpg, workbookResult, lang)
        println(s"${Calendar.getInstance().getTime} - Field Identifier size: ${workbookResult.size}")
      }

    } catch {
      case ex: Exception =>
        println(s"Failed to process Data Element Discovery report ${ex}")
    }
    println(s"${Calendar.getInstance().getTime} - No of sources: ${workbookResult.size}")
    DEDSourceDiscoveryUtils.getHeaderList() +: DEDSourceDiscoveryUtils
      .updateWorkbookResultsToGetUniqueSourcePerFile(workbookResult.toList)
  }

  def createDEDSourceReportJson(dedSourceAudit: List[List[String]], repoPath: String) = {
    try {
      val auditDataList = new ListBuffer[DEDSourceAudit]()

      for (item <- dedSourceAudit.drop(1)) {
        auditDataList += DEDSourceAudit(
          eliminateEmptyCellValueIfExist(item(0)),
          eliminateEmptyCellValueIfExist(item(1)),
          eliminateEmptyCellValueIfExist(item(3)),
          eliminateEmptyCellValueIfExist(if (item(4).startsWith("{")) "NA" else item(4)),
          item(5) == "true",
          eliminateEmptyCellValueIfExist(item(6)),

          // Line number
          if (item.size >= 11) eliminateEmptyCellValueIfExist(item(10))
          else AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,

          // variable identifier
          if (item.size >= 12) eliminateEmptyCellValueIfExist(item(11))
          else AuditReportConstants.AUDIT_EMPTY_CELL_VALUE,

          // variable type
          if (item.size >= 13) eliminateEmptyCellValueIfExist(item(12)) else AuditReportConstants.AUDIT_EMPTY_CELL_VALUE
        )
      }
      JSONExporter.dedSourceDiscoveryAuditFileExport(
        AuditReportConstants.DED_SOURCE_FILE_NAME,
        repoPath,
        auditDataList.toList
      )
    } catch {
      case ex: Exception =>
        println(f"Failed to create Data Element Discovery json ${ex}")
    }

  }

  def generateReport(
    xtocpg: Try[Cpg],
    repoPath: String,
    statsRecorder: StatsRecorder,
    lang: Language = Language.JAVA
  ): Either[String, Unit] = {
    try {
      statsRecorder.initiateNewStage("DED Source Report generation ...")
      val dedSourceDiscoveryData = lang match {
        case Language.JAVASCRIPT | Language.PHP | Language.CSHARP | Language.PYTHON | Language.GO | Language.JAVA |
            Language.KOTLIN =>
          processDEDSourceDiscovery(xtocpg, lang)
        case Language.RUBY =>
          processDEDSourceDiscoveryForIdentifierAndFieldIdentfier(xtocpg, lang)
        case _ =>
          List[List[String]]()
      }
      createDEDSourceReportJson(dedSourceDiscoveryData, repoPath)
      statsRecorder.endLastStage()
      Right(())
    } catch {
      case ex: Exception =>
        println("Failed to export DED source Report")
        logger.debug("Failed to export DED source Report", ex)
        println(ex.printStackTrace())
        Left(ex.toString)
    }
  }
}

