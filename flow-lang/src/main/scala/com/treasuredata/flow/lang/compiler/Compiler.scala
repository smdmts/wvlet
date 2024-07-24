package com.treasuredata.flow.lang.compiler

import com.treasuredata.flow.lang.compiler.Compiler.presetLibraryPaths
import com.treasuredata.flow.lang.compiler.analyzer.{
  RemoveQueryOnlyUnits,
  SymbolLabeler,
  TypeResolver
}
import com.treasuredata.flow.lang.compiler.parser.{FlowParser, ParserPhase}
import com.treasuredata.flow.lang.compiler.transform.Incrementalize
import com.treasuredata.flow.lang.model.plan.LogicalPlan
import wvlet.log.LogSupport

object Compiler:
  def default: Compiler = Compiler(phases = allPhases)

  /**
    * Phases for text-based analysis of the source code
    */
  def analysisPhases: List[Phase] = List(
    ParserPhase, // Parse *.flow files and create untyped plans
    SymbolLabeler, // Assign unique Symbol to each LogicalPlan and Expression nodes, a and assign a lazy DataType
    new RemoveQueryOnlyUnits, // Check if any compilation units are unused
    TypeResolver              // Assign a concrete DataType to each LogicalPlan and Expression nodes
  )

  /**
    * Phases for transforming the logical plan trees
    */
  def transformPhases: List[Phase] = List(
    Incrementalize // Create an incrementalized plan for a subscription
  )

  /**
    * Generate SQL, Scala, or other code from the logical plan
    * @return
    */
  def codeGenPhases: List[Phase] = List()

  def allPhases: List[List[Phase]] = List(analysisPhases, transformPhases, codeGenPhases)

  def presetLibraryPaths: List[String] = List("flow-lang/src/main/resources/flow-stdlib")

end Compiler

class Compiler(phases: List[List[Phase]] = Compiler.allPhases) extends LogSupport:
  /**
    * @param sourceFolder
    *   A folder containing src and data folders
    * @return
    */
  def compile(sourceFolder: String): CompileResult = compile(List(sourceFolder), sourceFolder, None)

  def compile(
      sourceFolders: List[String],
      contextFolder: String,
      contextFile: Option[String]
  ): CompileResult =
    val sourcePaths = Compiler.presetLibraryPaths ++ sourceFolders
    var units: List[CompilationUnit] = sourcePaths.flatMap { path =>
      val srcPath = s"${path}/src"
      CompilationUnit.fromPath(srcPath, isPreset = presetLibraryPaths.contains(path))
    }

    val global = GlobalContext(
      contextFile = contextFile,
      sourceFolders = sourceFolders,
      workingFolder = contextFolder
    )
    val rootContext = global.getContextOf(unit = CompilationUnit.empty, scope = Scope.newScope(0))
    // Need to initialize the global context before running the analysis phases
    global.init(using rootContext)

    for
      phaseGroup <- phases
      phase      <- phaseGroup
    do
      debug(s"Running phase ${phase.name}")
      units = phase.runOn(units, rootContext)

    CompileResult(units, this, rootContext)

end Compiler

case class CompileResult(units: List[CompilationUnit], compiler: Compiler, context: Context):
  def typedPlans: List[LogicalPlan] = units.map(_.resolvedPlan).filter(_.nonEmpty)

  /**
    * Extract compilation results for a specific file name
    * @param fileName
    * @return
    */
  def inFile(fileName: String): Option[CompilationUnit] =
    units.filter(_.sourceFile.fileName == fileName).headOption
