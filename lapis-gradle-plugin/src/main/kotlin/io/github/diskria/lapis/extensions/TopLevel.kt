package io.github.diskria.lapis.extensions

@PublishedApi
internal inline fun <reified T : Any> defaultNameBySuffix(suffix: String): String {
    val className = checkNotNull(T::class.simpleName) {
        "Cannot derive name: class '${T::class}' does not have a simple name"
    }
    check(className != suffix && className.endsWith(suffix)) {
        "Class name '$className' must end with '$suffix' suffix"
    }
    return className.removeSuffix(suffix).decapitalized()
}
