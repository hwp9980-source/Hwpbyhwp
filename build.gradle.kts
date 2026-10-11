// Plugins are applied per module. Kotlin is deliberately not declared here: it would load in the
// root classloader, where it cannot see the Android Gradle plugin that only :app loads.
