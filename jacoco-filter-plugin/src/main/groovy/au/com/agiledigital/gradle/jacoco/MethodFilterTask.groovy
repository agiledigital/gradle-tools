package au.com.agiledigital.gradle.jacoco

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.file.FileCollection

class MethodFilterTask extends DefaultTask {

    @InputFiles
    FileCollection classDirs

    FileCollection classPath

    @OutputFile
    File outputFile

    Collection<String> methodNames

    Collection<String> getMethodNames() {
      if (methodNames == null) {
        methodNames = ['hashCode', 'equals', 'toString']
      }
      return methodNames
    }

    FileCollection getClassDirs() {
      if (classDirs == null) {
        classDirs = project.fileTree(dir: 'build/jacoco').matching { include '**/*.exec*' }
      }
      return classDirs
    }

    void classDirs(Object... files) {
      logger.info "Setting input to $files"
      if (this.classDirs == null) {
        this.classDirs = project.files(files)
      }
      else {
        this.classDirs += project.files(files)
      }
    }

    FileCollection getClassPath() {
      if (this.classPath == null) {
        this.classPath = project.fileTree(dir: 'build/jacoco').matching { include '**/*.exec*' }
      }
      return this.classPath
    }

    void classPath(Object... files) {
      logger.info "Setting input to $files"
      if (this.classPath == null) {
        this.classPath = project.files(files)
      }
      else {
        this.classPath += project.files(files)
      }
    }

    File getOutputFile() {
      if (outputFile == null) {
        outputFile = project.file('build/jacoco/filtered.exec')
      }
      return outputFile
    }

    @TaskAction
    public void methodFilterTask() {
        logger.info "Filtering ${getMethodNames()} -> ${getOutputFile()}"
        def filter = new JacocoMethodFilter()
        filter.filter(getMethodNames(), getClassDirs().getFiles(), getClassPath().getFiles())
        filter.save(getOutputFile())
    }
}
