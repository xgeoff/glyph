package biz.digitalindustry.glyph.core

import biz.digitalindustry.glyph.core.ast.RecordDecl
import biz.digitalindustry.glyph.core.ast.RecordField

/**
 * Utilities for mapping Glyph record declarations into linear-memory layouts.
 */
class HeapLayout {
    static final int POINTER_SIZE = 4
    static final int ALIGNMENT = 4

    static RecordLayout forRecord(RecordDecl record) {
        if (!record) {
            throw new IllegalArgumentException('record must not be null')
        }
        Map<String, FieldLayout> fields = [:]
        int offset = 0
        record.fields.each { RecordField field ->
            offset = align(offset, ALIGNMENT)
            WasmValueType valueType = WasmValueType.fromTypeName(field.type)
            int fieldSize = sizeOf(valueType)
            FieldLayout layout = new FieldLayout(
                    field.name,
                    offset,
                    fieldSize,
                    valueType
            )
            fields[field.name] = layout
            offset += fieldSize
        }
        offset = align(offset, ALIGNMENT)
        new RecordLayout(record, fields.asImmutable(), offset, ALIGNMENT)
    }

    private static int sizeOf(WasmValueType valueType) {
        switch (valueType.kind) {
            case GlyphValueKind.I32:
                return 4
            case GlyphValueKind.STRING:
            case GlyphValueKind.POINTER:
                return POINTER_SIZE
            default:
                return POINTER_SIZE
        }
    }

    static int align(int value, int alignment = ALIGNMENT) {
        int remainder = value % alignment
        return remainder == 0 ? value : value + (alignment - remainder)
    }
}

class RecordLayout {
    final RecordDecl record
    final Map<String, FieldLayout> fieldsByName
    final int totalSize
    final int alignment

    RecordLayout(RecordDecl record,
                 Map<String, FieldLayout> fieldsByName,
                 int totalSize,
                 int alignment) {
        this.record = record
        this.fieldsByName = fieldsByName ?: [:]
        this.totalSize = totalSize
        this.alignment = alignment
    }

    FieldLayout field(String name) {
        FieldLayout layout = fieldsByName[name]
        if (!layout) {
            throw new IllegalStateException("Unknown field ${name} on record ${record?.name}")
        }
        return layout
    }
}

class FieldLayout {
    final String name
    final int offset
    final int size
    final WasmValueType type

    FieldLayout(String name, int offset, int size, WasmValueType type) {
        this.name = name
        this.offset = offset
        this.size = size
        this.type = type
    }
}

class RecordLayoutCache {
    private final Map<RecordDecl, RecordLayout> layouts = [:]

    RecordLayout layoutFor(RecordDecl record) {
        layouts.computeIfAbsent(record) { HeapLayout.forRecord(it) }
    }
}
