package com.chattriggers.ctjs.typing

import com.chattriggers.ctjs.typing.annotations.CTApi
import com.chattriggers.ctjs.typing.annotations.useClassName
import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.symbol.*

@OptIn(KspExperimental::class)
class Collector(private val resolver: Resolver, private val logger: KSPLogger) {
    // Qualified name -> Node
    private val nodes = mutableMapOf<String, Node.Type>()

    fun collect(): List<Node> {
        resolver.getSymbolsWithAnnotation(CTApi::class.qualifiedName!!).forEach {
            require(it is KSDeclaration)
            collect(it, 0)
        }

        val topLevelTypes = mutableListOf<Node.Type>()
        val packages = mutableMapOf<String, Node.Package>()

        fun getPackage(path: List<String>): Node.Package {
            require(path.isNotEmpty())

            var (pkg, parts) = path.let {
                packages.getOrPut(it.first()) { Node.Package(it.first(), mutableMapOf()) } to it.drop(1)
            }

            parts.forEach {
                pkg = pkg.children.getOrPut(it) { Node.Package(it, mutableMapOf()) } as Node.Package
            }

            return pkg
        }

        nodes.values.forEach {
            if (it.isCtApi) {
                topLevelTypes.add(it)
            } else {
                getPackage(it.pkg.split('.')).children[it.qualifiedName()] = it
            }
        }

        return topLevelTypes + packages.values
    }

    private fun collect(decl: KSDeclaration, depth: Int): Node.Type {
        if (depth >= MAX_DEPTH)
            return Node.Unknown

        nodes[decl.qualName]?.let { return it }

        require(decl is KSClassDeclaration) {
            "Expected KSClassDeclaration, found ${decl::class.simpleName}"
        }

        return if (decl.classKind == ClassKind.ENUM_CLASS) {
            collectEnum(decl, depth)
        } else if (decl.classKind.let { it == ClassKind.CLASS || it == ClassKind.OBJECT || it == ClassKind.INTERFACE }) {
            collectClassOrInterface(decl, depth)
        } else TODO("Handle declaration type ${decl.classKind}")
    }

    private fun collectClassOrInterface(decl: KSClassDeclaration, depth: Int): Node.Type {
        if (decl.hasInvalidName())
            return Node.Unknown

        val annotation = decl.getAnnotationsByType(CTApi::class).singleOrNull()
        val name = if (annotation?.useClassName() != false) decl.name else annotation.name

        val forceStatic = if (annotation?.singleton == true) true else decl.classKind == ClassKind.OBJECT

        val isInterface = decl.classKind == ClassKind.INTERFACE
        val generics = decl.typeParameters.map { it.name.asString() }
        val children = mutableListOf<Node>()
        val node = Node.Class(name, decl.pkg, annotation != null, isInterface, generics, children)
        nodes[node.qualifiedName()] = node

        decl.declarations.mapNotNull {
            if (Modifier.PRIVATE in it.modifiers)
                return@mapNotNull null

            when (it) {
                is KSClassDeclaration -> collect(it, depth + 1)
                is KSPropertyDeclaration -> collectProperty(it, forceStatic, depth)
                is KSFunctionDeclaration -> collectFunction(it, forceStatic, depth)
                else -> TODO("Handle nested declaration of type ${it::class.simpleName} in collectClassOrInterface")
            }
        }.filter { it !is Node.Unknown }.forEach(children::add)

        return node
    }

    private fun collectEnum(decl: KSClassDeclaration, depth: Int): Node.Type {
        if (decl.hasInvalidName())
            return Node.Unknown

        val enumEntries = mutableListOf<Node.EnumEntry>()
        val other = mutableListOf<Node>()

        val annotation = decl.getAnnotationsByType(CTApi::class).singleOrNull()
        val name = if (annotation?.useClassName() != false) decl.name else annotation.name
        val generics = decl.typeParameters.map { it.name.asString() }

        val node = Node.Enum(name, decl.pkg, annotation != null, generics, enumEntries, other)
        nodes[node.qualifiedName()] = node

        decl.declarations.forEach {
            when (it) {
                is KSClassDeclaration -> if (it.classKind == ClassKind.ENUM_ENTRY) {
                    enumEntries.add(Node.EnumEntry(it.name, it.docString))
                } else {
                    collect(it, depth + 1).takeIf { n -> n !is Node.Unknown }?.let(other::add)
                }
                is KSPropertyDeclaration -> other.add(collectProperty(it, false, depth))
                is KSFunctionDeclaration -> collectFunction(it, false, depth)?.let(other::add)
                else -> TODO("Handle nested declaration of type ${it::class.simpleName} in collectEnum")
            }
        }

        return node
    }

    private fun collectProperty(decl: KSPropertyDeclaration, forceStatic: Boolean, depth: Int): Node.Property {
        require(depth < MAX_DEPTH)

        return Node.Property(
            decl.name,
            decl.type.resolveToNode(depth + 1),
            forceStatic || Modifier.JAVA_STATIC in decl.modifiers,
            decl.docString,
        )
    }

    private fun collectFunction(decl: KSFunctionDeclaration, forceStatic: Boolean, depth: Int): Node.Function? {
        require(depth < MAX_DEPTH)

        if (decl.name in excludedMethods)
            return null

        // if (decl.name == "getEnchantments")
        //     logger.warn(decl.returnType!!.element?.typeArguments)

        return Node.Function(
            decl.name,
            decl.typeParameters.map { it.name.asString() },
            decl.parameters.map { (it.name?.asString() ?: "_") to it.type.resolveToNode(depth + 1) },
            decl.returnType?.resolveToNode(depth + 1),
            forceStatic || Modifier.JAVA_STATIC in decl.modifiers,
            decl.docString,
        )
    }

    private val KSDeclaration.name: String
        get() = simpleName.asString()

    private val KSDeclaration.pkg: String
        get() = packageName.asString()

    private val KSDeclaration.qualName: String
        get() = qualifiedName!!.asString()

    private fun KSTypeReference.resolveToNode(depth: Int): Node.TypeReference {
        if (depth >= MAX_DEPTH)
            return Node.Unknown

        val decl = resolve().declaration
        if (decl is KSTypeAlias)
            return decl.type.resolveToNode(depth)

        if (decl is KSTypeParameter)
            return Node.TypeParameterReference(decl.name.asString())

        if (decl.qualName !in nodes)
            collect(decl, depth)

        return Node.TypeReference(decl.name, decl.pkg, element?.typeArguments?.map {
            it.type?.resolveToNode(depth + 1) ?: Node.Unknown
        }.orEmpty())
    }

    private fun KSDeclaration.hasInvalidName(): Boolean {
        // We can't generate a symbol with the name of a typescript reserved word, so we
        // check here to make sure that isn't the case.
        if (pkg.split('.').any { it in typescriptReservedWords })
            return true

        return name in typescriptReservedWords
    }

    companion object {
        private const val MAX_DEPTH = 3

        private val excludedMethods = setOf(
            "<init>", "<clinit>", "equals", "hashCode", "toString", "finalize", "compareTo", "clone",
        )

        // Typescript keywords
        private val typescriptReservedWords = setOf(
            "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do", "else",
            "enum", "export", "extends", "false", "finally", "for", "function", "if", "import", "in", "instanceof",
            "new", "null", "return", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void",
            "while", "with",
        )
    }
}
