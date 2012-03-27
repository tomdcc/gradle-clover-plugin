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
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

/**
 * Task for generating Clover code coverage report.
 *
 * @author Benjamin Muschko
 */
@Slf4j
class GenerateCoverageReportTask extends CloverReportTask {
    String initString
    File buildDir
    @Input Set<CloverSourceSet> sourceSets
    @Input Set<CloverSourceSet> testSourceSets
    FileCollection cloverClasspath
    @InputFile File licenseFile
    List<String> testIncludes
    String filter
    String targetPercentage

    @TaskAction
    void start() {
        validateConfiguration()

        if(allowReportGeneration()) {
            generateReport()
        }
    }

    private boolean allowReportGeneration() {
        isCloverDatabaseExistent() && existsAllClassesAndBackupDirs()
    }

    private boolean existsAllClassesAndBackupDirs() {
        for(CloverSourceSet sourceSet : getSourceSets()) {
            if(!sourceSet.classesDir.exists() || !sourceSet.backupDir.exists()) {
                return false
            }
        }

        true
    }

    private boolean isCloverDatabaseExistent() {
        new File("${getBuildDir()}/${getInitString()}").exists()
    }

    private void generateReport() {
        log.info 'Starting to generate Clover code coverage report.'

        ant.taskdef(resource: 'cloverlib.xml', classpath: getCloverClasspath().asPath)
        ant.property(name: 'clover.license.path', value: getLicenseFile().canonicalPath)

        restoreOriginalClasses()
        String cloverReportDir = "${getReportsDir()}/clover"

        if(getXml()) {
            writeReport("$cloverReportDir/clover.xml", ReportType.XML.format)
        }

        if(getJson()) {
            writeReport("$cloverReportDir/json", ReportType.JSON.format)
        }

        if(getHtml()) {
            writeReport("$cloverReportDir/html", ReportType.HTML.format)
        }

        if(getPdf()) {
            ant."clover-pdf-report"(initString: "${getBuildDir()}/${getInitString()}", outfile: "$cloverReportDir/clover.pdf",
                                    title: getProjectName())
        }

        log.info 'Finished generating Clover code coverage report.'
    }

    private void restoreOriginalClasses() {
        deleteAllClassesDirectories(getSourceSets())
        deleteAllClassesDirectories(getTestSourceSets())
        moveAllBackupDirsToClassesDirs(getSourceSets())
        moveAllBackupDirsToClassesDirs(getTestSourceSets())
    }

    private void deleteAllClassesDirectories(Set<CloverSourceSet> sourceSets) {
        for(CloverSourceSet sourceSet : sourceSets) {
            deleteClassesDirectory(sourceSet.classesDir)
        }
    }

    private void deleteClassesDirectory(File classesDir) {
        ant.delete(includeEmptyDirs: true) {
            fileset(dir: classesDir.canonicalPath, includes: '**/*')
        }
    }

    private void moveAllBackupDirsToClassesDirs(Set<CloverSourceSet> sourceSets) {
        for(CloverSourceSet sourceSet : sourceSets) {
            moveBackupToClassesDir(sourceSet.backupDir, sourceSet.classesDir)
        }
    }

    private void moveBackupToClassesDir(File backupDir, File classesDir) {
        if(backupDir && backupDir.exists()) {
            ant.move(file: backupDir.canonicalPath, tofile: classesDir.canonicalPath, failonerror: true)
        }
    }

    private void writeReport(String outfile, String type) {
        ant."clover-report"(initString: "${getBuildDir()}/${getInitString()}") {
            current(outfile: outfile, title: getProjectName()) {
                getTestSourceSets().collect { it.srcDirs }.each { testSrcDir ->
                    ant.testsources(dir: testSrcDir) {
                        getTestIncludes().each { include ->
                            ant.include(name: include)
                        }
                    }
                }
                if(getFilter()) {
                    format(type: type, filter: getFilter())
                }
                else {
                    format(type: type)
                }
            }
        }
    }
}
