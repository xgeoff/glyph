package parser

import (
	"fmt"

	"glyph-cli/ast"
)

// ParseProgramFile wraps the generated ParseFile to return a typed AST.
func ParseProgramFile(path string) (*ast.Program, error) {
	result, err := ParseFile(path)
	if err != nil {
		return nil, err
	}

	program, ok := result.(*ast.Program)
	if !ok {
		return nil, fmt.Errorf("unexpected parse result type %T", result)
	}
	return program, nil
}
