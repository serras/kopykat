@file:Suppress("WildcardImport")

package fp.serrano.kopykat

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.LambdaTypeName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.UNIT
import com.squareup.kotlinpoet.ksp.toTypeName
import fp.serrano.kopykat.utils.FileCompilerScope
import fp.serrano.kopykat.utils.TypeCompileScope
import fp.serrano.kopykat.utils.addDslMarkerClass
import fp.serrano.kopykat.utils.addGeneratedMarker
import fp.serrano.kopykat.utils.annotationClassName
import fp.serrano.kopykat.utils.kotlin.poet.buildFile
import fp.serrano.kopykat.utils.ksp.TypeCategory.Known.Data
import fp.serrano.kopykat.utils.ksp.TypeCategory.Known.Sealed
import fp.serrano.kopykat.utils.ksp.TypeCategory.Known.Value
import fp.serrano.kopykat.utils.ksp.category
import fp.serrano.kopykat.utils.ksp.onKnownCategory
import fp.serrano.kopykat.utils.name
import fp.serrano.kopykat.utils.sealedTypes

internal val TypeCompileScope.mutableCopyKt: FileSpec
  get() = buildFile(mutableTypeName) {
    file.addGeneratedMarker()

    addDslMarkerClass()
    addMutableCopy()
    addFreezeFunction()
    addToMutateFunction()
    addCopyClosure()

    if (category is Sealed) {
      addRetrofittedCopyFunction()
    }
  }

internal fun FileCompilerScope.addMutableCopy() {
  file.addClass(mutableClassName) {
    addAnnotation(annotationClassName)
    addTypeVariables(typeVariableNames)
    primaryConstructor {
      properties.forEach { property ->
        val originalName = property.type.toTypeName(typeParamResolver)
        val type = property.type.resolve()
        val declaration = type.declaration

        val typeName = type.takeIf { it.hasMutableCopy() }
          ?.let { ClassName(declaration.packageName.asString(), "Mutable${declaration.name}") }
          ?: originalName

        addParameter(property.asParameterSpec(typeName))
        addProperty(property.asPropertySpec(typeName) {
          mutable(true).initializer(property.name)
        })
      }
      addParameter(name = "old", type = targetClassName)
      addProperty(PropertySpec.builder(name = "old", type = targetClassName).initializer("old").build())
    }
  }
}

internal fun FileCompilerScope.addFreezeFunction() {
  onKnownCategory { category ->
    addFunction(
      name = "freeze",
      receives = mutableParameterized,
      returns = targetClassName,
    ) {
      val assignments = properties.map { it.toAssignment(".freeze()") }.joinToString()
      addReturn(
        when (category) {
          Data, Value -> "$targetTypeName($assignments)"
          Sealed -> sealedTypes.joinWithWhen(subject = "old") { "is ${it.name} -> old.copy($assignments)" }
        }
      )
    }
  }
}

internal fun FileCompilerScope.addToMutateFunction() {
  addFunction(
    name = "toMutable",
    receives = targetClassName,
    returns = mutableParameterized,
  ) {
    val assignments = properties.map { it.toAssignment(".toMutable()") } + "old = this"
    addReturn("$mutableParameterized(${assignments.joinToString()})")
  }
}

internal fun FileCompilerScope.addCopyClosure() {
  addCopyFunction {
    addParameter(
      name = "block",
      type = LambdaTypeName.get(receiver = mutableParameterized, returnType = UNIT),
    )
    addReturn("toMutable().apply(block).freeze()")
  }
}

private fun FileCompilerScope.addRetrofittedCopyFunction() {
  addCopyFunction {
    val propertyStatements = properties.map { property ->
      val typeName = property.type.toTypeName(typeParamResolver)
      addParameter(
        ParameterSpec.builder(name = property.name, type = typeName).defaultValue("this.${property.name}").build()
      )
      "${property.name} = ${property.name}"
    }.toList()

    addReturn(sealedTypes.joinWithWhen { "is ${it.name} -> this.copy(${propertyStatements.joinToString()})" })
  }
}

internal fun FileCompilerScope.addCopyFunction(block: FunSpec.Builder.() -> Unit) {
  addInlinedFunction(name = "copy", receives = targetClassName, returns = targetClassName, block = block)
}
