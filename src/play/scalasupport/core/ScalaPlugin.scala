package play.scalasupport.core

import play._
import play.test._
import play.vfs.{VirtualFile => VFile}
import play.exceptions._
import play.classloading.ApplicationClasses.ApplicationClass

import scala.tools.nsc._
import scala.tools.nsc.reporters._
import scala.tools.nsc.util._
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer
import scala.collection.mutable.HashMap

import scala.tools.nsc.io._

import java.util.{List => JList}

import org.scalatest.Suite
import org.scalatest.tools.ScalaTestRunner

/**
* this play plugin is responsible for compiling both scala and java files.
* It is using the same compilation technique as fsc
* 
*/
class ScalaPlugin extends PlayPlugin {
    
    var lastHash = 0
    
    /*
    * Scanning both java and scala sources for compilation
    */
    def scanSources = {
        val sources = ListBuffer[VFile]()
        val hash = new StringBuffer
        def scan(path: VFile): Unit = {
            path match {
                case _ if path.isDirectory => path.list foreach scan
                case _ if (path.getName().endsWith(".scala") || path.getName().endsWith(".java")) && !path.getName().startsWith(".") => sources add path; hash.append(path.relativePath)
                case _ => 
            }
        }
        Play.javaPath foreach scan
        (sources, hash.toString.hashCode)
    }
    
    /**
    * try to detect source changes
    **/
    override def detectChange = {
        if(lastHash != scanSources._2) {
            throw new Exception("Path change")
        }
    }

    /**
    * compile all classes
    * @classes classes to be compiled
    * @return return compiled classes
    **/
    override def compileAll(classes: JList[ApplicationClass]) = {
        val (sources, hash) = scanSources
        lastHash = hash
        play.Logger.trace("SCALA compileAll")
        if(Play.usePrecompiled) {
            new java.util.ArrayList[ApplicationClass]()            
        } else {
            classes.addAll(compile(sources))
        }  
    }

    /**
    * inject ScalaTestRunner into play's test framework
    * @testClass a class under testing 
    */
    override def runTest(testClass: Class[BaseTest]) = {
        testClass match {
            case suite if classOf[Suite] isAssignableFrom testClass => ScalaTestRunner run suite.asInstanceOf[Class[Suite]]
            case _ => null
        }
    }

    /**
    * compile a class if a change was made.
    * @modified classes that were modified
    */
    override def onClassesChange(modified: JList[ApplicationClass]) {
        val sources = new java.util.ArrayList[VFile]
        modified foreach { cl: ApplicationClass =>
            var source = cl.javaFile
            if(!(sources contains source)) {
                sources add source
            }
        }
        compile(sources)
    }

    // Compiler
    private var compiler: ScalaCompiler = _

    /**
    * compiles all given source files
    * @sources files to be compiled
    * @return List of compiled classes
    */
    def compile(sources: JList[VFile]) = {
        if(compiler == null) {
            compiler = new ScalaCompiler
        }
        compiler compile sources.toList
    }


private[this] class ScalaCompiler {

        private val reporter = new Reporter() {

            override def info0(position: Position, msg: String, severity: Severity, force: Boolean) = {
                severity match {
                    case ERROR if position.isDefined => throw new CompilationException(realFiles.get(position.source.file.toString()).get, msg, position.line)
                    case ERROR => throw new CompilationException(msg);
                    case WARNING if position.isDefined => Logger.warn(msg + ", at line " + position.line + " of "+position.source)
                    case WARNING => Logger.warn(msg)
                    case INFO if position.isDefined => Logger.info(msg + ", at line " + position.line + " of "+position.source)
                    case INFO => Logger.info(msg)
                }
            }
            
        }

        // New compiler
        private val realFiles = HashMap[String,VFile]()
        private val virtualDirectory = new VirtualDirectory("(memory)", None)
        private val settings = new Settings()
        settings.debuginfo.level = 3
        settings.outputDirs setSingleOutput virtualDirectory   
        settings.deprecation.value = true
		settings.classpath.value = System.getProperty("java.class.path")
        private val compiler = new Global(settings, reporter)

        def compile(sources: List[VFile]) = {
            val c = compiler
            val run = new c.Run()            

            // BatchSources
            realFiles.clear()
            var sourceFiles = sources map { vfile =>
                val name = vfile.relativePath
                realFiles.put(name, vfile)
                new BatchSourceFile(name, vfile.contentAsString)
            }

            // Clear compilation results
            virtualDirectory.clear

            // Compile
            play.Logger.trace("SCALA Start compiling")
            run compileSources sourceFiles
            play.Logger.trace("SCALA Done ...")

            // Retrieve result
            val classes = new java.util.ArrayList[ApplicationClass]()

            def scan(path: AbstractFile): Unit = {
                path match {
                    case d: VirtualDirectory => path.iterator foreach scan
                    case f: VirtualFile =>
                                val byteCode = play.libs.IO.readContent(path.input)
                                val infos = play.utils.Java.extractInfosFromByteCode(byteCode)
                                var applicationClass = Play.classes.getApplicationClass(infos(0))
                                if(applicationClass == null) {
                                    applicationClass = new ApplicationClass() {

                                        override def compile() = {
                                            javaByteCode
                                        }

                                    }
                                    applicationClass.name = infos(0)
                                    applicationClass.javaFile = realFiles.get(infos(1)).get
                                    applicationClass.javaSource = applicationClass.javaFile.contentAsString
                                    play.Play.classes.add(applicationClass)
                                }
                                applicationClass.compiled(byteCode)
                                classes.add(applicationClass)
                }
            }
            virtualDirectory.iterator foreach scan

            //
            classes
        }

    }

}

