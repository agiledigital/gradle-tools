package au.com.agiledigital.gradle.jacoco

import org.gradle.api.Project
import org.gradle.api.Plugin
import org.gradle.api.file.FileCollection
import org.gradle.api.tasks.InputFiles

class JacocoFilterPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.task('methodFilter', type: MethodFilterTask)
    }
}
