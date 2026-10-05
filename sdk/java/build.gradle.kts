plugins { `java-library`; `maven-publish` }
val okhttpVersion: String by project
val jacksonVersion: String by project
dependencies {
    api(project(":plowshare-protocol"))
    api("com.fasterxml.jackson.core:jackson-databind:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
}
java { withSourcesJar(); withJavadocJar() }
publishing { publications { create<MavenPublication>("sdk") { from(components["java"]) } } }
