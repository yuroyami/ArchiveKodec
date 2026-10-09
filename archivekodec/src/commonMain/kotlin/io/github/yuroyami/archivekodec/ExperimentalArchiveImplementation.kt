package io.github.yuroyami.archivekodec

/**
 * Marks interfaces whose implementations are not covered by the compatibility
 * promise: [io.github.yuroyami.archivekodec.codec.Codec] and
 * [io.github.yuroyami.archivekodec.archive.ArchiveReader]. Using them is stable;
 * implementing them requires `@OptIn(ExperimentalArchiveImplementation::class)`,
 * because a later release may add members that an implementation must then provide.
 * Java and Swift do not check Kotlin opt-in markers; the same caveat applies there.
 * [io.github.yuroyami.archivekodec.archive.RandomAccessSource] is not marked.
 */
@RequiresOptIn(
    message = "Members may be added to this interface in a later release; implementations must then be updated.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
public annotation class ExperimentalArchiveImplementation
