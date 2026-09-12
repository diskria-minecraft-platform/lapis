package io.github.diskria.lapis.ksp.phases.lowering.types

import io.github.diskria.lapis.ksp.extensions.jp.JPList
import io.github.diskria.lapis.ksp.extensions.jp.JPMap
import io.github.diskria.lapis.ksp.extensions.jp.JPSet
import io.github.diskria.lapis.ksp.extensions.jp.JPString
import io.github.diskria.lapis.ksp.extensions.kp.KPList
import io.github.diskria.lapis.ksp.extensions.kp.KPMap
import io.github.diskria.lapis.ksp.extensions.kp.KPSet
import io.github.diskria.lapis.ksp.extensions.kp.KPString
import io.github.diskria.lapis.ksp.phases.lowering.asIrClassName
import io.github.diskria.poetesse.java.JPClassName
import io.github.diskria.poetesse.java.JPObject
import io.github.diskria.poetesse.kotlin.KPAny
import io.github.diskria.poetesse.kotlin.KPClassName

class IrClassName(override val kotlin: KPClassName) : IrTypeName(kotlin) {

    val packageName: String? = kotlin.packageName.takeIf { it.isNotEmpty() }
    val simpleName: String = kotlin.simpleName
    val nestedName: String = kotlin.simpleNames.joinToString(".")
    val qualifiedName: String = listOfNotNull(packageName, nestedName).joinToString(".")

    override val java: JPClassName by lazy {
        box().getJavaPrimitiveType(allowVoid = false) as? JPClassName ?: when (kotlin) {
            KPAny -> JPObject
            KPString -> JPString
            KPList -> JPList
            KPSet -> JPSet
            KPMap -> JPMap
            else -> JPClassName.get(
                packageName,
                kotlin.simpleNames.first(),
                *kotlin.simpleNames.drop(1).toTypedArray()
            )
        }
    }

    fun nested(name: String): IrClassName =
        kotlin.nestedClass(name).asIrClassName()

    fun derived(suffix: String): IrClassName =
        of(packageName, (kotlin.simpleNames + suffix).joinToString("_"))

    companion object {
        fun of(packageName: String?, vararg names: String): IrClassName =
            KPClassName(packageName.orEmpty(), *names).asIrClassName()
    }
}
