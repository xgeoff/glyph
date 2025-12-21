package biz.digitalindustry.glyph.core

import groovy.transform.Canonical

interface TypeRef {}

@Canonical
class PrimitiveType implements TypeRef {
    Type type
}

@Canonical
class NullableType implements TypeRef {
    TypeRef inner
}

@Canonical
class NullType implements TypeRef {}

@Canonical
class RecordType implements TypeRef {
    String name
}

@Canonical
class ArrayType implements TypeRef {
    TypeRef element
}

@Canonical
class MapType implements TypeRef {
    TypeRef key
    TypeRef value
}

@Canonical
class ParameterType {
    String name
    TypeRef type
}

@Canonical
class LambdaType implements TypeRef {
    List<ParameterType> parameters = []
    TypeRef returnType
}
