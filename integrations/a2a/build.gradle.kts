plugins { `java-library`; application }
val okhttpVersion: String by project
dependencies {
    api(project(":plowshare-sdk"))
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.squareup.okhttp3:mockwebserver:$okhttpVersion")
}
application { mainClass.set("io.aeyer.plowshare.a2a.Main") }
