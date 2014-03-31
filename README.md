gradle-tools
============

Set of gradle tools to assist in life. This contains at the moment just a single plugin
* JacocoFilter => MethodFilter -- Filter the target methods for the jacoco exec by marking them 100% coverage.

To use update your repo and dependencies to include 

    buildscript {	
      repositories {	
         maven {	
          url 'https://raw.githubusercontent.com/agiledigital/gradle-tools/master/repo'	
         }	
         mavenCentral()	
      }	
      dependencies {	
         classpath 'au.com.agiledigital:jacoco-filter-plugin:0.1.0'	
      }	
    }


Then

	apply plugin: 'jacocofilter'
	
Tell it where the source and classes are and have it run at the end after jacoco.

    methodFilter {	
      classDirs sourceSets.main.output.classesDir	
      classPath configurations.compile	
    }.dependsOn(compileJava)	
    test.finalizedBy(methodFilter) 
    
This will then produce a new filtered.exec file that includes all the toString,hashcode,equals methods set to 100% coverage.
