/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.lang.runner

import org.jline.terminal.Terminal
import wvlet.airframe.control.{Control, Shell}
import wvlet.lang.api.WvletLangException
import wvlet.lang.api.v1.query.{QueryRequest, QuerySelection}
import wvlet.lang.compiler.*
import wvlet.lang.runner.*
import wvlet.log.{LogRotationHandler, LogSupport, Logger}

import java.io.{BufferedWriter, FilterOutputStream, OutputStreamWriter}
import java.sql.SQLException
import scala.util.control.NonFatal

case class WvletScriptRunnerConfig(
    // If true, the query result will be displayed with LESS command
    interactive: Boolean,
    resultLimit: Int = 40,
    maxColWidth: Int = 150,
    catalog: Option[String],
    schema: Option[String]
)

case class LastOutput(
    line: String,
    output: String,
    result: QueryResult,
    error: Option[Throwable] = None
):
  def hasError: Boolean = error.isDefined

class WvletScriptRunner(
    workEnv: WorkEnv,
    config: WvletScriptRunnerConfig,
    queryExecutor: QueryExecutor,
    threadManager: ThreadManager
) extends AutoCloseable
    with LogSupport:

  private var units: List[CompilationUnit] = Nil

  private var resultRowLimits: Int   = config.resultLimit
  private var resultMaxColWidth: Int = config.maxColWidth

  def getResultRowLimit: Int              = resultRowLimits
  def setResultRowLimit(limit: Int): Unit = resultRowLimits = limit
  def setMaxColWidth(size: Int): Unit     = resultMaxColWidth = size

  override def close(): Unit = queryExecutor.close()

  private val compiler =
    val c = Compiler(
      CompilerOptions(
        sourceFolders = List(workEnv.path),
        workEnv = workEnv,
        catalog = config.catalog,
        schema = config.schema
      )
    )

    // Set the default catalog given in the configuration
    config
      .catalog
      .foreach { catalog =>
        c.setDefaultCatalog(
          queryExecutor.getDBConnector.getCatalog(catalog, config.schema.getOrElse("main"))
        )
      }
    config
      .schema
      .foreach { schema =>
        c.setDefaultSchema(schema)
      }

    // Pre-compile files in the source paths
    threadManager.runBackgroundTask { () =>
      c.compileSourcePaths(None)
    }
    c

  def runStatement(request: QueryRequest): QueryResult =
    val newUnit = CompilationUnit.fromString(request.query)
    units = newUnit :: units

    try
      val compileResult = compiler.compileSingleUnit(contextUnit = newUnit)
      val ctx = compileResult.context.global.getContextOf(newUnit).withDebugRun(request.isDebugRun)
      val queryResult = queryExecutor
        .setRowLimit(resultRowLimits)
        .executeSelectedStatement(newUnit, request.querySelection, request.nodeLocation, ctx)
      trace(s"ctx: ${ctx.hashCode()} ${ctx.compilationUnit.knownSymbols}")

      queryResult
    catch
      case NonFatal(e) =>
        ErrorResult(e)
    end try

  end runStatement

  def displayOutput(query: String, queryResult: QueryResult, terminal: Terminal): LastOutput =
    def print: LastOutput =
      val str            = queryResult.toPrettyBox(maxColWidth = resultMaxColWidth)
      val resultMaxWidth = str.split("\n").map(_.size).max
      if !config.interactive || resultMaxWidth <= terminal.getWidth then
        // The result fits in the terminal width
        val output = queryResult
          .toPrettyBox(maxWidth = Some(terminal.getWidth), maxColWidth = resultMaxColWidth)
        if output.trim.nonEmpty then
          println(output)
      else
        // Launch less command to enable scrolling of query results in the terminal
        // TODO Use jline3's internal less
        val proc = ProcessUtil.launchInteractiveProcess("less", "-FXRSn")
        val out =
          new BufferedWriter(
            new OutputStreamWriter(
              // Need to use a FilterOutputStream to accept keyboard events for less command along with the query result string
              new FilterOutputStream(proc.getOutputStream())
            )
          )
        out.write(str)
        out.flush()
        out.close()
        // Blocks until the process is finished
        proc.waitFor()

      LastOutput(query, str, queryResult)
    end print

    queryResult.getError match
      case None =>
        print
      case Some(e) =>
        workEnv.errorLogger.error(e)
        e match
          case e: WvletLangException if e.statusCode.isUserError =>
            error(e.getMessage)
          case e: SQLException =>
            error(e.getMessage)
          case _ =>
            error(e.getMessage)
        LastOutput(query, e.getMessage, QueryResult.empty, error = Some(e))

  end displayOutput

end WvletScriptRunner
