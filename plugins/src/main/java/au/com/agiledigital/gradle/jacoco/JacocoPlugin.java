package au.com.agiledigital.gradle.jacoco;

import org.gradle.api.Project;
import org.gradle.api.Plugin;

public class JacocoPlugin implements Plugin<Project> {

    @Override
    public void apply(Project target) {
        target.task("methodFilterTask");
    }
}

