package org.jetbrains.kotlin.gradle.plugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.SourceSet
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.internal.HasConvention
import java.io.File
import org.gradle.api.Action
import org.gradle.api.tasks.compile.AbstractCompile
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.BasePlugin
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.build.gradle.internal.variant.BaseVariantOutputData
import org.gradle.api.initialization.dsl.ScriptHandler
import org.jetbrains.kotlin.gradle.plugin.android.AndroidGradleWrapper
import javax.inject.Inject
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.Delete
import groovy.lang.Closure
import org.gradle.api.artifacts.Configuration
import org.jetbrains.kotlin.gradle.tasks.KotlinTasksProvider
import org.gradle.api.logging.*
import org.gradle.api.plugins.*
import org.gradle.api.tasks.compile.JavaCompile
import org.jetbrains.kotlin.gradle.internal.*

val KOTLIN_AFTER_JAVA_TASK_SUFFIX = "AfterJava"

abstract class KotlinSourceSetProcessor<T : AbstractCompile>(
        val project: ProjectInternal,
        val javaBasePlugin: JavaBasePlugin,
        val sourceSet: SourceSet,
        val pluginName: String,
        val compileTaskNameSuffix: String,
        val taskDescription: String,
        val compilerClass: Class<T>
) {
    abstract protected fun doTargetSpecificProcessing()
    val logger = Logging.getLogger(this.javaClass)

    protected val sourceSetName: String = sourceSet.name
    protected val sourceRootDir: String = "src/$sourceSetName/kotlin"
    protected val absoluteSourceRootDir: String = project.projectDir.path + "/" + sourceRootDir
    protected val kotlinSourceSet: KotlinSourceSet? by lazy { createKotlinSourceSet() }
    protected val kotlinDirSet: SourceDirectorySet? by lazy { createKotlinDirSet() }
    protected val kotlinTask: T by lazy { createKotlinCompileTask() }
    protected val kotlinTaskName: String by lazy { kotlinTask.name }

    public fun run() {
        if (kotlinSourceSet == null || kotlinDirSet == null) {
            return
        }

        addSourcesToKotlinDirSet()
        commonTaskConfiguration()
        doTargetSpecificProcessing()
    }

    open protected fun createKotlinSourceSet(): KotlinSourceSet? {
        return if (sourceSet is HasConvention) {
            logger.kotlinDebug("Creating KotlinSourceSet for source set $sourceSet")
            val kotlinSourceSet = KotlinSourceSetImpl(sourceSet.name, project.fileResolver)
            sourceSet.convention.plugins.put(pluginName, kotlinSourceSet)
            kotlinSourceSet
        }
        else null
    }

    open protected fun createKotlinDirSet(): SourceDirectorySet? {
        val srcDir = project.file(sourceRootDir)
        logger.kotlinDebug("Creating Kotlin SourceDirectorySet for source set $kotlinSourceSet with src dir ${srcDir}")
        return kotlinSourceSet?.getKotlin()?.apply { srcDir(srcDir) }
    }

    open protected fun addSourcesToKotlinDirSet() {
        logger.kotlinDebug("Adding Kotlin SourceDirectorySet $kotlinDirSet to source set $sourceSet")
        with (sourceSet) {
            allJava?.source(kotlinDirSet)
            allSource?.source(kotlinDirSet)
            resources?.filter?.exclude { kotlinDirSet!!.contains(it.file) }
        }
    }

    open protected fun createKotlinCompileTask(suffix: String = ""): T {
        val name = sourceSet.getCompileTaskName(compileTaskNameSuffix) + suffix
        logger.kotlinDebug("Creating kotlin compile task $name with class $compilerClass")
        val compile = project.tasks.create(name, compilerClass)
        compile.extensions.extraProperties.set("defaultModuleName", "${project.name}-$name")
        return compile
    }

    open protected fun commonTaskConfiguration() {
        javaBasePlugin.configureForSourceSet(sourceSet, kotlinTask)
        kotlinTask.description = taskDescription
        kotlinTask.source(kotlinDirSet)
    }
}

class Kotlin2JvmSourceSetProcessor(
        project: ProjectInternal,
        javaBasePlugin: JavaBasePlugin,
        sourceSet: SourceSet,
        val scriptHandler: ScriptHandler,
        val tasksProvider: KotlinTasksProvider
) : KotlinSourceSetProcessor<AbstractCompile>(
        project, javaBasePlugin, sourceSet,
        pluginName = "kotlin",
        compileTaskNameSuffix = "kotlin",
        taskDescription = "Compiles the $sourceSet.kotlin.",
        compilerClass = tasksProvider.kotlinJVMCompileTaskClass
) {

    private companion object {
        private var cachedKotlinAnnotationProcessingDep: String? = null
    }

    override fun doTargetSpecificProcessing() {
        // store kotlin classes in separate directory. They will serve as class-path to java compiler
        val kotlinDestinationDir = File(project.buildDir, "kotlin-classes/${sourceSetName}")
        kotlinTask.setProperty("kotlinDestinationDir", kotlinDestinationDir)

        val javaTask = project.tasks.findByName(sourceSet.compileJavaTaskName) as AbstractCompile?

        if (javaTask != null) {
            javaTask.dependsOn(kotlinTaskName)
            javaTask.doFirst {
                javaTask.classpath += project.files(kotlinDestinationDir)
            }
        }

        val kotlinAnnotationProcessingDep = cachedKotlinAnnotationProcessingDep ?: run {
            val projectVersion = loadKotlinVersionFromResource(project.logger)
            val dep = "org.jetbrains.kotlin:kotlin-annotation-processing:$projectVersion"
            cachedKotlinAnnotationProcessingDep = dep
            dep
        }

        val aptConfiguration = project.createAptConfiguration(sourceSet.name, kotlinAnnotationProcessingDep)

        project.afterEvaluate { project ->
            if (project == null) return@afterEvaluate

            for (dir in sourceSet.java.srcDirs) {
                kotlinDirSet?.srcDir(dir)
            }

            val subpluginEnvironment = loadSubplugins(project)
            subpluginEnvironment.addSubpluginArguments(project, kotlinTask)

            if (aptConfiguration.dependencies.size > 1 && javaTask is JavaCompile) {
                val (aptOutputDir, aptWorkingDir) = project.getAptDirsForSourceSet(sourceSetName)

                val kaptManager = AnnotationProcessingManager(kotlinTask, javaTask, sourceSetName,
                        aptConfiguration.resolve(), aptOutputDir, aptWorkingDir, tasksProvider.tasksLoader)

                val kotlinAfterJavaTask = project.initKapt(kotlinTask, javaTask, kaptManager,
                        sourceSetName, kotlinDestinationDir, subpluginEnvironment) {
                    createKotlinCompileTask(it)
                }

                if (kotlinAfterJavaTask != null) {
                    javaTask.doFirst {
                        kotlinAfterJavaTask.classpath = project.files(kotlinTask.classpath, javaTask.destinationDir)
                    }
                }
            }
        }
    }
}

class Kotlin2JsSourceSetProcessor(
        project: ProjectInternal,
        javaBasePlugin: JavaBasePlugin,
        sourceSet: SourceSet,
        val scriptHandler: ScriptHandler,
        val tasksProvider: KotlinTasksProvider
) : KotlinSourceSetProcessor<AbstractCompile>(
        project, javaBasePlugin, sourceSet,
        pluginName = "kotlin2js",
        taskDescription = "Compiles the kotlin sources in $sourceSet to JavaScript.",
        compileTaskNameSuffix = "kotlin2Js",
        compilerClass = tasksProvider.kotlinJSCompileTaskClass
) {

    val copyKotlinJsTaskName = sourceSet.getTaskName("copy", "kotlinJs")
    val clean = project.tasks.findByName("clean")
    val build = project.tasks.findByName("build")

    val defaultKotlinDestinationDir = File(project.buildDir, "kotlin2js/${sourceSetName}")
    private fun kotlinTaskDestinationDir(): File? = kotlinTask.property("kotlinDestinationDir") as File?
    private fun kotlinJsDestinationDir(): File? = (kotlinTask.property("outputFile") as String).let { File(it).directory }

    private fun kotlinSourcePathsForSourceMap() = sourceSet.allSource
            .map { it.path }
            .filter { it.endsWith(".kt") }
            .map { it.replace(absoluteSourceRootDir, (kotlinTask.property("sourceMapDestinationDir") as File).path) }

    private fun shouldGenerateSourceMap() = kotlinTask.property("sourceMap")

    override fun doTargetSpecificProcessing() {
        kotlinTask.setProperty("kotlinDestinationDir", defaultKotlinDestinationDir)
        build?.dependsOn(kotlinTaskName)
        clean?.dependsOn("clean" + kotlinTaskName.capitalize())

        createCleanSourceMapTask()
    }

    private fun createCleanSourceMapTask() {
        val taskName = sourceSet.getTaskName("clean", "sourceMap")
        val task = project.tasks.create(taskName, Delete::class.java)
        task.onlyIf { kotlinTask.property("sourceMap") as Boolean }
        task.delete(object : Closure<String>(this) {
            override fun call(): String? = (kotlinTask.property("outputFile") as String) + ".map"
        })
        clean?.dependsOn(taskName)
    }
}


abstract class AbstractKotlinPlugin @Inject constructor(
        val scriptHandler: ScriptHandler,
        val tasksProvider: KotlinTasksProvider
) : Plugin<Project> {
    abstract fun buildSourceSetProcessor(
            project: ProjectInternal,
            javaBasePlugin: JavaBasePlugin,
            sourceSet: SourceSet
    ): KotlinSourceSetProcessor<*>

    public override fun apply(project: Project) {
        val javaBasePlugin = project.plugins.apply(JavaBasePlugin::class.java)
        val javaPluginConvention = project.convention.getPlugin(JavaPluginConvention::class.java)

        project.plugins.apply(JavaPlugin::class.java)

        configureSourceSetDefaults(project as ProjectInternal, javaBasePlugin, javaPluginConvention)
    }

    open protected fun configureSourceSetDefaults(
            project: ProjectInternal,
            javaBasePlugin: JavaBasePlugin,
            javaPluginConvention: JavaPluginConvention
    ) {
        javaPluginConvention.sourceSets?.all(Action<SourceSet> { sourceSet ->
            if (sourceSet != null) {
                buildSourceSetProcessor(project, javaBasePlugin, sourceSet).run()
            }
        })
    }
}


open class KotlinPlugin @Inject constructor(
        scriptHandler: ScriptHandler,
        tasksProvider: KotlinTasksProvider
) : AbstractKotlinPlugin(scriptHandler, tasksProvider) {
    override fun buildSourceSetProcessor(project: ProjectInternal, javaBasePlugin: JavaBasePlugin, sourceSet: SourceSet) =
            Kotlin2JvmSourceSetProcessor(project, javaBasePlugin, sourceSet, scriptHandler, tasksProvider)

    override fun apply(project: Project) {
        project.createKaptExtension()
        super.apply(project)
    }
}


open class Kotlin2JsPlugin @Inject constructor(
        scriptHandler: ScriptHandler,
        tasksProvider: KotlinTasksProvider
) : AbstractKotlinPlugin(scriptHandler, tasksProvider) {
    override fun buildSourceSetProcessor(project: ProjectInternal, javaBasePlugin: JavaBasePlugin, sourceSet: SourceSet) =
            Kotlin2JsSourceSetProcessor(project, javaBasePlugin, sourceSet, scriptHandler, tasksProvider)
}


open class KotlinAndroidPlugin @Inject constructor(
        val scriptHandler: ScriptHandler,
        val tasksProvider: KotlinTasksProvider
) : Plugin<Project> {
    val log = Logging.getLogger(this.javaClass)

    public override fun apply(p0: Project) {

        val project = p0 as ProjectInternal
        val ext = project.extensions.getByName("android") as BaseExtension

        val version = loadAndroidPluginVersion()
        if (version != null) {
            val minimalVersion = "1.1.0"
            if (compareVersionNumbers(version, minimalVersion) < 0) {
                throw IllegalStateException("Kotlin: Unsupported version of com.android.tools.build:gradle plugin: " +
                        "version $minimalVersion or higher should be used with kotlin-android plugin")
            }
        }

        val aptConfigurations = hashMapOf<String, Configuration>()

        val projectVersion = loadKotlinVersionFromResource(log)
        val kotlinAnnotationProcessingDep = "org.jetbrains.kotlin:kotlin-annotation-processing:$projectVersion"

        ext.sourceSets.all(Action<AndroidSourceSet> { sourceSet ->
            if (sourceSet is HasConvention) {
                val sourceSetName = sourceSet.name
                val kotlinSourceSet = KotlinSourceSetImpl(sourceSetName, project.fileResolver)
                sourceSet.convention.plugins.put("kotlin", kotlinSourceSet)
                val kotlinDirSet = kotlinSourceSet.getKotlin()
                kotlinDirSet.srcDir(project.file("src/${sourceSetName}/kotlin"))

                aptConfigurations.put(sourceSet.name,
                        project.createAptConfiguration(sourceSet.name, kotlinAnnotationProcessingDep))

                /*TODO: before 0.11 gradle android plugin there was:
                  sourceSet.getAllJava().source(kotlinDirSet)
                  sourceSet.getAllSource().source(kotlinDirSet)
                  AndroidGradleWrapper.getResourceFilter(sourceSet)?.exclude(KSpec({ elem ->
                    kotlinDirSet.contains(elem.getFile())
                  }))
                 but those methods were removed so commented as temporary hack*/

                project.logger.kotlinDebug("Created kotlin sourceDirectorySet at ${kotlinDirSet.srcDirs}")
            }
        })

        (ext as ExtensionAware).extensions.add("kotlinOptions", tasksProvider.kotlinJVMOptionsClass)

        project.createKaptExtension()

        project.afterEvaluate { project ->
            if (project != null) {
                val plugin = (project.plugins.findPlugin("android")
                                ?: project.plugins.findPlugin("android-library")) as BasePlugin

                val variantManager = AndroidGradleWrapper.getVariantDataManager(plugin)
                processVariantData(variantManager.variantDataList, project,
                        ext, plugin, aptConfigurations)
            }
        }
    }

    private fun processVariantData(
            variantDataList: List<BaseVariantData<out BaseVariantOutputData>>,
            project: Project,
            androidExt: BaseExtension,
            androidPlugin: BasePlugin,
            aptConfigurations: Map<String, Configuration>
    ) {
        val logger = project.logger
        val kotlinOptions = getExtension<Any?>(androidExt, "kotlinOptions")

        val subpluginEnvironment = loadSubplugins(project)

        for (variantData in variantDataList) {
            val variantDataName = variantData.name
            logger.kotlinDebug("Process variant [$variantDataName]")

            val javaTask = AndroidGradleWrapper.getJavaCompile(variantData)

            if (javaTask == null) {
                logger.info("KOTLIN: javaTask is missing for $variantDataName, so Kotlin files won't be compiled for it")
                continue
            }

            val kotlinTaskName = "compile${variantDataName.capitalize()}Kotlin"
            val kotlinTask = tasksProvider.createKotlinJVMTask(project, kotlinTaskName)

            kotlinTask.extensions.extraProperties.set("defaultModuleName", "${project.name}-$kotlinTaskName")
            if (kotlinOptions != null) {
                kotlinTask.setProperty("kotlinOptions", kotlinOptions)
            }

            // store kotlin classes in separate directory. They will serve as class-path to java compiler
            val kotlinOutputDir = File(project.buildDir, "tmp/kotlin-classes/$variantDataName")

            with (kotlinTask) {
                setProperty("kotlinDestinationDir", kotlinOutputDir)
                destinationDir = javaTask.destinationDir
                description = "Compiles the $variantDataName kotlin."
                classpath = javaTask.classpath
                setDependsOn(javaTask.dependsOn)
            }

            fun SourceDirectorySet.addSourceDirectories(additionalSourceFiles: Collection<File>) {
                for (dir in additionalSourceFiles) {
                    this.srcDir(dir)
                    logger.kotlinDebug("Source directory ${dir.absolutePath} was added to kotlin source for $kotlinTaskName")
                }
            }

            val aptFiles = arrayListOf<File>()

            // getSortedSourceProviders should return only actual java sources, generated sources should be collected earlier
            val providers = variantData.variantConfiguration.sortedSourceProviders
            for (provider in providers) {
                val javaSrcDirs = AndroidGradleWrapper.getJavaSrcDirs(provider as AndroidSourceSet)
                val kotlinSourceSet = getExtension<KotlinSourceSet>(provider, "kotlin")
                val kotlinSourceDirectorySet = kotlinSourceSet.getKotlin()
                kotlinTask.source(kotlinSourceDirectorySet)

                kotlinSourceDirectorySet.addSourceDirectories(javaSrcDirs)

                val aptConfiguration = aptConfigurations[(provider as AndroidSourceSet).name]
                // Ignore if there's only an annotation processor wrapper in dependencies (added by default)
                if (aptConfiguration != null && aptConfiguration.dependencies.size > 1) {
                    aptFiles.addAll(aptConfiguration.resolve())
                }
            }

            // getJavaSources should return the Java sources used for compilation
            // We want to collect only generated files, like R-class output dir
            // Actual java sources will be collected later
            val additionalSourceFiles = variantData.javaSources.filterIsInstance(File::class.java)
            for (file in additionalSourceFiles) {
                kotlinTask.source(file)
                logger.kotlinDebug("Source directory with generated files ${file.absolutePath} was added to kotlin source for $kotlinTaskName")
            }

            subpluginEnvironment.addSubpluginArguments(project, kotlinTask)

            kotlinTask.doFirst {
                val androidRT = project.files(AndroidGradleWrapper.getRuntimeJars(androidPlugin, androidExt))
                val fullClasspath = (javaTask.classpath + androidRT) - project.files(kotlinTask.property("kotlinDestinationDir"))
                (it as AbstractCompile).classpath = fullClasspath

                for (task in project.getTasksByName(kotlinTaskName + KOTLIN_AFTER_JAVA_TASK_SUFFIX, false)) {
                    (task as AbstractCompile).classpath = project.files(fullClasspath, javaTask.destinationDir)
                }
            }

            javaTask.dependsOn(kotlinTaskName)

            val (aptOutputDir, aptWorkingDir) = project.getAptDirsForSourceSet(variantDataName)
            variantData.addJavaSourceFoldersToModel(aptOutputDir)

            if (javaTask is JavaCompile && aptFiles.isNotEmpty()) {
                val kaptManager = AnnotationProcessingManager(kotlinTask, javaTask, variantDataName,
                        aptFiles.toSet(), aptOutputDir, aptWorkingDir, tasksProvider.tasksLoader, variantData)

                kotlinTask.storeKaptAnnotationsFile(kaptManager)

                project.initKapt(kotlinTask, javaTask, kaptManager, variantDataName, kotlinOutputDir, subpluginEnvironment) {
                    tasksProvider.createKotlinJVMTask(project, kotlinTaskName + KOTLIN_AFTER_JAVA_TASK_SUFFIX)
                }
            }

            javaTask.doFirst {
                javaTask.classpath = javaTask.classpath + project.files(kotlinTask.property("kotlinDestinationDir"))
            }
        }
    }

    fun <T> getExtension(obj: Any, extensionName: String): T {
        if (obj is ExtensionAware) {
            val result = obj.extensions.findByName(extensionName)
            if (result != null) {
                @Suppress("UNCHECKED_CAST")
                return result as T
            }
        }
        val result = (obj as HasConvention).convention.plugins[extensionName]
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
}