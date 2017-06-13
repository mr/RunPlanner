package net.ma.ttrobinson.runplanner

/**
 * Created by mattro on 6/9/17.
 * Disjoint unions
 */

sealed class Either<out L, out R>
data class Left<out L, out R>(val left: L) : Either<L, R>()
data class Right<out L, out R>(val right: R) : Either<L, R>()

fun <L, R, A> Either<L, R>.either(lf: (L) -> A, rf: (R) -> A): A = when (this) {
    is Left -> lf(left)
    is Right -> rf(right)
}

fun <L, R> List<Either<L, R>>.lefts(): List<L> {
    val res = ArrayList<L>()
    for (x in this) {
        when (x) {
            is Left -> res.add(x.left)
        }
    }
    return res
}

fun <L, R> List<Either<L, R>>.rights(): List<R> {
    val res = ArrayList<R>()
    for (x in this) {
        when (x) {
            is Right -> res.add(x.right)
        }
    }
    return res
}

fun <L, R> List<Either<L, R>>.partitionEithers(): Pair<List<L>, List<R>> {
    val lefts = ArrayList<L>()
    val rights = ArrayList<R>()
    for (x in this) {
        when (x) {
            is Left -> lefts.add(x.left)
            is Right -> rights.add(x.right)
        }
    }
    return Pair(lefts, rights)
}

fun <L, R, A> Either<L, R>.mapLeft(f: (L) -> A): Either<A, R> = when (this) {
    is Left -> Left(f(left))
    is Right -> Right(right)
}

fun <L, R, A> Either<L, R>.mapRight(f: (R) -> A): Either<L, A> = when (this) {
    is Left -> Left(left)
    is Right -> Right(f(right))
}

fun <L, R, A> Either<L, R>.flatMap(f: (R) -> Either<L, A>): Either<L, A> = when (this) {
    is Left -> Left(left)
    is Right -> f(right)
}
