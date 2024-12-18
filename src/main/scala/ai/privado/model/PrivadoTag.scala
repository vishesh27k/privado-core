/*
 * This file is part of Privado OSS.
 *
 * Privado is an open source static code analysis tool to discover data flows in the code.
 * Copyright (C) 2022 Privado, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * For more information, contact support@privado.ai
 */

package ai.privado.model

import ai.privado.entrypoint.ScanProcessor.logger
import ai.privado.model
import ai.privado.model.Language.{JAVA, Language}
import io.shiftleft.codepropertygraph.generated.Languages
import org.slf4j.LoggerFactory

import scala.sys.exit
import scala.util.{Failure, Success, Try}

object InternalTag extends Enumeration {

  type InternalTag = Value

  val VARIABLE_REGEX_LITERAL                   = Value("VARIABLE_REGEX_LITERAL")
  val VARIABLE_REGEX_IDENTIFIER                = Value("VARIABLE_REGEX_IDENTIFIER")
  val VARIABLE_REGEX_MEMBER                    = Value("VARIABLE_REGEX_MEMBER")
  val OBJECT_OF_SENSITIVE_CLASS_BY_MEMBER_NAME = Value("OBJECT_OF_SENSITIVE_CLASS_BY_MEMBER_NAME")
  val OBJECT_OF_SENSITIVE_CLASS_BY_INHERITANCE = Value("OBJECT_OF_SENSITIVE_CLASS_BY_INHERITANCE")
  val OBJECT_OF_SENSITIVE_CLASS_BY_MEMBER_TYPE = Value("OBJECT_OF_SENSITIVE_CLASS_BY_MEMBER_TYPE")
  val SENSITIVE_FIELD_ACCESS                   = Value("SENSITIVE_FIELD_ACCESS")
  val COLLECTION_METHOD_ENDPOINT               = Value("COLLECTION_METHOD_ENDPOINT")
  val SENSITIVE_METHOD_RETURN                  = Value("SENSITIVE_METHOD_RETURN")
  val INDEX_ACCESS_CALL                        = Value("INDEX_ACCESS_CALL")
  val INSENSITIVE_METHOD_RETURN                = Value("INSENSITIVE_METHOD_RETURN")
  val INSENSITIVE_FIELD_ACCESS                 = Value("INSENSITIVE_FIELD_ACCESS")
  val INSENSITIVE_SETTER                       = Value("INSENSITIVE_SETTER")
  val SENSITIVE_SETTER                         = Value("SENSITIVE_SETTER")
  val PROBABLE_ASSET                           = Value("PROBABLE_ASSET")
  val SOURCE_PROPERTY                          = Value("SOURCE_PROPERTY")
  val TAGGED_BY_DED                            = Value("TAGGED_BY_DED")
  val TAGGING_DISABLED_BY_DED                  = Value("TAGGING_DISABLED_BY_DED")

  // API Tags
  val API_SINK_MARKED = Value("API_SINK_MARKED")
  val API_URL_MARKED  = Value("API_URL_MARKED")

  // Apache Flink Tag
  val FLINK_INITIALISATION_LOCAL_NODE = Value("FLINK_INITIALISATION_LOCAL_NODE")

  // Ruby Mongo Repository
  val RUBY_MONGO_CLASS_CLIENT    = Value("RUBY_MONGO_CLASS_CLIENT")
  val RUBY_MONGO_COLUMN          = Value("RUBY_MONGO_COLUMN")
  val RUBY_MONGO_COLUMN_DATATYPE = Value("RUBY_MONGO_COLUMN_DATATYPE")

  lazy val valuesAsString = InternalTag.values.map(value => value.toString())

}

object NodeType extends Enumeration {

  type NodeType = Value

  val API     = Value("api")
  val REGULAR = Value("REGULAR")
  val UNKNOWN = Value("Unknown")

  def withNameWithDefault(name: String): Value = {
    try {
      withName(name)
    } catch {
      case _: Throwable => this.REGULAR
    }
  }
}

object CatLevelOne extends Enumeration {
  type CatLevelOne = CatLevelOneIn

  case class CatLevelOneIn(name: String, label: String) extends Val(name)

  val SOURCES     = CatLevelOneIn("sources", "Data Element")
  val SINKS       = CatLevelOneIn("sinks", "Sinks")
  val COLLECTIONS = CatLevelOneIn("collections", "Collections")
  val POLICIES    = CatLevelOneIn("policies", "Policies")
  val THREATS     = CatLevelOneIn("threats", "Threats")
  val INFERENCES  = CatLevelOneIn("inferences", "Inferences")
  val DED         = CatLevelOneIn("ded", "Ded")
  val UNKNOWN     = CatLevelOneIn("unknown", "Unknown")

  // internal CatLevelOne
  val DERIVED_SOURCES = CatLevelOneIn("DerivedSources", "Data Element")

  def withNameWithDefault(name: String): CatLevelOneIn = {
    try {
      withName(name).asInstanceOf[CatLevelOne.CatLevelOneIn]
    } catch {
      case _: Throwable => this.UNKNOWN
    }
  }
}

object Language extends Enumeration {
  private val logger = LoggerFactory.getLogger(this.getClass)
  type Language = Value

  val JAVA       = Value("java")
  val JAVASCRIPT = Value("javascript")
  val PYTHON     = Value("python")
  val RUBY       = Value("ruby")
  val KOTLIN     = Value("kotlin")
  val GO         = Value("go")
  val PHP        = Value("php")
  val CSHARP     = Value("csharp")
  val DEFAULT    = Value("default")
  val UNKNOWN    = Value("unknown")

  def withJoernLangName(name: Try[Option[String]]): Value = {
    name match {
      case Success(guessedLang) =>
        guessedLang match {
          case Some(language) if language == Languages.JAVASRC || language == Languages.JAVA     => JAVA
          case Some(language) if language == Languages.JSSRC || language == Languages.JAVASCRIPT => JAVASCRIPT
          case Some(language) if language == Languages.PYTHONSRC || language == Languages.PYTHON => PYTHON
          case Some(language) if language == Languages.RUBYSRC                                   => RUBY
          case Some(language) if language == Languages.GOLANG                                    => GO
          case Some(language) if language == Languages.KOTLIN                                    => KOTLIN
          case Some(language) if language == Languages.CSHARPSRC || language == Languages.CSHARP => CSHARP
          case Some(language) if language == Languages.PHP                                       => PHP
          case _                                                                                 => UNKNOWN
        }
      case Failure(exc) =>
        logger.debug("Error while guessing language", exc)
        println(s"Error Occurred: ${exc.getMessage}")
        exit(1)
    }

  }

  def withNameWithDefault(name: String): Value = {
    try {
      withName(name)
    } catch {
      case _: Throwable => this.UNKNOWN
    }
  }
}

object LanguageFileExt extends Enumeration {
  type LanguageFileExt = Value

  private val JAVA_EXT       = Value(".java")
  private val JAVASCRIPT_EXT = Value(".js")
  private val PYTHON_EXT     = Value(".py")
  private val RUBY_EXT       = Value(".rb")
  private val KOTLIN_EXT     = Value(".kt")
  private val GO_EXT         = Value(".go")
  private val DEFAULT_EXT    = Value(".default")
  private val UNKNOWN_EXT    = Value(".unknown")

  def withLanguage(language: Language): String = {
    language match {
      case Language.JAVA       => JAVA_EXT.toString
      case Language.JAVASCRIPT => JAVASCRIPT_EXT.toString
      case Language.PYTHON     => PYTHON_EXT.toString
      case Language.RUBY       => RUBY_EXT.toString
      case Language.KOTLIN     => KOTLIN_EXT.toString
      case Language.GO         => GO_EXT.toString
      case Language.DEFAULT    => DEFAULT_EXT.toString
      case Language.UNKNOWN    => UNKNOWN_EXT.toString
    }
  }

  def withNameWithDefault(name: String): Value = {
    try {
      withName(name)
    } catch {
      case _: Throwable => this.UNKNOWN_EXT
    }
  }
}

object PolicyAction extends Enumeration {
  type PolicyAction = Value

  val ALLOW = Value("allow")
  val DENY  = Value("deny")

  def withNameDefaultHandler(name: String): Value = {
    if (name != null)
      try {
        withName(name.toLowerCase())
      } catch {
        case _: Throwable => null
      }
    else
      null
  }

}
object PolicyThreatType extends Enumeration {
  type PolicyThreatType = Value

  val THREAT     = Value("threat")
  val COMPLIANCE = Value("compliance")

  def withNameDefaultHandler(name: String): Value = {
    if (name != null)
      try {
        withName(name.toLowerCase())
      } catch {
        case _: Throwable => null
      }
    else
      null
  }
}

object ConfigRuleType extends Enumeration {
  type ConfigRuleType = Value

  val EXCLUSIONS     = Value("exclusions")
  val SEMANTICS      = Value("semantics")
  val SINK_SKIP_LIST = Value("sinkSkipList")
  val SYSTEM_CONFIG  = Value("systemConfig")
  val AUDIT_CONFIG   = Value("auditConfig")

  def withNameDefaultHandler(name: String): Value = {
    if (name != null)
      try {
        withName(name)
      } catch {
        case _: Throwable => null
      }
    else
      null
  }
}

object FilterProperty extends Enumeration {
  type FilterProperty = Value
  val METHOD_FULL_NAME: model.FilterProperty.Value = Value("method_full_name")
  val CODE: model.FilterProperty.Value             = Value("code")

  // For Inference API Endpoint mapping
  val METHOD_FULL_NAME_WITH_LITERAL: model.FilterProperty.Value       = Value("method_full_name_with_literal")
  val METHOD_FULL_NAME_WITH_PROPERTY_NAME: model.FilterProperty.Value = Value("method_full_name_with_property_name")
  val ENDPOINT_DOMAIN_WITH_LITERAL: model.FilterProperty.Value        = Value("endpoint_domain_with_literal")
  val ENDPOINT_DOMAIN_WITH_PROPERTY_NAME: model.FilterProperty.Value  = Value("endpoint_domain_with_property_name")

  def withNameWithDefault(name: String): Value = {
    try {
      withName(name)
    } catch {
      case _: Throwable => this.METHOD_FULL_NAME
    }
  }
}
