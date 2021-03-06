package groovyx.grooid

import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.GroovyCompile
import org.gradle.api.tasks.compile.JavaCompile

/**
 * Adds support for building Android applications using the Groovy language.
 */
class GroovyAndroidPlugin implements Plugin<Project> {
    private static String LATEST_SUPPORTED = "1.1.0"

    private static List RUNTIMEJARS_COMPAT = [
            { it.runtimeJars },
            { it.bootClasspath },
            { it.androidBuilder.bootClasspath }, // returns List<File>
            { it.androidBuilder.getBootClasspath(false) } // returns List<Files>
    ]

    void apply(Project project) {
        project.extensions.create("androidGroovy", GroovyAndroidPluginExtension)

        def plugin = project.plugins.findPlugin('android') ?: project.plugins.findPlugin('android-library')
        if (!plugin) {
            throw new GradleException('You must apply the Android plugin or the Android library plugin before using the groovy-android plugin')
        }

        def groovyPlugin = this

        def isLibraryPlugin = plugin.class.name.endsWith('.LibraryPlugin')

        // IMPORTANT TO TO THIS FIRST, SEE https://groups.google.com/forum/#!msg/adt-dev/vXKzoS4nB1k/zUIa9Rp9SOIJ
        if (isLibraryPlugin) {
            project.android.libraryVariants.all {
                it.productFlavors*.name.each {
                    project.android.sourceSets.getByName(it).java.srcDir("src/$it/groovy")
                }
            }
        } else {
            project.android.applicationVariants.all {
                it.productFlavors*.name.each {
                    project.android.sourceSets.getByName(it).java.srcDir("src/$it/groovy")
                }
            }
        }

        project.android {
            packagingOptions {
                // workaround for http://stackoverflow.com/questions/20673625/android-gradle-plugin-0-7-0-duplicate-files-during-packaging-of-apk
                exclude 'META-INF/LICENSE.txt'
                exclude 'META-INF/groovy-release-info.properties'
            }

            def variants = isLibraryPlugin ? libraryVariants : applicationVariants

            variants.all {
                project.logger.debug("Configuring Groovy variant $it.name")
                def flavors = it.productFlavors*.name
                def types = it.buildType*.name
                groovyPlugin.attachGroovyCompileTask(project, plugin, javaCompile, ['main', *flavors, *types])

                // Unit tests (android plugin >= 1.1.0)
                def unitTestTaskName = javaCompile.name.replaceFirst('Java', 'UnitTestJava')
                def unitTestCompile = project.getTasksByName(unitTestTaskName, false)
                if (unitTestCompile) {
                    unitTestCompile.each { task ->
                        groovyPlugin.attachGroovyCompileTask(project, plugin, task, ['test'])
                    }
                }
            }
            testVariants.all {
                project.logger.debug("Configuring Groovy test variant $it.name")
                def flavors = it.productFlavors*.name
                def types = it.buildType*.name
                groovyPlugin.attachGroovyCompileTask(project, plugin, javaCompile, ['androidTest', *flavors, *types])
            }

            // Forces Android Studio to recognize groovy folder as code
            sourceSets {
                main.java.srcDir('src/main/groovy')
                androidTest.java.srcDir('src/androidTest/groovy')
                test.java.srcDir('src/test/groovy')
            }
        }
        project.logger.info("Detected Android plugin version ${getAndroidPluginVersion(project)}")
    }


    private void attachGroovyCompileTask(Project project, Plugin plugin, JavaCompile javaCompile, List<String> srcDirs) {
        def taskName = javaCompile.name.replace("Java", "Groovy")
        def srcDirsAsString = srcDirs.collect { "src/$it/groovy" }
        project.logger.debug("Configuring Groovy compile task [$taskName] with source directories $srcDirsAsString")
        def groovyCompile = project.task(taskName, type: GroovyCompile) {
            project.androidGroovy.configure(it)
            source = srcDirsAsString.collect { project.fileTree(project.file(it)) }
            destinationDir = javaCompile.destinationDir
            classpath = javaCompile.classpath
            groovyClasspath = classpath
            doFirst {
                // update the classpath of groovyCompile task, to use the latest classpath which may be updated
                // by other tasks, task "prepareComAndroidSupportSupportV42311Library" for example when using
                // "com.android.support:support-v4:23.1.1"
                // see https://github.com/groovy/groovy-android-gradle-plugin/pull/69
                classpath = javaCompile.classpath
                groovyClasspath = classpath
                // add Android's bootstrap classpath
                def pluginVersion = getAndroidPluginVersion(project)
                def runtimeJars = getRuntimeJars(pluginVersion, plugin)
                classpath = project.files(runtimeJars) + classpath
            }
        }

        javaCompile.finalizedBy(groovyCompile)
        javaCompile.exclude { e ->
            // this is a dirty hack to work around the fact that we need to declare the source tree as Java sources
            // for this to be recognized by Android Studio, so here we just exclude the files !
            def path = e.file.absolutePath
            // this dirty hack wasn't working on windows because of the different path styles in absolutePath
            srcDirsAsString.any { path.contains(it) || path.contains(it.replaceAll('/', '\\\\')) }
        }
    }

    private static String getAndroidPluginVersion(Project project) {
        def dependency = [project, project.rootProject].collect {
            it.buildscript.configurations.classpath.resolvedConfiguration.firstLevelModuleDependencies.find {
                it.moduleGroup == 'com.android.tools.build' && it.moduleName == 'gradle'
            }
        }.find()

        if (dependency) {
            return dependency.moduleVersion
        }
        project.logger.warn("Unable to determine Android build tools version from classpath. Falling back to default ($LATEST_SUPPORTED).")

        return LATEST_SUPPORTED
    }

    private static def getRuntimeJars(String pluginVersion, Plugin plugin) {
        int index = getRuntimeJarsIndex(pluginVersion)
        def fun = RUNTIMEJARS_COMPAT[index]
        return fun(plugin)
    }

    private static int getRuntimeJarsIndex(pluginVersion) {
        switch (pluginVersion) {
            case ~/0\.9\..*/:
                return 0
            case ~/0\.10\..*/:
            case ~/0\.11\..*/:
            case ~/0\.12\..*/:
            case ~/0\.13\..*/:
            case ~/0\.14\..*/:
            case ~/1\.0\..*/:
                return 1
            case ~/1\.1\..*/:
            case ~/1\.2\..*/:
            case ~/1\.3\..*/:
            case ~/1\.4\.0-beta1*/:
                return 2
            case ~/1\.4\.0-beta.*/:
                return 3
            default:
                return RUNTIMEJARS_COMPAT.size() - 1
        }
    }
}
