package eu.kanade.tachiyomi.source.cloud

/**
 * Optional account entry point for cloud connectors that have a verified
 * username/password API. The host must call this from a lifecycle-scoped IO
 * coroutine and must not persist the password. Implementations must clear the
 * supplied character array before returning.
 */
interface CloudPasswordLoginSource {
    suspend fun login(username: String, password: CharArray): CloudAccountState
}
