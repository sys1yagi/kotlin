// WITH_RUNTIME
// sortedSetOf returns TreeSet not SortedSet
val foo = sortedSetOf(1, 2, 3).toSortedSet().toSortedSet()<caret>