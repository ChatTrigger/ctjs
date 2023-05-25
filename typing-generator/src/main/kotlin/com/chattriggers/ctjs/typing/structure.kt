package com.chattriggers.ctjs.typing

sealed class Node(val name: String) {
    class Package(name: String, val children: MutableMap<String, Node>) : Node(name)

    sealed class Type(name: String, val pkg: String, val isCtApi: Boolean) : Node(name) {
        open fun qualifiedName(): String {
            if (isCtApi)
                return name

            if (pkg.startsWith("kotlin")) {
                return when (name) {
                    "Any" -> "any"
                    "Nothing" -> "never"
                    "Unit" -> "void"
                    "Byte", "Char", "Short", "Int", "Long", "Float", "Double" -> "number"
                    "Boolean" -> "boolean"
                    "String" -> "string"
                    "Array", "MutableList", "List" -> "Array"
                    "MutableMap", "Map" -> "Map"
                    else -> "$pkg.$name"
                }
            }

            return "$pkg.$name"
        }
    }

    open class TypeReference(
        name: String,
        pkg: String,
        val typeArguments: List<TypeReference>,
    ) : Type(name, pkg, false)

    open class TypeParameterReference(name: String) : TypeReference(name, "", emptyList()) {
        override fun qualifiedName() = name
    }

    // Used when we exceed the collection depth
    object Unknown : TypeReference("unknown", "", emptyList()) {
        override fun qualifiedName() = "unknown"
    }

    class Class(
        name: String,
        pkg: String,
        isCtApi: Boolean,
        val isInterface: Boolean,
        val typeParameters: List<String>,
        val children: List<Node>,
    ) : Type(name, pkg, isCtApi)

    class Enum(
        name: String,
        pkg: String,
        isCtApi: Boolean,
        val typeParameters: List<String>,
        val entries: List<EnumEntry>,
        val children: List<Node>,
    ) : Type(name, pkg, isCtApi)

    class EnumEntry(val name: String, val docString: String?)

    class Property(
        name: String,
        val type: TypeReference,
        val static: Boolean,
        val docString: String?,
    ) : Node(name)

    class Function(
        name: String,
        val typeParameters: List<String>,
        val params: List<Pair<String, TypeReference>>,
        val returnType: TypeReference?,
        val static: Boolean,
        val docString: String?,
    ) : Node(name)
}
