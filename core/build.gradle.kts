plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies { testImplementation("junit:junit:4.13.2") }

tasks.register<JavaExec>("swarmReport") {
    group = "verification"
    description = "Print the computed synthetic swarm comparison as JSON"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.jsons.roadmesh.core.swarm.SwarmScenarioCli")
}

tasks.register<Jar>("junctionPolicyJar") {
    group = "build"
    description = "Build the standalone Android-free junction policy TSV process"
    archiveFileName.set("core-junction-policy.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.jsons.roadmesh.core.junction.JunctionPolicyCli" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
