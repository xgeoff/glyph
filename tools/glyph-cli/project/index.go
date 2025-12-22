package project

import (
	"fmt"
	"io/fs"
	"os"
	"path/filepath"

	"glyph-cli/ast"
	"glyph-cli/parser"
)

// Index holds all declarations discovered within a project tree.
type Index struct {
	Functions map[string]*ast.FunctionDecl
	Records   map[string]*ast.RecordDecl
	Aliases   map[string]*ast.TypeAliasDecl
	Programs  map[string]*ast.Program
}

// BuildIndex scans rootDir (and optional library directories) for .gly files.
func BuildIndex(rootDir string, libDirs ...string) (*Index, error) {
	idx := &Index{
		Functions: make(map[string]*ast.FunctionDecl),
		Records:   make(map[string]*ast.RecordDecl),
		Aliases:   make(map[string]*ast.TypeAliasDecl),
		Programs:  make(map[string]*ast.Program),
	}

	if err := scanDir(rootDir, idx); err != nil {
		return nil, err
	}
	for _, lib := range libDirs {
		if lib == "" {
			continue
		}
		if info, err := os.Stat(lib); err != nil || !info.IsDir() {
			continue
		}
		if err := scanDir(lib, idx); err != nil {
			return nil, err
		}
	}

	return idx, nil
}

func scanDir(rootDir string, idx *Index) error {
	return filepath.WalkDir(rootDir, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		if filepath.Ext(path) != ".gly" {
			return nil
		}

		program, parseErr := parser.ParseProgramFile(path)
		if parseErr != nil {
			return fmt.Errorf("parse %s: %w", path, parseErr)
		}
		abs, absErr := filepath.Abs(path)
		if absErr != nil {
			return absErr
		}
		if _, exists := idx.Programs[abs]; exists {
			return nil
		}
		idx.Programs[abs] = program

		pkg := packageName(program)
		for _, alias := range program.TypeAliases {
			fqn := qualify(pkg, alias.Name)
			if _, exists := idx.Aliases[fqn]; exists {
				return fmt.Errorf("duplicate type alias %s", fqn)
			}
			idx.Aliases[fqn] = alias
		}
		for _, rec := range program.Records {
			fqn := qualify(pkg, rec.Name)
			if _, exists := idx.Records[fqn]; exists {
				return fmt.Errorf("duplicate record %s", fqn)
			}
			idx.Records[fqn] = rec
		}
		for _, fn := range program.Functions {
			fqn := qualify(pkg, fn.Name)
			if _, exists := idx.Functions[fqn]; exists {
				return fmt.Errorf("duplicate function %s", fqn)
			}
			idx.Functions[fqn] = fn
		}
		return nil
	})
}

// Resolve constructs the visible symbol set for the provided program.
func Resolve(program *ast.Program, idx *Index) (*Symbols, error) {
	if idx == nil {
		return nil, fmt.Errorf("index is nil")
	}
	pkg := packageName(program)
	functions := make(map[string]*ast.FunctionDecl)
	records := make(map[string]*ast.RecordDecl)
	aliases := make(map[string]*ast.TypeAliasDecl)

	addPackageSymbols(pkg, idx, functions, records, aliases)

	for _, rec := range program.Records {
		records[rec.Name] = rec
	}
	for _, fn := range program.Functions {
		functions[fn.Name] = fn
	}
	for _, alias := range program.TypeAliases {
		aliases[alias.Name] = alias
	}

	for _, imp := range program.Imports {
		simple := simpleName(imp.Name)
		if fn, ok := idx.Functions[imp.Name]; ok {
			if _, exists := functions[simple]; exists {
				return nil, fmt.Errorf("symbol %s already defined", simple)
			}
			functions[simple] = fn
			continue
		}
		if rec, ok := idx.Records[imp.Name]; ok {
			if _, exists := records[simple]; exists {
				return nil, fmt.Errorf("symbol %s already defined", simple)
			}
			records[simple] = rec
			continue
		}
		if alias, ok := idx.Aliases[imp.Name]; ok {
			if _, exists := aliases[simple]; exists {
				return nil, fmt.Errorf("symbol %s already defined", simple)
			}
			aliases[simple] = alias
			continue
		}
		return nil, fmt.Errorf("symbol not found: %s", imp.Name)
	}

	return &Symbols{
		Package:   pkg,
		Functions: functions,
		Records:   records,
		Aliases:   aliases,
	}, nil
}

type Symbols struct {
	Package   string
	Functions map[string]*ast.FunctionDecl
	Records   map[string]*ast.RecordDecl
	Aliases   map[string]*ast.TypeAliasDecl
}

func packageName(program *ast.Program) string {
	if program != nil && program.Package != nil {
		return program.Package.Name
	}
	return ""
}

func qualify(pkg, name string) string {
	if pkg == "" {
		return name
	}
	return pkg + "." + name
}

func packagePart(fqn string) string {
	if idx := lastDot(fqn); idx >= 0 {
		return fqn[:idx]
	}
	return ""
}

func simpleName(fqn string) string {
	if idx := lastDot(fqn); idx >= 0 {
		return fqn[idx+1:]
	}
	return fqn
}

func lastDot(s string) int {
	for i := len(s) - 1; i >= 0; i-- {
		if s[i] == '.' {
			return i
		}
	}
	return -1
}

func addPackageSymbols(pkg string, idx *Index, functions map[string]*ast.FunctionDecl, records map[string]*ast.RecordDecl, aliases map[string]*ast.TypeAliasDecl) {
	for fqn, fn := range idx.Functions {
		if packagePart(fqn) != pkg {
			continue
		}
		name := simpleName(fqn)
		if _, exists := functions[name]; exists {
			continue
		}
		functions[name] = fn
	}
	for fqn, rec := range idx.Records {
		if packagePart(fqn) != pkg {
			continue
		}
		name := simpleName(fqn)
		if _, exists := records[name]; exists {
			continue
		}
		records[name] = rec
	}
	for fqn, alias := range idx.Aliases {
		if packagePart(fqn) != pkg {
			continue
		}
		name := simpleName(fqn)
		if _, exists := aliases[name]; exists {
			continue
		}
		aliases[name] = alias
	}
}
