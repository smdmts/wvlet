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
package wvlet.lang.compiler

import wvlet.lang.catalog.Catalog
import wvlet.lang.compiler.Compiler.presetLibraries
import wvlet.lang.compiler.analyzer.{RemoveUnusedQueries, SymbolLabeler, TypeResolver}
import wvlet.lang.compiler.parser.{WvletParser, ParserPhase}
import wvlet.lang.compiler.transform.Incrementalize
import wvlet.lang.model.plan.LogicalPlan
import wvlet.log.LogSupport

object Compiler extends LogSupport:

  def default(sourcePath: String): Compiler =
    new Compiler(CompilerOptions(sourceFolders = List(sourcePath), workingFolder = sourcePath))

  /**
    * Phases for text-based analysis of the source code
    */
  def analysisPhases: List[Phase] = List(
    ParserPhase, // Parse *.wv files and create untyped plans
    SymbolLabeler, // Assign unique Symbol to each LogicalPlan and Expression nodes, a and assign a lazy DataType
    RemoveUnusedQueries(), // Exclude unused compilation units (e.g., out of scope queries) from the following phases
    TypeResolver // Assign a concrete DataType to each LogicalPlan and Expression nodes
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

  lazy val presetLibraries: List[CompilationUnit] = CompilationUnit
    .fromResourcePath("/module/standard", isPreset = true)

end Compiler

case class CompilerOptions(
    phases: List[List[Phase]] = Compiler.allPhases,
    sourceFolders: List[String] = List("."),
    workingFolder: String = ".",
    // Context database catalog
    catalog: Option[String] = None,
    // context database schema
    schema: Option[String] = None
)

class Compiler(compilerOptions: CompilerOptions) extends LogSupport:

  private lazy val globalContext = newGlobalContext
  // Compilation units in the given source folders (except preset-libraries)
  lazy val localCompilationUnits    = listLocalCompilationUnits(compilerOptions.sourceFolders)
  def compilationUnitsInSourcePaths = presetLibraries ++ localCompilationUnits

  def setDefaultCatalog(catalog: Catalog): Unit = globalContext.defaultCatalog = catalog
  def setDefaultSchema(schema: String): Unit    = globalContext.defaultSchema = schema

  private def newGlobalContext: GlobalContext =
    val global      = GlobalContext(compilerOptions)
    val rootContext = global.getContextOf(unit = CompilationUnit.empty, scope = Scope.newScope(0))
    // Need to initialize the global context before running the analysis phases
    global.init(using rootContext)
    global

  private def listLocalCompilationUnits(sourceFolders: List[String]): List[CompilationUnit] =
    val sourcePaths = sourceFolders
    val units = sourcePaths.flatMap { path =>
      CompilationUnit.fromPath(path)
    }
    units

  /**
    * @param sourceFolder
    *   A folder containing src and data folders
    * @return
    */
  def compile(): CompileResult = compileSourcePaths(None)

  /**
    * Compile all files in the source paths
    * @param contextFile
    * @return
    */
  def compileSourcePaths(contextFile: Option[String]): CompileResult =
    val contextUnit: Option[CompilationUnit] = contextFile
      .flatMap(f => compilationUnitsInSourcePaths.find(_.sourceFile.fileName == f))

    compileInternal(compilationUnitsInSourcePaths, contextUnit = contextUnit)

  /**
    * Compile only a single file without reading any other files. This method is useful for
    * incremental compilation or running test suites
    * @param contextUnit
    * @return
    */
  def compileSingleUnit(contextUnit: CompilationUnit): CompileResult =
    val units: List[CompilationUnit] = compilationUnitsInSourcePaths :+ contextUnit
    compileInternal(units, contextUnit = Some(contextUnit))

  def compileInternal(
      units: List[CompilationUnit],
      contextUnit: Option[CompilationUnit]
  ): CompileResult =
    globalContext.setContextUnit(contextUnit)
    val rootContext  = globalContext.getRootContext
    var refinedUnits = units
    for
      phaseGroup <- compilerOptions.phases
      phase      <- phaseGroup
    do
      debug(s"Running phase ${phase.name}")
      refinedUnits = phase.runOn(refinedUnits, rootContext)

    units
      .filter(_.isFailed)
      .foreach { failedUnit =>
        if contextUnit.exists(_ eq failedUnit) then
          throw failedUnit.lastError.get
      }

    val result = CompileResult(refinedUnits, this, rootContext)
    result

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