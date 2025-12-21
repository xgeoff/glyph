PARSER=parser/glyph_parser.go
GRAMMAR=../../../grammar/glyph.peg

.PHONY: parser
parser:
	pigeon -o parser/glyph_parser.tmp $(GRAMMAR)
	printf "package parser\n\n" > $(PARSER)
	cat parser/glyph_parser.tmp >> $(PARSER)
	rm parser/glyph_parser.tmp
