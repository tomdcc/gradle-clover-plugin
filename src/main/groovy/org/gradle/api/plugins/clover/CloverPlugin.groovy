/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.plugins.clover

import groovy.util.logging.Slf4j
import java.lang.reflect.Constructor
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.execution.TaskExecutionGraph
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.AsmBackedClassGenerator
import org.gradle.api.plugins.GroovyPlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.clover.internal.LicenseResolverFactory
import org.gradle.api.tasks.testing.Test

/**
 * <p>A {@link org.gradle.api.Plugin} that provides a task for creating a code coverage report using Clover.</p>
 *
 * @author Benjamin Muschko
 */
@Slf4j
class CloverPlugin implements Plugin<Project> {
    static final String CONFIGURATION_NAME = 'clover'
    static final String GENERATE_REPORT_TASK_NAME = 'cloverGenerateReport'
    static final String AGGREGATE_REPORTS_TASK_NAME = 'cloverAggregateReports'
    static final String REPORT_GROUP = 'report'
    static final String DEFAULT_JAVA_INCLUDES = '**/*.java'
    static final String DEFAULT_GROOVY_INCLUDES = '**/*.groovy'
    static final String DEFAULT_JAVA_TEST_INCLUDES = '**/*Test.java'
    static final String DEFAULT_GROOVY_TEST_INCLUDES = '**/*Test.groovy'
    static final String DEFAULT_CLOVER_DATABASE = '.clover/clover.db'

    @Override
    void apply(Project project) {
        project.configurations.add(CONFIGURATION_NAME).setVisible(false).setTransitive(true)
                .setDescription('The Clover library to be used for this project.')

        CloverPluginConvention cloverPluginConvention = new CloverPluginConvention()
        project.convention.plugins.clover = cloverPluginConvention

        configureInstrumentationAction(project, cloverPluginConvention)
        configureGenerateCoverageReportTask(project, cloverPluginConvention)
        configureAggregateReportsTask(project, cloverPluginConvention)
    }

    private void configureInstrumentationAction(Project project, CloverPluginConvention cloverPluginConvention) {
        AsmBackedClassGenerator generator = new AsmBackedClassGenerator()
        Class<? extends InstrumentCodeAction> instrumentClass = generator.generate(InstrumentCodeAction)
        Constructor<InstrumentCodeAction> constructor = instrumentClass.getConstructor()

        InstrumentCodeAction instrumentCodeAction = constructor.newInstance()
        instrumentCodeAction.conventionMapping.map('initString') { getInitString(cloverPluginConvention) }
        instrumentCodeAction.conventionMapping.map('compileGroovy') { hasGroovyPlugin(project) }
        instrumentCodeAction.conventionMapping.map('cloverClasspath') { project.configurations.getByName(CONFIGURATION_NAME).asFileTree }
        instrumentCodeAction.conventionMapping.map('testRuntimeClasspath') { getTestRuntimeClasspath(project).asFileTree }
        instrumentCodeAction.conventionMapping.map('groovyClasspath') { project.configurations.groovy.asFileTree }
        instrumentCodeAction.conventionMapping.map('licenseFile') { getLicenseFile(project, cloverPluginConvention) }
        instrumentCodeAction.conventionMapping.map('buildDir') { project.buildDir }
        instrumentCodeAction.conventionMapping.map('sourceSets') { getSourceSets(project, cloverPluginConvention) }
        instrumentCodeAction.conventionMapping.map('testSourceSets') { getTestSourceSets(project, cloverPluginConvention) }
        instrumentCodeAction.conventionMapping.map('sourceCompatibility') { project.sourceCompatibility?.toString() }
        instrumentCodeAction.conventionMapping.map('targetCompatibility') { project.targetCompatibility?.toString() }
        instrumentCodeAction.conventionMapping.map('includes') { getIncludes(project, cloverPluginConvention) }
        instrumentCodeAction.conventionMapping.map('excludes') { cloverPluginConvention.excludes }
        instrumentCodeAction.conventionMapping.map('testIncludes') { getTestIncludes(project, cloverPluginConvention) }
        instrumentCodeAction.conventionMapping.map('statementContexts') { cloverPluginConvention.contexts.statements }
        instrumentCodeAction.conventionMapping.map('methodContexts') { cloverPluginConvention.contexts.methods }

        project.gradle.taskGraph.whenReady { TaskExecutionGraph graph ->
            def generateReportTask = project.tasks.getByName(GENERATE_REPORT_TASK_NAME)

            // Only invoke instrumentation when Clover report generation task is run
            if(graph.hasTask(generateReportTask)) {
                project.tasks.withType(Test).each { Test test ->
                    test.classpath += project.configurations.getByName(CONFIGURATION_NAME).asFileTree
                    test.doFirst instrumentCodeAction
                }
            }
        }
    }

    private void configureGenerateCoverageReportTask(Project project, CloverPluginConvention cloverPluginConvention) {
        project.tasks.withType(GenerateCoverageReportTask).whenTaskAdded { GenerateCoverageReportTask generateCoverageReportTask ->
            generateCoverageReportTask.dependsOn project.tasks.withType(Test)
            generateCoverageReportTask.conventionMapping.map('initString') { getInitString(cloverPluginConvention) }
            generateCoverageReportTask.conventionMapping.map('buildDir') { project.buildDir }
            generateCoverageReportTask.conventionMapping.map('sourceSets') { getSourceSets(project, cloverPluginConvention) }
            generateCoverageReportTask.conventionMapping.map('testSourceSets') { getTestSourceSets(project, cloverPluginConvention) }
            generateCoverageReportTask.conventionMapping.map('cloverClasspath') { project.configurations.getByName(CONFIGURATION_NAME).asFileTree }
            generateCoverageReportTask.conventionMapping.map('licenseFile') { getLicenseFile(project, cloverPluginConvention) }
            generateCoverageReportTask.conventionMapping.map('targetPercentage') { cloverPluginConvention.targetPercentage }
            generateCoverageReportTask.conventionMapping.map('filter') { cloverPluginConvention.report.filter }
            generateCoverageReportTask.conventionMapping.map('testIncludes') { getTestIncludes(project, cloverPluginConvention) }
            setCloverReportConventionMappings(project, cloverPluginConvention, generateCoverageReportTask)
        }

        GenerateCoverageReportTask generateCoverageReportTask = project.tasks.add(GENERATE_REPORT_TASK_NAME, GenerateCoverageReportTask)
        generateCoverageReportTask.description = 'Generates Clover code coverage report.'
        generateCoverageReportTask.group = REPORT_GROUP
    }

    private void configureAggregateReportsTask(Project project, CloverPluginConvention cloverPluginConvention) {
        project.tasks.withType(AggregateReportsTask).whenTaskAdded { AggregateReportsTask aggregateReportsTask ->
            aggregateReportsTask.conventionMapping.map('initString') { getInitString(cloverPluginConvention) }
            aggregateReportsTask.conventionMapping.map('cloverClasspath') { project.configurations.getByName(CONFIGURATION_NAME).asFileTree }
            aggregateReportsTask.conventionMapping.map('licenseFile') { getLicenseFile(project, cloverPluginConvention) }
            aggregateReportsTask.conventionMapping.map('buildDir') { project.buildDir }
            aggregateReportsTask.conventionMapping.map('subprojectBuildDirs') { project.subprojects.collect { it.buildDir } }
            setCloverReportConventionMappings(project, cloverPluginConvention, aggregateReportsTask)
        }

        project.afterEvaluate {
            // Only add task to root project
            if(project == project.rootProject && project.subprojects.size() > 0) {
                AggregateReportsTask aggregateReportsTask = project.rootProject.tasks.add(AGGREGATE_REPORTS_TASK_NAME, AggregateReportsTask)
                aggregateReportsTask.description = 'Aggregates Clover code coverage reports.'
                aggregateReportsTask.group = REPORT_GROUP
                aggregateReportsTask.dependsOn project.tasks.getByName(GENERATE_REPORT_TASK_NAME)
            }
        }
    }

    /**
     * Sets Clover report convention mappings.
     *
     * @param project Project
     * @param cloverPluginConvention Clover plugin convention
     * @param task Task
     */
    private void setCloverReportConventionMappings(Project project, CloverPluginConvention cloverPluginConvention, Task task) {
        task.conventionMapping.map('reportsDir') { new File(project.buildDir, 'reports') }
        task.conventionMapping.map('xml') { cloverPluginConvention.report.xml }
        task.conventionMapping.map('json') { cloverPluginConvention.report.json }
        task.conventionMapping.map('html') { cloverPluginConvention.report.html }
        task.conventionMapping.map('pdf') { cloverPluginConvention.report.pdf }
        task.conventionMapping.map('projectName') { project.name }
    }

    /**
     * Gets init String that determines location of Clover database.
     *
     * @param cloverPluginConvention Clover plugin convention
     * @return Init String
     */
    private String getInitString(CloverPluginConvention cloverPluginConvention) {
        cloverPluginConvention.initString ?: DEFAULT_CLOVER_DATABASE
    }

    /**
     * Gets Clover license file.
     *
     * @param project Project
     * @param cloverPluginConvention Clover plugin convention
     * @return License file
     */
    private File getLicenseFile(Project project, CloverPluginConvention cloverPluginConvention) {
        LicenseResolverFactory.instance.getResolver(cloverPluginConvention.licenseLocation).resolve(project.rootDir, cloverPluginConvention.licenseLocation)
    }

    private Set<CloverSourceSet> getSourceSets(Project project, CloverPluginConvention cloverPluginConvention) {
        def cloverSourceSets = []

        if(hasGroovyPlugin(project)) {
            CloverSourceSet cloverSourceSet = new CloverSourceSet()
            cloverSourceSet.srcDirs.addAll(filterNonExistentDirectories(project.sourceSets.main.java.srcDirs))
            cloverSourceSet.srcDirs.addAll(filterNonExistentDirectories(project.sourceSets.main.groovy.srcDirs))
            cloverSourceSet.classesDir = project.sourceSets.main.output.classesDir
            cloverSourceSet.backupDir = cloverPluginConvention.classesBackupDir ?: new File("${project.sourceSets.main.output.classesDir}-bak")
            cloverSourceSets << cloverSourceSet
        }
        else if(hasJavaPlugin(project)) {
            CloverSourceSet cloverSourceSet = new CloverSourceSet()
            cloverSourceSet.srcDirs.addAll(filterNonExistentDirectories(project.sourceSets.main.java.srcDirs))
            cloverSourceSet.classesDir = project.sourceSets.main.output.classesDir
            cloverSourceSet.backupDir = cloverPluginConvention.classesBackupDir ?: new File("${project.sourceSets.main.output.classesDir}-bak")
            cloverSourceSets << cloverSourceSet
        }

        if(cloverPluginConvention.additionalSourceSets) {
            cloverPluginConvention.additionalSourceSets.each {
                CloverSourceSet cloverSourceSet = new CloverSourceSet()
                cloverSourceSet.srcDirs.addAll(filterNonExistentDirectories(it.srcDirs))
                cloverSourceSet.classesDir = it.output.classesDir
                cloverSourceSet.backupDir = cloverPluginConvention.classesBackupDir ?: new File("${it.output.classesDir}-bak")
                cloverSourceSets << cloverSourceSet
            }
        }

        cloverSourceSets
    }

    private Set<CloverSourceSet> getTestSourceSets(Project project, CloverPluginConvention cloverPluginConvention) {
        def cloverSourceSets = []

        if(hasGroovyPlugin(project)) {
            CloverSourceSet cloverSourceSet = new CloverSourceSet()
            cloverSourceSet.srcDirs.addAll(filterNonExistentDirectories(project.sourceSets.test.java.srcDirs))
            cloverSourceSet.srcDirs.addAll(filterNonExistentDirectories(project.sourceSets.test.groovy.srcDirs))
            cloverSourceSet.classesDir = project.sourceSets.test.output.classesDir
            cloverSourceSet.backupDir = cloverPluginConvention.testClassesBackupDir ?: new File("${project.sourceSets.test.output.classesDir}-bak")
            cloverSourceSets << cloverSourceSet
        }
        else if(hasJavaPlugin(project)) {
            CloverSourceSet cloverSourceSet = new CloverSourceSet()
            cloverSourceSet.srcDirs.addAll(filterNonExistentDirectories(project.sourceSets.test.java.srcDirs))
            cloverSourceSet.classesDir = project.sourceSets.test.output.classesDir
            cloverSourceSet.backupDir = cloverPluginConvention.testClassesBackupDir ?: new File("${project.sourceSets.test.output.classesDir}-bak")
            cloverSourceSets << cloverSourceSet
        }

        if(cloverPluginConvention.additionalTestSourceSets) {
            cloverPluginConvention.additionalTestSourceSets.each {
                CloverSourceSet cloverSourceSet = new CloverSourceSet()
                cloverSourceSet.srcDirs.addAll(filterNonExistentDirectories(it.srcDirs))
                cloverSourceSet.classesDir = it.output.classesDir
                cloverSourceSet.backupDir = cloverPluginConvention.classesBackupDir ?: new File("${it.output.classesDir}-bak")
                cloverSourceSets << cloverSourceSet
            }
        }

        cloverSourceSets
    }

    private Set<File> filterNonExistentDirectories(Set<File> dirs) {
        dirs.findAll { it.exists() }
    }

    /**
     * Gets includes for compilation. Uses includes if set as convention property. Otherwise, use default includes. The
     * default includes are determined by the fact if Groovy plugin was applied to project or not.
     *
     * @param project Project
     * @param cloverPluginConvention Clover plugin convention
     * @return Includes
     */
    private List getIncludes(Project project, CloverPluginConvention cloverPluginConvention) {
        if(cloverPluginConvention.includes) {
            return cloverPluginConvention.includes
        }

        if(hasGroovyPlugin(project)) {
            return [DEFAULT_JAVA_INCLUDES, DEFAULT_GROOVY_INCLUDES]
        }

        [DEFAULT_JAVA_INCLUDES]
    }

    /**
     * Gets test includes for compilation. Uses includes if set as convention property. Otherwise, use default includes. The
     * default includes are determined by the fact if Groovy plugin was applied to project or not.
     *
     * @param project Project
     * @param cloverPluginConvention Clover plugin convention
     * @return Test includes
     */
    private List getTestIncludes(Project project, CloverPluginConvention cloverPluginConvention) {
        if(cloverPluginConvention.testIncludes) {
            return cloverPluginConvention.testIncludes
        }

        if(hasGroovyPlugin(project)) {
            return [DEFAULT_JAVA_TEST_INCLUDES, DEFAULT_GROOVY_TEST_INCLUDES]
        }

        [DEFAULT_JAVA_TEST_INCLUDES]
    }

    /**
     * Checks to see if Java plugin got applied to project.
     *
     * @param project Project
     * @return Flag
     */
    private boolean hasJavaPlugin(Project project) {
        project.plugins.hasPlugin(JavaPlugin)
    }

    /**
     * Checks to see if Groovy plugin got applied to project.
     *
     * @param project Project
     * @return Flag
     */
    private boolean hasGroovyPlugin(Project project) {
        project.plugins.hasPlugin(GroovyPlugin)
    }

    /**
     * Gets testRuntime classpath which consists of the existing testRuntime configuration FileTree and the Clover
     * configuration FileTree.
     *
     * @param project Project
     * @return File collection
     */
    private FileCollection getTestRuntimeClasspath(Project project) {
        project.configurations.testRuntime.asFileTree + project.configurations.getByName(CONFIGURATION_NAME).asFileTree
    }
}
