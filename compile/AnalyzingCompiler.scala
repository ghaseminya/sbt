package xsbt

	import sbt.ComponentManager
	import xsbti.{AnalysisCallback, Logger => xLogger}
	import java.io.File
	import java.net.{URL, URLClassLoader}

/** Interface to the Scala compiler that uses the dependency analysis plugin.  This class uses the Scala library and compiler
* provided by scalaInstance.  This class requires a ComponentManager in order to obtain the interface code to scalac and
* the analysis plugin.  Because these call Scala code for a different Scala version than the one used for this class, they must
* be compiled for the version of Scala being used.*/
class AnalyzingCompiler(val scalaInstance: ScalaInstance, val manager: ComponentManager, val cp: ClasspathOptions, log: CompileLogger) extends NotNull
{
	def this(scalaInstance: ScalaInstance, manager: ComponentManager, log: CompileLogger) = this(scalaInstance, manager, ClasspathOptions.auto, log)
	def apply(sources: Seq[File], classpath: Seq[File], outputDirectory: File, options: Seq[String], callback: AnalysisCallback, maximumErrors: Int, log: CompileLogger)
	{
		val arguments = (new CompilerArguments(scalaInstance, cp))(sources, classpath, outputDirectory, options)
		compile(arguments, callback, maximumErrors, log)
	}
	def compile(arguments: Seq[String], callback: AnalysisCallback, maximumErrors: Int, log: CompileLogger)
	{
		call("xsbt.CompilerInterface", log)(
			classOf[Array[String]], classOf[AnalysisCallback], classOf[Int], classOf[xLogger] ) (
			arguments.toArray[String] : Array[String], callback, maximumErrors: java.lang.Integer, log )
	}
	def doc(sources: Seq[File], classpath: Seq[File], outputDirectory: File, options: Seq[String], maximumErrors: Int, log: CompileLogger): Unit =
	{
		val arguments = (new CompilerArguments(scalaInstance, cp))(sources, classpath, outputDirectory, options)
		call("xsbt.ScaladocInterface", log) (classOf[Array[String]], classOf[Int], classOf[xLogger]) (arguments.toArray[String] : Array[String], maximumErrors: java.lang.Integer, log)
	}
	def console(classpath: Seq[File], options: Seq[String], initialCommands: String, log: CompileLogger): Unit =
	{
		val arguments = new CompilerArguments(scalaInstance, cp)
		val classpathString = CompilerArguments.absString(arguments.finishClasspath(classpath))
		val bootClasspath = if(cp.autoBoot) arguments.createBootClasspath else ""
		call("xsbt.ConsoleInterface", log) (classOf[Array[String]], classOf[String], classOf[String], classOf[String], classOf[xLogger]) (options.toArray[String]: Array[String], bootClasspath, classpathString, initialCommands, log)
	}
	def force(log: CompileLogger): Unit = getInterfaceJar(log)
	private def call(interfaceClassName: String, log: CompileLogger)(argTypes: Class[_]*)(args: AnyRef*)
	{
		val interfaceClass = getInterfaceClass(interfaceClassName, log)
		val interface = interfaceClass.newInstance.asInstanceOf[AnyRef]
		val method = interfaceClass.getMethod("run", argTypes : _*)
		try { method.invoke(interface, args: _*) }
		catch { case e: java.lang.reflect.InvocationTargetException => throw e.getCause }
	}
	private[this] def loader =
	{
		val interfaceJar = getInterfaceJar(log)
		val dual = createDualLoader(scalaInstance.loader, getClass.getClassLoader) // this goes to scalaLoader for scala classes and sbtLoader for xsbti classes
		new URLClassLoader(Array(interfaceJar.toURI.toURL), dual)
	}
	private def getInterfaceClass(name: String, log: CompileLogger) = Class.forName(name, true, loader)
	private def getInterfaceJar(log: CompileLogger) =
	{
		// this is the instance used to compile the interface component
		val componentCompiler = newComponentCompiler(log)
		log.debug("Getting " + ComponentCompiler.compilerInterfaceID + " from component compiler for Scala " + scalaInstance.version)
		componentCompiler(ComponentCompiler.compilerInterfaceID)
	}
	def newComponentCompiler(log: CompileLogger) = new ComponentCompiler(new RawCompiler(scalaInstance, ClasspathOptions.auto, log), manager)
	protected def createDualLoader(scalaLoader: ClassLoader, sbtLoader: ClassLoader): ClassLoader =
	{
		val xsbtiFilter = (name: String) => name.startsWith("xsbti.")
		val notXsbtiFilter = (name: String) => !xsbtiFilter(name)
		new DualLoader(scalaLoader, notXsbtiFilter, x => true, sbtLoader, xsbtiFilter, x => false)
	}
	override def toString = "Analyzing compiler (Scala " + scalaInstance.actualVersion + ")"
}