package androidx.annotation

/** A marker from Android's own build tools that some extensions carry (StreamPlay, XDMovies). It only labels code. */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.TYPE)
annotation class RecentlyNullable
