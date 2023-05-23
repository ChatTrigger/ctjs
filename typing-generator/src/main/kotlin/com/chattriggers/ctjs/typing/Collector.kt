package com.chattriggers.ctjs.typing

import com.chattriggers.ctjs.typing.annotations.CTApi
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias

@OptIn(KspExperimental::class)
class Collector(private val resolver: Resolver) {
    private val queue = ArrayDeque<KSClassDeclaration>()
    private val processedClasses = mutableSetOf<String>()

    private val packages = mutableMapOf<String, Container.Package>()
    private val topLevelClasses = mutableListOf<Container.Class>()

    fun collect(): List<Container> {
        resolver.getSymbolsWithAnnotation(CTApi::class.qualifiedName!!).forEach {
            require(it is KSClassDeclaration)
            queue.addLast(it)
        }

        while (queue.isNotEmpty())
            collect(queue.removeFirst())

        return topLevelClasses + packages.values
    }

    private fun collect(decl: KSDeclaration) {
        if (decl is KSTypeAlias)
            return collect(decl.type.resolve().declaration)

        if (decl !is KSClassDeclaration)
            return

        val apiAnnotation = decl.getAnnotationsByType(CTApi::class).singleOrNull()
        if (apiAnnotation != null) {
            topLevelClasses.add(Container.Class(decl))
        } else {
            getPackage(decl.packageName.asString()).classes.add(Container.Class(decl))
        }
        
        for (property in decl.getAllProperties())
            collect(property.type.resolve().declaration)

        for (function in decl.getAllFunctions()) {
            function.parameters.forEach { collect(it.type.resolve().declaration) }
            function.returnType?.resolve()?.declaration?.let(::collect)
        }
    }

    private fun getPackage(name: String): Container.Package {
        var (pkg, parts) = name.split('.').let {
            packages.getOrPut(it.first()) { Container.Package(it.first()) } to it.drop(1)
        }

        parts.forEach {
           pkg = pkg.subPackages.getOrPut(it) { Container.Package(it) }
        }

        return pkg
    }
}

sealed interface Container {
    class Class(val decl: KSClassDeclaration) : Container

    class Package(
        val name: String,
        val subPackages: MutableMap<String, Package> = mutableMapOf(),
        val classes: MutableList<Class> = mutableListOf(),
    ) : Container
}

fun KSType.typeName(resolver: Resolver): String {
    if (isMarkedNullable)
        return makeNotNullable().typeName(resolver) + " | null"

    return when (this) {
        resolver.builtIns.anyType -> "any"
        resolver.builtIns.nothingType -> "never"
        resolver.builtIns.unitType -> "void"
        resolver.builtIns.numberType,
        resolver.builtIns.byteType,
        resolver.builtIns.shortType,
        resolver.builtIns.intType,
        resolver.builtIns.longType,
        resolver.builtIns.floatType,
        resolver.builtIns.doubleType,
        resolver.builtIns.charType -> "number"
        resolver.builtIns.booleanType -> "boolean"
        resolver.builtIns.stringType -> "string"
        resolver.builtIns.iterableType -> TODO()
        resolver.builtIns.arrayType -> {
            val inner = arguments.single().type?.resolve() ?: "unknown"
            "Array<$inner>"
        }
        else -> declaration.simpleName.asString()
    }
}
