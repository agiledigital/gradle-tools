package au.com.agiledigital.gradle.jacoco;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

public class MethodFilterTask extends DefaultTask {

    @TaskAction
    public void methodFilterTask() {
        System.out.println("Hello from MethodFilterTask");
    }
}

