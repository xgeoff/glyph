package main

import (
	"flag"
	"fmt"
	"os"
	"path/filepath"

	"glyph-cli/interpreter"
	"glyph-cli/parser"
	"glyph-cli/project"
)

func main() {
	var sourcePath string
	var rootPath string
	flag.StringVar(&sourcePath, "file", "", "Path to a Glyph source file")
	flag.StringVar(&rootPath, "root", "", "Project root directory (defaults to the source file directory)")
	flag.Parse()

	// Allow positional argument as shorthand.
	if sourcePath == "" && flag.NArg() > 0 {
		sourcePath = flag.Arg(0)
	}

	if sourcePath == "" {
		fmt.Fprintln(os.Stderr, "usage: glyph-cli -file path/to/file.gly")
		os.Exit(1)
	}

	absSource, err := filepath.Abs(sourcePath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "resolve source path: %v\n", err)
		os.Exit(1)
	}
	if rootPath == "" {
		rootPath = filepath.Dir(absSource)
	}
	absRoot, err := filepath.Abs(rootPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "resolve root path: %v\n", err)
		os.Exit(1)
	}

	index, err := project.BuildIndex(absRoot)
	if err != nil {
		fmt.Fprintf(os.Stderr, "failed to index project: %v\n", err)
		os.Exit(1)
	}
	program := index.Programs[absSource]
	if program == nil {
		program, err = parser.ParseProgramFile(absSource)
		if err != nil {
			fmt.Fprintf(os.Stderr, "parse error: %v\n", err)
			os.Exit(1)
		}
	}
	symbols, err := project.Resolve(program, index)
	if err != nil {
		fmt.Fprintf(os.Stderr, "symbol resolution error: %v\n", err)
		os.Exit(1)
	}

	if err := interpreter.Eval(program, symbols); err != nil {
		fmt.Fprintf(os.Stderr, "runtime error: %v\n", err)
		os.Exit(1)
	}
}
