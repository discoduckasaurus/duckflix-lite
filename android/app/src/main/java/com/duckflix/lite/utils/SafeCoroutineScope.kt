package com.duckflix.lite.utils

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Launches a coroutine with built-in exception handling to prevent app crashes.
 * Use this for API calls and other operations that might fail during network issues.
 *
 * @param onError Optional callback to handle errors (receives the exception)
 * @param block The coroutine block to execute
 */
fun CoroutineScope.safeLaunch(
    onError: ((Throwable) -> Unit)? = null,
    block: suspend CoroutineScope.() -> Unit
): Job {
    val handler = CoroutineExceptionHandler { _, throwable ->
        println("[SafeLaunch] Caught exception: ${throwable.javaClass.simpleName} - ${throwable.message}")
        throwable.printStackTrace()
        onError?.invoke(throwable)
    }

    return launch(handler) {
        try {
            block()
        } catch (e: Exception) {
            println("[SafeLaunch] Exception in block: ${e.javaClass.simpleName} - ${e.message}")
            e.printStackTrace()
            onError?.invoke(e)
        }
    }
}

/**
 * Executes a suspending API call with automatic error handling.
 * Returns null if the call fails, otherwise returns the result.
 *
 * @param onError Optional callback to handle errors
 * @param block The suspend function to execute
 */
suspend fun <T> safeApiCall(
    onError: ((Exception) -> Unit)? = null,
    block: suspend () -> T
): T? {
    return try {
        block()
    } catch (e: Exception) {
        println("[SafeApiCall] Exception: ${e.javaClass.simpleName} - ${e.message}")
        onError?.invoke(e)
        null
    }
}
