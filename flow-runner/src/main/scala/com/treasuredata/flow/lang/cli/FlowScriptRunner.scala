package com.treasuredata.flow.lang.cli

import com.treasuredata.flow.lang.FlowLangException
import com.treasuredata.flow.lang.compiler.{
  CompilationUnit,
  CompileResult,
  Compiler,
  CompilerOptions
}
import com.treasuredata.flow.lang.runner.*
import org.jline.terminal.Terminal
import wvlet.airframe.control.{Control, Shell}
import wvlet.log.LogSupport

import java.io.{BufferedWriter, FilterOutputStream, OutputStreamWriter}

case class FlowScriptRunnerConfig(
    workingFolder: String = ".",
    interactive: Boolean,
    resultLimit: Int = 40,
    maxColWidth: Int = 150,
    catalog: Option[String],
    schema: Option[String]
)

case class LastOutput(line: String, output: String, result: QueryResult) {}

class FlowScriptRunner(config: FlowScriptRunnerConfig, queryExecutor: QueryExecutor)
    extends AutoCloseable
    with LogSupport:
  private var units: List[CompilationUnit] = Nil

  private var resultRowLimits: Int   = config.resultLimit
  private var resultMaxColWidth: Int = config.maxColWidth

  def setResultRowLimit(limit: Int): Unit = resultRowLimits = limit
  def setMaxColWidth(size: Int): Unit     = resultMaxColWidth = size

  override def close(): Unit = queryExecutor.close()

  private val compiler =
    val c = Compiler(
      CompilerOptions(
        sourceFolders = List(config.workingFolder),
        workingFolder = config.workingFolder,
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

    c

  def runStatement(line: String, terminal: Terminal): LastOutput =
    val newUnit = CompilationUnit.fromString(line)
    units = newUnit :: units

    try
      val compileResult = compiler.compileSingle(contextUnit = newUnit)
      val ctx           = compileResult.context.global.getContextOf(newUnit)
      val queryResult   = queryExecutor.execute(newUnit, ctx, limit = resultRowLimits)
      trace(s"ctx: ${ctx.hashCode()} ${ctx.compilationUnit.knownSymbols}")

      val str = queryResult.toPrettyBox(maxColWidth = resultMaxColWidth)
      if str.nonEmpty then
        val resultMaxWidth = str.split("\n").map(_.size).max
        if !config.interactive || resultMaxWidth <= terminal.getWidth then
          println(
            queryResult
              .toPrettyBox(maxWidth = Some(terminal.getWidth), maxColWidth = resultMaxColWidth)
          )
        else
          // Launch less command to enable scrolling of query results in the terminal
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
          // Blocking
          proc.waitFor()

      LastOutput(line, str, queryResult)
    catch
      case e: FlowLangException if e.statusCode.isUserError =>
        error(s"${e.getMessage}")
        LastOutput(line, e.getMessage, QueryResult.empty)
    end try

  end runStatement

end FlowScriptRunner