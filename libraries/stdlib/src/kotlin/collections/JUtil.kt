@file:kotlin.jvm.JvmMultifileClass
@file:kotlin.jvm.JvmName("CollectionsKt")

package kotlin.collections

import java.io.Serializable
import java.util.*
import kotlin.comparisons.compareValues

internal object EmptyIterator : ListIterator<Nothing> {
    override fun hasNext(): Boolean = false
    override fun hasPrevious(): Boolean = false
    override fun nextIndex(): Int = 0
    override fun previousIndex(): Int = -1
    override fun next(): Nothing = throw NoSuchElementException()
    override fun previous(): Nothing = throw NoSuchElementException()
}

internal object EmptyList : List<Nothing>, Serializable {
    override fun equals(other: Any?): Boolean = other is List<*> && other.isEmpty()
    override fun hashCode(): Int = 1
    override fun toString(): String = "[]"

    override val size: Int get() = 0
    override fun isEmpty(): Boolean = true
    override fun contains(element: Nothing): Boolean = false
    override fun containsAll(elements: Collection<Nothing>): Boolean = elements.isEmpty()

    override fun get(index: Int): Nothing = throw IndexOutOfBoundsException("Index $index is out of bound of empty list.")
    override fun indexOf(element: Nothing): Int = -1
    override fun lastIndexOf(element: Nothing): Int = -1

    override fun iterator(): Iterator<Nothing> = EmptyIterator
    override fun listIterator(): ListIterator<Nothing> = EmptyIterator
    override fun listIterator(index: Int): ListIterator<Nothing> {
        if (index != 0) throw IndexOutOfBoundsException("Index: $index")
        return EmptyIterator
    }

    override fun subList(fromIndex: Int, toIndex: Int): List<Nothing> {
        if (fromIndex == 0 && toIndex == 0) return this
        throw IndexOutOfBoundsException("fromIndex: $fromIndex, toIndex: $toIndex")
    }

    private fun readResolve(): Any = EmptyList
}

internal fun <T> Array<out T>.asCollection(): Collection<T> = ArrayAsCollection(this)

private class ArrayAsCollection<T>(val values: Array<out T>): Collection<T> {
    override val size: Int get() = values.size
    override fun isEmpty(): Boolean = values.isEmpty()
    override fun contains(element: T): Boolean = values.contains(element)
    override fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }
    override fun iterator(): Iterator<T> = values.iterator()
    // override hidden toArray implementation to prevent copying of values array
    public fun toArray(): Array<out Any?> = values.varargToArrayOfAny()
}

/** Returns an empty read-only list.  The returned list is serializable (JVM). */
public fun <T> emptyList(): List<T> = EmptyList

/** Returns a new read-only list of given elements.  The returned list is serializable (JVM). */
public fun <T> listOf(vararg elements: T): List<T> = if (elements.size > 0) elements.asList() else emptyList()

/** Returns an empty read-only list.  The returned list is serializable (JVM). */
@kotlin.internal.InlineOnly
public inline fun <T> listOf(): List<T> = emptyList()

/**
 * Returns an immutable list containing only the specified object [element].
 * The returned list is serializable.
 */
@JvmVersion
public inline fun <T> listOf(element: T): List<T> = Collections.singletonList(element)

/** Returns a new [LinkedList] with the given elements. */
@JvmVersion
@Deprecated("Use LinkedList constructor.", ReplaceWith("LinkedList(listOf(*elements))", "java.util.LinkedList"), level = DeprecationLevel.ERROR)
public fun <T> linkedListOf(vararg elements: T): LinkedList<T>
        = if (elements.size == 0) LinkedList() else LinkedList(ArrayAsCollection(elements))

@Deprecated("Use LinkedList constructor.", ReplaceWith("LinkedList<T>()", "java.util.LinkedList"), level = DeprecationLevel.ERROR)
public fun <T> linkedListOf() = LinkedList<T>()


/** Returns a new [MutableList] with the given elements. */
public fun <T> mutableListOf(vararg elements: T): MutableList<T>
        = if (elements.size == 0) ArrayList() else ArrayList(ArrayAsCollection(elements))

/** Returns a new [ArrayList] with the given elements. */
public fun <T> arrayListOf(vararg elements: T): ArrayList<T>
        = if (elements.size == 0) ArrayList() else ArrayList(ArrayAsCollection(elements))

/** Returns a new read-only list either of single given element, if it is not null, or empty list it the element is null. The returned list is serializable (JVM). */
public fun <T : Any> listOfNotNull(element: T?): List<T> = if (element != null) listOf(element) else emptyList()

/** Returns a new read-only list only of those given elements, that are not null.  The returned list is serializable (JVM). */
public fun <T : Any> listOfNotNull(vararg elements: T?): List<T> = elements.filterNotNull()

/**
 * Returns an [IntRange] of the valid indices for this collection.
 */
public val Collection<*>.indices: IntRange
    get() = 0..size - 1

/**
 * Returns the index of the last item in the list or -1 if the list is empty.
 *
 * @sample test.collections.ListSpecificTest.lastIndex
 */
public val <T> List<T>.lastIndex: Int
    get() = this.size - 1

/** Returns `true` if the collection is not empty. */
@kotlin.internal.InlineOnly
public inline fun <T> Collection<T>.isNotEmpty(): Boolean = !isEmpty()

/** Returns this Collection if it's not `null` and the empty list otherwise. */
@kotlin.internal.InlineOnly
public inline fun <T> Collection<T>?.orEmpty(): Collection<T> = this ?: emptyList()

/** Returns this List if it's not `null` and the empty list otherwise. */
@kotlin.internal.InlineOnly
public inline fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

/**
 * Returns a list containing the elements returned by this enumeration
 * in the order they are returned by the enumeration.
 */
@JvmVersion
@kotlin.internal.InlineOnly
public inline fun <T> Enumeration<T>.toList(): List<T> = Collections.list(this)

/**
 * Returns the size of this iterable if it is known, or `null` otherwise.
 */
@kotlin.internal.InlineOnly
public inline fun <T> Iterable<T>.collectionSizeOrNull(): Int? = if (this is Collection<*>) this.size else null

/**
 * Returns the size of this iterable if it is known, or the specified [default] value otherwise.
 */
public fun <T> Iterable<T>.collectionSizeOrDefault(default: Int): Int = if (this is Collection<*>) this.size else default

/** Returns true when it's safe to convert this collection to a set without changing contains method behavior. */
private fun <T> Collection<T>.safeToConvertToSet() = size > 2 && this is ArrayList

/** Converts this collection to a set, when it's worth so and it doesn't change contains method behavior. */
internal fun <T> Iterable<T>.convertToSetForSetOperationWith(source: Iterable<T>): Collection<T> =
        when(this) {
            is Set -> this
            is Collection ->
                when {
                    source is Collection && source.size < 2 -> this
                    else -> if (this.safeToConvertToSet()) toHashSet() else this
                }
            else -> toHashSet()
        }

/** Converts this collection to a set, when it's worth so and it doesn't change contains method behavior. */
internal fun <T> Iterable<T>.convertToSetForSetOperation(): Collection<T> =
        when(this) {
            is Set -> this
            is Collection -> if (this.safeToConvertToSet()) toHashSet() else this
            else -> toHashSet()
        }

// copies typed varargs array to array of objects
@JvmVersion
private fun <T> Array<out T>.varargToArrayOfAny(): Array<Any?>
        = Arrays.copyOf(this, this.size, Array<Any?>::class.java)

/**
 * Searches this list or its range for the provided [element] index using binary search algorithm.
 * The list is expected to be sorted into ascending order according to the Comparable natural ordering of its elements.
 *
 * If the list contains multiple elements equal to the specified object, there is no guarantee which one will be found.
 */
public fun <T: Comparable<T>> List<T?>.binarySearch(element: T?, fromIndex: Int = 0, toIndex: Int = size): Int {
    rangeCheck(size, fromIndex, toIndex)

    var low = fromIndex
    var high = toIndex - 1

    while (low <= high) {
        val mid = (low + high).ushr(1) // safe from overflows
        val midVal = get(mid)
        val cmp = compareValues(midVal, element)

        if (cmp < 0)
            low = mid + 1
        else if (cmp > 0)
            high = mid - 1
        else
            return mid // key found
    }
    return -(low + 1)  // key not found
}

/**
 * Searches this list or its range for the provided [element] index using binary search algorithm.
 * The list is expected to be sorted into ascending order according to the specified [comparator].
 *
 * If the list contains multiple elements equal to the specified object, there is no guarantee which one will be found.
 */
public fun <T> List<T>.binarySearch(element: T, comparator: Comparator<in T>, fromIndex: Int = 0, toIndex: Int = size): Int {
    rangeCheck(size, fromIndex, toIndex)

    var low = fromIndex
    var high = toIndex - 1

    while (low <= high) {
        val mid = (low + high).ushr(1) // safe from overflows
        val midVal = get(mid)
        val cmp = comparator.compare(midVal, element)

        if (cmp < 0)
            low = mid + 1
        else if (cmp > 0)
            high = mid - 1
        else
            return mid // key found
    }
    return -(low + 1)  // key not found
}

/**
 * Searches this list or its range for an index of an element with the provided [key] using binary search algorithm.
 * The list is expected to be sorted into ascending order according to the Comparable natural ordering of keys of its elements.
 *
 * If the list contains multiple elements with the specified [key], there is no guarantee which one will be found.
 */
public inline fun <T, K : Comparable<K>> List<T>.binarySearchBy(key: K?, fromIndex: Int = 0, toIndex: Int = size, crossinline selector: (T) -> K?): Int =
        binarySearch(fromIndex, toIndex) { compareValues(selector(it), key) }

// do not introduce this overload --- too rare
//public fun <T, K> List<T>.binarySearchBy(key: K, comparator: Comparator<K>, fromIndex: Int = 0, toIndex: Int = size(), selector: (T) -> K): Int =
//        binarySearch(fromIndex, toIndex) { comparator.compare(selector(it), key) }

/**
 * Searches this list or its range for an index of an element for which [comparison] function returns zero.
 * The list is expected to be sorted into ascending order according to the provided [comparison].
 *
 * @param comparison function that compares an element of the list with the element being searched.
 */
public fun <T> List<T>.binarySearch(fromIndex: Int = 0, toIndex: Int = size, comparison: (T) -> Int): Int {
    rangeCheck(size, fromIndex, toIndex)

    var low = fromIndex
    var high = toIndex - 1

    while (low <= high) {
        val mid = (low + high).ushr(1) // safe from overflows
        val midVal = get(mid)
        val cmp = comparison(midVal)

        if (cmp < 0)
            low = mid + 1
        else if (cmp > 0)
            high = mid - 1
        else
            return mid // key found
    }
    return -(low + 1)  // key not found
}

/**
 * Checks that `from` and `to` are in
 * the range of [0..size] and throws an appropriate exception, if they aren't.
 */
private fun rangeCheck(size: Int, fromIndex: Int, toIndex: Int) {
    when {
        fromIndex > toIndex -> throw IllegalArgumentException("fromIndex ($fromIndex) is greater than toIndex ($toIndex)")
        fromIndex < 0 -> throw IndexOutOfBoundsException("fromIndex ($fromIndex) is less than zero.")
        toIndex > size -> throw IndexOutOfBoundsException("toIndex ($toIndex) is greater than size ($size).")
    }
}

/**
 * Returns a single list of all elements from all collections in the given collection.
 */
public fun <T> Iterable<Iterable<T>>.flatten(): List<T> {
    val result = ArrayList<T>()
    for (element in this) {
        result.addAll(element)
    }
    return result
}

/**
 * Returns a pair of lists, where
 * *first* list is built from the first values of each pair from this collection,
 * *second* list is built from the second values of each pair from this collection.
 */
public fun <T, R> Iterable<Pair<T, R>>.unzip(): Pair<List<T>, List<R>> {
    val expectedSize = collectionSizeOrDefault(10)
    val listT = ArrayList<T>(expectedSize)
    val listR = ArrayList<R>(expectedSize)
    for (pair in this) {
        listT.add(pair.first)
        listR.add(pair.second)
    }
    return listT to listR
}

