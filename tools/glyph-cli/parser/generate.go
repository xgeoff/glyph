package parser

//go:generate sh -c "pigeon -o glyph_parser.tmp ../../../grammar/glyph.peg && { echo 'package parser'; echo; cat glyph_parser.tmp; } > glyph_parser.go && rm glyph_parser.tmp"
