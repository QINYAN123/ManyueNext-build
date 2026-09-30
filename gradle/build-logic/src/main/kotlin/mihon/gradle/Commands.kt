package mihon.gradle

import org.gradle.api.Project
import kotlin.time.Clock
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

// Git is needed in your system PATH for these commands to work.
// If it's not installed, you can return a random value as a workaround
fun Project.getLatestCommitCount(): String {
    return try {
        exec("git rev-list --count HEAD")
    } catch (t: Throwable) {
        "1"
    }
}

fun Project.getLatestCommitSha(): String {
    return try {
        exec("git rev-parse --short HEAD")
    } catch (t: Throwable) {
        "unknown"
    }
}

/**
 * @param useLatestCommitTime If `true`, the build time is based on the timestamp of the last Git commit;
 *                          otherwise, the current time is used. Both are in UTC.
 * @return An ISO 8601 formatted string representing the build time.
 */
fun Project.getBuildTime(useLatestCommitTime: Boolean): String {
    return if (useLatestCommitTime) {
        try {
            val epoch = exec("git log -1 --format=%ct").toLong()
            Instant.fromEpochSeconds(epoch).toString()
        } catch (t: Throwable) {
            val now = Clock.System.now()
            (now - now.nanosecondsOfSecond.nanoseconds).toString()
        }
    } else {
        val now = Clock.System.now()
        (now - now.nanosecondsOfSecond.nanoseconds).toString()
    }
}

fun Project.exec(command: String): String {
    return providers.exec {
        commandLine = command.split(" ")
    }
        .standardOutput
        .asText
        .get()
        .trim()
}
