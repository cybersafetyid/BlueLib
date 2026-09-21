package io.github.cybersafetyid.bluelib.domain.error

/**
 * Outcome of a BlueLib operation: either a value or a typed [BlueLibError].
 *
 * Kotlin's `Result` cannot carry BlueLib's error taxonomy without wrapping, and throwing everywhere
 * would hide the "is this retryable?" question that applications actually need to answer. Helpers
 * mirror the standard library so the type stays familiar.
 */
public sealed interface BlueLibResult<out T> {

    /** The operation succeeded. */
    public data class Success<out T>(public val value: T) : BlueLibResult<T>

    /** The operation failed with a typed error. */
    public data class Failure(public val error: BlueLibError) : BlueLibResult<Nothing>

    /** `true` when the result carries a value. */
    public val isSuccess: Boolean
        get() = this is Success

    /** `true` when the result carries an error. */
    public val isFailure: Boolean
        get() = this is Failure

    /** Returns the value or `null`. */
    public fun getOrNull(): T? = (this as? Success)?.value

    /** Returns the error or `null`. */
    public fun errorOrNull(): BlueLibError? = (this as? Failure)?.error

    /** Returns the value or throws [BlueLibException] carrying the typed error. */
    public fun getOrThrow(): T = when (this) {
        is Success -> value
        is Failure -> throw BlueLibException(error)
    }

    /** Maps the success value. */
    public fun <R> map(transform: (T) -> R): BlueLibResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
    }

    /** Runs [action] when the result is a success. */
    public fun onSuccess(action: (T) -> Unit): BlueLibResult<T> {
        if (this is Success) action(value)
        return this
    }

    /** Runs [action] when the result is a failure. */
    public fun onFailure(action: (BlueLibError) -> Unit): BlueLibResult<T> {
        if (this is Failure) action(error)
        return this
    }

    /** Folds both branches into one value. */
    public fun <R> fold(onSuccess: (T) -> R, onFailure: (BlueLibError) -> R): R = when (this) {
        is Success -> onSuccess(value)
        is Failure -> onFailure(error)
    }
}

/** Builds a success result. */
public fun <T> successOf(value: T): BlueLibResult<T> = BlueLibResult.Success(value)

/** Builds a failure result. */
public fun <T> failureOf(error: BlueLibError): BlueLibResult<T> = BlueLibResult.Failure(error)

/** Wraps [block], converting validation failures into typed results. */
public inline fun <T> blueLibRunCatching(operation: String, block: () -> T): BlueLibResult<T> = try {
    BlueLibResult.Success(block())
} catch (validation: BlueLibValidationException) {
    BlueLibResult.Failure(
        BlueLibError.OperationRejected(reason = validation.message ?: "validation failed", hint = validation.docsAnchor),
    )
} catch (throwable: Throwable) {
    BlueLibResult.Failure(BlueLibError.Unexpected(operation = operation, message = throwable.message ?: "failure", cause = throwable))
}

/**
 * Exception wrapper for the few places where Kotlin requires a throwable, such as a `Flow` that
 * fails mid-stream or `getOrThrow()`.
 */
public class BlueLibException(public val error: BlueLibError) : Exception(error.message, error.cause) {
    /** Documentation anchor pointing at `docs/troubleshooting.md#anchor`. */
    public val docsAnchor: String
        get() = error.docsAnchor
}
