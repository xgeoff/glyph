package interpreter

import (
	"bytes"
	"io"
	"os"
	"strings"
	"testing"

	"glyph-cli/ast"
	"glyph-cli/project"
)

func TestMatchBindingsDoNotLeakIntoOuterScope(t *testing.T) {
	program := inlineProgram()
	symbols := inlineSymbols(program)

	var buf bytes.Buffer
	origStdout := os.Stdout
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	os.Stdout = w
	if err := Eval(program, symbols); err != nil {
		t.Fatalf("eval: %v", err)
	}
	w.Close()
	os.Stdout = origStdout
	if _, err := io.Copy(&buf, r); err != nil {
		t.Fatalf("read pipe: %v", err)
	}
	r.Close()

	output := strings.Split(strings.TrimSpace(buf.String()), "\n")
	expected := []string{"outer", "inner"}
	if len(output) != len(expected) {
		t.Fatalf("expected %d lines, got %d: %v", len(expected), len(output), output)
	}
	for i, line := range expected {
		if output[i] != line {
			t.Fatalf("line %d mismatch: expected %q, got %q", i, line, output[i])
		}
	}
}

func inlineSymbols(program *ast.Program) *project.Symbols {
	symbols := &project.Symbols{
		Package:   "",
		Functions: make(map[string]*ast.FunctionDecl),
		Records:   make(map[string]*ast.RecordDecl),
		Aliases:   make(map[string]*ast.TypeAliasDecl),
	}
	for _, fn := range program.Functions {
		symbols.Functions[fn.Name] = fn
	}
	for _, rec := range program.Records {
		symbols.Records[rec.Name] = rec
	}
	for _, alias := range program.TypeAliases {
		symbols.Aliases[alias.Name] = alias
	}
	return symbols
}

func inlineProgram() *ast.Program {
	userRecord := &ast.RecordDecl{
		Name: "User",
		Fields: []*ast.RecordField{
			{Name: "name", Type: "string", Mutability: "var"},
		},
	}

	matchPattern := &ast.RecordPattern{
		TypeName: "User",
		Fields: []*ast.RecordFieldPattern{
			{Field: "name", Pattern: &ast.VarPattern{Name: "name"}},
		},
	}

	matchExpr := &ast.MatchExpr{
		Target: &ast.VarRef{Name: "u"},
		Cases: []*ast.MatchCase{
			{
				Pattern: matchPattern,
				Value:   &ast.VarRef{Name: "name"},
			},
		},
		ElseExpr: &ast.StringLiteral{Value: "none"},
	}

	mainFn := &ast.FunctionDecl{
		Name:       "main",
		ReturnType: "void",
		Body: &ast.Block{
			Statements: []ast.Statement{
				&ast.VarDecl{
					Name:       "name",
					Mutability: "val",
					Value:      &ast.StringLiteral{Value: "outer"},
				},
				&ast.VarDecl{
					Name:       "u",
					Mutability: "val",
					Value: &ast.RecordLiteral{
						TypeName: "User",
						Fields: map[string]ast.Expr{
							"name": &ast.StringLiteral{Value: "inner"},
						},
					},
				},
				&ast.VarDecl{
					Name:       "result",
					Mutability: "val",
					Value:      matchExpr,
				},
				&ast.PrintStmt{Expr: &ast.VarRef{Name: "name"}},
				&ast.PrintStmt{Expr: &ast.VarRef{Name: "result"}},
			},
		},
	}

	return &ast.Program{
		Records:   []*ast.RecordDecl{userRecord},
		Functions: []*ast.FunctionDecl{mainFn},
	}
}
